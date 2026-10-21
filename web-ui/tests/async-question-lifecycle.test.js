import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { asyncQuestionReplyMode } from "../src/async-question-state.js";
import { questionPanelKey, snapshotIsCurrent } from "../src/floating-panel-state.js";

const source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
const program = source.slice(source.indexOf("const asyncQuestionsByThread = new Map();"), source.indexOf("function formatGoalDuration("));
const question = (turn = "turn-a", id = "question-a") => ({ item_id: id, turn_id: turn, questions: [{ title: "Synthetic question?" }] });

function fixture(questions = [question()], activeTurnId = "turn-a") {
  const state = { current: { id: "thread-a" }, activeTurnId, authoritativeThreadActive: new Map([["thread-a", true]]) };
  const commands = [], nodes = new Map();
  const answer = { value: "Typed answer" };
  const field = { querySelector: (selector) => selector.startsWith("input") ? { value: "__custom__" } : answer };
  const $ = (id) => {
    if (!nodes.has(id)) nodes.set(id, { hidden: false, textContent: "", disabled: false,
      replaceChildren() { throw new Error("A live question's typed input must not be rebuilt"); },
      querySelectorAll: () => [field] });
    return nodes.get(id);
  };
  const ctx = vm.createContext({ state, $, fixtureQuestions: questions, questionPanelKey, snapshotIsCurrent,
    asyncQuestionReplyMode, currentThreadWritable: () => true, requireCurrentThreadWriter() {},
    tr: (key) => key, command: async (body) => { commands.push(body); return {}; },
    scheduleEventRefresh() {}, refreshActivity: async () => {},
  });
  vm.runInContext(program, ctx);
  vm.runInContext('asyncQuestionsByThread.set("thread-a", fixtureQuestions); renderedAsyncQuestionKey = questionPanelKey("thread-a", fixtureQuestions.find(q => q.turn_id === state.activeTurnId));', ctx);
  return { ctx, state, $, commands, answer, questions,
    read: (code) => vm.runInContext(code, ctx),
  };
}

test("a finished question hides its panel and restore button without changing history", () => {
  const f = fixture();
  f.ctx.renderAsyncQuestion();
  assert.equal(f.$("asyncQuestionPanel").hidden, false);
  f.state.activeTurnId = null;
  f.state.authoritativeThreadActive.set("thread-a", false);
  f.ctx.finishAsyncQuestionTurn("thread-a", "turn-a");
  f.ctx.renderAsyncQuestion();
  assert.equal(f.$("asyncQuestionPanel").hidden, true);
  assert.equal(f.$("asyncQuestionRestoreBtn").hidden, true);
  assert.equal(f.questions.length, 1, "The original history record is retained");
  assert.equal(f.questions[0].item_id, "question-a");
});

test("a late question snapshot cannot resurrect a finished turn", async () => {
  const f = fixture();
  let resolve;
  f.ctx.command = () => new Promise((done) => { resolve = done; });
  const loading = f.ctx.refreshAsyncQuestions("thread-a");
  f.ctx.finishAsyncQuestionTurn("thread-a", "turn-a");
  f.state.activeTurnId = null;
  resolve({ questions: [question()] });
  await loading;
  f.ctx.renderAsyncQuestion();
  assert.equal(f.read('asyncQuestionsByThread.get("thread-a").length'), 0);
  assert.equal(f.$("asyncQuestionPanel").hidden, true);
});

test("an older completion preserves the successor question and its typed input", () => {
  const f = fixture([question(), question("turn-b", "question-b")], "turn-b");
  f.ctx.renderAsyncQuestion();
  f.ctx.finishAsyncQuestionTurn("thread-a", "turn-a");
  f.ctx.renderAsyncQuestion();
  assert.equal(f.ctx.currentAsyncQuestion().item_id, "question-b");
  assert.equal(f.$("asyncQuestionPanel").hidden, false);
  assert.equal(f.answer.value, "Typed answer");
  assert.equal(f.state.activeTurnId, "turn-b");
});

test("authoritative idle state hides a question even before its old turn ID clears", () => {
  const f = fixture();
  f.state.authoritativeThreadActive.set("thread-a", false);
  f.ctx.renderAsyncQuestion();
  assert.equal(f.$("asyncQuestionPanel").hidden, true);
  assert.equal(f.$("asyncQuestionRestoreBtn").hidden, true);
});

test("a stale submit cannot create a new turn or reply into a successor", async () => {
  for (const turn of [null, "turn-b"]) {
    const f = fixture();
    f.state.activeTurnId = turn;
    await f.ctx.submitAsyncQuestion({ preventDefault() {} });
    assert.equal(f.commands.length, 0);
  }
  assert.equal(asyncQuestionReplyMode("turn-a", null), "expired");
  assert.equal(asyncQuestionReplyMode(null, null), "expired");
});

test("a current question answers once using only its originating active turn", async () => {
  const f = fixture();
  await f.ctx.submitAsyncQuestion({ preventDefault() {} });
  await f.ctx.submitAsyncQuestion({ preventDefault() {} });
  assert.equal(f.commands.length, 1);
  assert.equal(f.commands[0].command, "async_question_reply");
  assert.equal(f.commands[0].mode, "steer");
  assert.equal(f.commands[0].turn_id, "turn-a");
  assert.equal(f.commands[0].answers[0], "Typed answer");
  assert.equal(f.$("asyncQuestionPanel").hidden, true);
});
