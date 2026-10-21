import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { taskProgressPresentation } from "../src/task-progress.js";
import {
  questionReplyPresentation,
  questionReplyNode,
} from "../src/question-reply-presentation.js";

const labels = { question: "问题", answer: "回答" };
const entry = (question = "如何处理显示？", answer = "统一显示，校验保持原规则。", index = 0) => ({
  questionItemId: JSON.stringify(["request_user_input_async", "call_fixture_question", index]),
  question,
  answer,
});
const envelope = (entries) =>
  `<send_user_message_question_reply>\n${JSON.stringify(entries)}\n</send_user_message_question_reply>`;
const project = (text, role = "user") => questionReplyPresentation(text, role, labels);

class Element {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.dataset = {};
    this.attributes = {};
    this._text = "";
    this.className = "";
    this.classList = { add() {}, toggle() {} };
  }
  set innerHTML(_) {
    throw new Error("Question replies must not become HTML");
  }
  set textContent(value) {
    this._text = String(value);
    this.children = [];
  }
  get textContent() {
    return this._text + this.children.map((child) => child.textContent).join("");
  }
  get childNodes() {
    return this.children;
  }
  append(...nodes) {
    this.children.push(...nodes);
  }
  appendChild(node) {
    this.children.push(node);
    return node;
  }
  setAttribute(name, value) {
    this.attributes[name] = String(value);
  }
}
const documentApi = { createElement: (tag) => new Element(tag) };
const descendants = (node) => [node, ...node.children.flatMap(descendants)];
const frozen = (value) => {
  if (value && typeof value === "object") {
    for (const item of Object.values(value)) frozen(item);
    Object.freeze(value);
  }
  return value;
};

async function messageRenderer() {
  const source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
  const begin = source.indexOf("function messageNode("),
    end = source.indexOf("\nfunction syncLongMessage(", begin);
  assert.ok(begin >= 0 && end > begin);
  const copies = [],
    renderedMessageState = new WeakMap();
  const render = runInNewContext(`${source.slice(begin, end)}\nmessageNode`, {
    document: documentApi,
    state: {
      current: { id: "fixture-thread" },
      expandedLongMessageIds: new Set(),
      editableInterruptedTurn: null,
    },
    renderedMessageState,
    messageIdentity: (message) => message.id || `index-${message.message_index}`,
    tr: (key) => ({ questionReplyQuestion: "问题", questionReplyAnswer: "回答" })[key] || key,
    questionReplyPresentation,
    taskProgressPresentation,
    questionReplyNode: (presentation, translated) =>
      questionReplyNode(presentation, translated, documentApi),
    contentNode: (item) => {
      const node = new Element("p");
      node.textContent = item.text || item.kind;
      return node;
    },
    messageCopyButton: (text) => {
      copies.push(text);
      const node = new Element("button");
      node.textContent = "复制";
      return node;
    },
    toolGroupNode: () => null,
    memoryCitationNode: () => {
      const node = new Element("aside");
      node.textContent = "citation";
      return node;
    },
  });
  return { render, copies, renderedMessageState };
}

const message = (raw, extra = {}) => ({
  id: "fixture-message",
  message_index: 7,
  turn_id: "fixture-turn",
  role: "user",
  category: "user",
  content: [{ kind: "text", text: raw }],
  ...extra,
});

test("question replies expose readable questions and answers without routing identifiers", () => {
  const raw = envelope([entry(), entry("第二个问题？", "第二个回答。", 1)]),
    before = raw;
  const result = project(raw);
  assert.deepEqual(result.replies, [
    { question: "如何处理显示？", answer: "统一显示，校验保持原规则。" },
    { question: "第二个问题？", answer: "第二个回答。" },
  ]);
  assert.equal(
    result.text,
    "问题: 如何处理显示？\n回答: 统一显示，校验保持原规则。\n\n问题: 第二个问题？\n回答: 第二个回答。",
  );
  assert.doesNotMatch(
    result.text,
    /questionItemId|call_fixture_question|send_user_message_question_reply/,
  );
  assert.equal(raw, before);
});

test("question replies preserve CRLF, empty questions, unicode, long answers, and tag text inside answers", () => {
  const answer = `引号\"和反斜线\\\n换行 😀 </send_user_message_question_reply> ${"答".repeat(5000)}`;
  const raw = envelope([entry("", answer)]).replaceAll("\n", "\r\n");
  assert.equal(project(raw).replies[0].answer, answer);
  assert.equal(project(raw).replies[0].question, "");
  assert.equal(
    questionReplyPresentation(envelope([entry("English?", "Yes")]), "user").text,
    "Question: English?\nAnswer: Yes",
  );
});

