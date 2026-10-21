import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { taskProgressNode, taskProgressPresentation } from "../src/task-progress.js";

const notice = "Read the ledger and summarize these changes. This is an internal notification, not a new task or authorization to write business data. Do not register this notification as a task.";
const payload = { changes: [{ change: "done", task_id: "T0001" }, { change: "awaiting_delivery:execution_finished_not_delivery_or_business_proof", task_id: "T0002" }], event_id: "00000000-0000-5000-8000-000000000001" };
const envelope = (value = payload) => `Y2YY_INTERNAL_SUMMARY ${JSON.stringify(value)}\n${notice}`;
const message = (text = envelope(), extra = {}) => ({ id: "fixture-progress", message_index: 1, turn_id: "fixture-turn", role: "user", category: "user", content: [{ kind: "text", text }], ...extra });
class Element {
  constructor(tag) { this.tagName = tag.toUpperCase(); this.children = []; this.dataset = {}; this.attributes = {}; this._text = ""; this.className = ""; this.open = false; }
  set innerHTML(_) { throw new Error("Progress must render as text, never HTML"); }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map((child) => child.textContent).join(""); }
  get childNodes() { return this.children; }
  append(...nodes) { this.children.push(...nodes); }
  appendChild(node) { this.children.push(node); return node; }
  setAttribute(key, value) { this.attributes[key] = String(value); }
  querySelector(selector) {
    if (selector === ":scope > summary") return this.children.find((node) => node.tagName === "SUMMARY");
    if (selector.startsWith(".")) return walk(this).find((node) => node.className === selector.slice(1));
    throw new Error(`Unexpected selector: ${selector}`);
  }
}
const walk = (root) => [root, ...root.children.flatMap(walk)];
const visible = (root) => root._text + (root.tagName === "DETAILS" && !root.open ? root.children.slice(0, 1) : root.children).map(visible).join("");
const documentApi = { createElement: (tag) => new Element(tag) };

test("valid historical notifications translate statuses without altering source history", () => {
  const raw = envelope();
  const result = taskProgressPresentation(raw, "user");
  assert.deepEqual(result.updates, [
    { taskId: "T0001", status: "任务已完成", detail: "" },
    { taskId: "T0002", status: "等待交付验证", detail: "执行已结束，尚未完成交付或业务验证" },
  ]);
  assert.equal(result.raw, raw);
  assert.equal(taskProgressPresentation(` \n${raw.replaceAll("\n", "\r\n")}\n `, "user").updates.length, 2);
});

test("ordinary questions, assistant summaries, quoted examples and malformed envelopes remain messages", () => {
  assert.equal(taskProgressPresentation(envelope(), "assistant"), null);
  for (const raw of ["普通消息", `解释一下\n${envelope()}`, `${envelope()}\n补充要求`, `\`\`\`\n${envelope()}\n\`\`\``, envelope().slice(0, -4), `Y2YY_INTERNAL_SUMMARY {bad}\n${notice}`]) assert.equal(taskProgressPresentation(raw, "user"), null);
  for (const value of [{ ...payload, event_id: [payload.event_id] }, { ...payload, event_id: "invalid" }, { ...payload, changes: [] }, { ...payload, extra: "preserve me" }, { ...payload, changes: [{ change: "done", task_id: "T0001", extra: "preserve me" }] }, { ...payload, changes: [null] }]) assert.equal(taskProgressPresentation(envelope(value), "user"), null);
});

test("unknown future statuses stay readable without claiming completion", () => {
  const result = taskProgressPresentation(envelope({ ...payload, changes: [{ task_id: "T0099", change: "future_state:future_reason" }] }), "user");
  assert.equal(result.updates[0].status, "任务状态已更新，待确认");
  assert.equal(result.updates[0].detail, "详细情况请查看统筹汇总");
});

test("card defaults closed and raw protocol stays behind a second explicit disclosure", () => {
  const card = taskProgressNode(taskProgressPresentation(envelope(), "user"), documentApi);
  assert.equal(visible(card), "任务进度 · 2 项更新");
  card.open = true;
  assert.match(visible(card), /T0002 · 等待交付验证/);
  assert.doesNotMatch(visible(card), /Y2YY_INTERNAL_SUMMARY|event_id|Read the ledger/);
  const raw = card.querySelector(".task-progress-raw");
  raw.open = true;
  assert.match(visible(card), /Y2YY_INTERNAL_SUMMARY/);
  assert.ok(walk(card).every((node) => !["SCRIPT", "IMG", "A"].includes(node.tagName)));
});

async function renderer() {
  const source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
  const begin = source.indexOf("function messageNode("), end = source.indexOf("\nfunction syncLongMessage(", begin);
  const state = { current: { id: "fixture-thread" }, expandedLongMessageIds: new Set(), editableInterruptedTurn: null };
  const renderedMessageState = new WeakMap(), copies = [];
  const render = runInNewContext(`${source.slice(begin, end)}\nmessageNode`, {
    document: documentApi, state, renderedMessageState, taskProgressPresentation,
    taskProgressNode: (p) => taskProgressNode(p, documentApi),
    messageIdentity: (m) => m.id, tr: (key) => key,
    preserveMessageElementPosition: (_, change) => change(),
    questionReplyPresentation: () => null,
    contentNode: (item) => { const n = new Element("p"); n.textContent = item.text || item.kind; return n; },
    messageCopyButton: (text) => { copies.push(text); return new Element("button"); },
    toolGroupNode: () => null,
  });
  return { render, state, renderedMessageState, copies };
}

test("actual timeline renders cards, preserves disclosure across refresh and retains coordinator summaries", async () => {
  const f = await renderer(), original = message(), before = JSON.stringify(original);
  const first = f.render(original), card = first.children[0].children[0];
  assert.equal(first.className, "message task-progress-message");
  assert.equal(first.dataset.messageKey, original.id);
  assert.equal(visible(card), "任务进度 · 2 项更新");
  assert.deepEqual(f.copies, []);
  const click = { preventDefault() {} };
  card.querySelector(":scope > summary").onclick(click);
  assert.equal(card.open, true);
  const refreshed = f.render(structuredClone(original)), refreshedCard = refreshed.children[0].children[0];
  assert.equal(refreshedCard.open, true);
  assert.equal(refreshedCard.querySelector(".task-progress-raw").open, false);
  f.state.expandedLongMessageIds.clear();
  assert.equal(f.render(original).children[0].children[0].open, false);
  assert.equal(JSON.stringify(original), before);
  assert.equal(f.renderedMessageState.get(first).signature, before);
  const summary = f.render(message("统筹汇总：任务排查已完成，恢复操作仍待验证。", { role: "assistant", category: "assistant" }));
  assert.match(summary.textContent, /统筹汇总：任务排查已完成/);
});

test("mixed content never silently loses attachments or additional user instructions", async () => {
  const f = await renderer();
  const node = f.render(message(envelope(), { content: [{ kind: "text", text: envelope() }, { kind: "image" }] }));
  assert.notEqual(node.className, "message task-progress-message");
  assert.match(node.textContent, /image/);
});