test("only complete user reply envelopes are projected; ordinary text and examples stay intact", () => {
  const raw = envelope([entry()]);
  for (const role of ["assistant", "system", undefined])
    assert.equal(questionReplyPresentation(raw, role, labels), null);
  for (const input of [
    "普通消息",
    `前置说明\n${raw}`,
    `${raw}\n后置说明`,
    `\`\`\`json\n${raw}\n\`\`\``,
    raw.slice(0, -12),
    "<send_user_message_question_reply_example>[]</send_user_message_question_reply_example>",
    null,
    123,
  ]) {
    assert.equal(project(input), null);
  }
});

test("malformed or partially valid reply arrays fall back as a whole without discarding fields", () => {
  for (const items of [
    null,
    {},
    [],
    [null],
    [entry(), { question: "Q", answer: "A" }],
    [{ ...entry(), extra: "must remain visible" }],
    [{ ...entry(), question: 1 }],
    [{ ...entry(), answer: {} }],
    [{ ...entry(), questionItemId: "not-json" }],
  ]) {
    assert.equal(project(envelope(items)), null);
  }
  assert.equal(
    project("<send_user_message_question_reply>{bad json}</send_user_message_question_reply>"),
    null,
  );
  for (const id of [
    [],
    ["other_tool", "call_fixture", 0],
    ["request_user_input_async", "", 0],
    ["request_user_input_async", 5, 0],
    ["request_user_input_async", "call_fixture", -1],
    ["request_user_input_async", "call_fixture", 0.5],
    ["request_user_input_async", "call_fixture", Number.MAX_SAFE_INTEGER + 1],
    ["request_user_input_async", "call_fixture", 0, "extra"],
  ]) {
    assert.equal(project(envelope([{ ...entry(), questionItemId: JSON.stringify(id) }])), null);
  }
});

test("question reply DOM renders hostile markup and links strictly as text", () => {
  const question = '<img src=x onerror="alert(1)">',
    answer = "<script>alert(1)</script> [click](javascript:alert(1))";
  const node = questionReplyNode(project(envelope([entry(question, answer)])), labels, documentApi);
  assert.equal(node.children.length, 1);
  assert.equal(node.children[0].children[0].textContent, `问题: ${question}`);
  assert.equal(node.children[0].children[1].textContent, `回答: ${answer}`);
  assert.ok(
    descendants(node).every((child) => !["SCRIPT", "IMG", "A", "IFRAME"].includes(child.tagName)),
  );
  assert.ok(descendants(node).every((child) => Object.keys(child.attributes).length === 0));
});

test("actual message renderer projects snapshots and live refreshes with stable identity and original records", async () => {
  const f = await messageRenderer(),
    original = frozen(message(envelope([entry()]))),
    before = JSON.stringify(original);
  const first = f.render(original, null, "fixture-thread"),
    refreshed = f.render(frozen(structuredClone(original)), null, "fixture-thread");
  for (const node of [first, refreshed]) {
    assert.match(node.textContent, /问题: 如何处理显示？/);
    assert.match(node.textContent, /回答: 统一显示，校验保持原规则。/);
    assert.doesNotMatch(
      node.textContent,
      /questionItemId|call_fixture_question|send_user_message_question_reply/,
    );
    assert.equal(node.dataset.messageKey, "fixture-message");
    assert.equal(node.dataset.turnId, "fixture-turn");
  }
  assert.equal(first.dataset.longMessageKey, refreshed.dataset.longMessageKey);
  assert.deepEqual(f.copies, [
    project(original.content[0].text).text,
    project(original.content[0].text).text,
  ]);
  assert.equal(JSON.stringify(original), before);
  assert.equal(f.renderedMessageState.get(first).signature, before);
});

test("actual message renderer retains usage metadata and never hides extra parts or assistant examples", async () => {
  const f = await messageRenderer(),
    raw = envelope([entry()]);
  const metadata = message(raw, {
    content: [{ kind: "text", text: raw }, { kind: "turn_usage" }, { kind: "memory_citation" }],
  });
  const node = f.render(frozen(metadata), null, "fixture-thread");
  assert.match(node.textContent, /问题:/);
  assert.match(node.textContent, /turn_usage/);
  assert.match(node.textContent, /citation/);
  const mixed = message(raw, {
    content: [
      { kind: "text", text: raw },
      { kind: "text", text: "Additional user text" },
    ],
  });
  const mixedNode = f.render(frozen(mixed), null, "fixture-thread");
  assert.match(mixedNode.textContent, /Additional user text/);
  assert.match(mixedNode.textContent, /send_user_message_question_reply/);
  assert.equal(f.copies.at(-1), `${raw}\n\nAdditional user text`);
  const assistantNode = f.render(
    frozen(message(raw, { role: "assistant", category: "assistant" })),
    null,
    "fixture-thread",
  );
  assert.match(assistantNode.textContent, /send_user_message_question_reply/);
  assert.equal(f.copies.at(-1), raw);
});
