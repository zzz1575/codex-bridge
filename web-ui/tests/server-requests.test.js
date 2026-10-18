import test from "node:test";
import assert from "node:assert/strict";
import {
  createServerRequestTray,
  safePromptUrl,
  supportedPromptSchema,
} from "../src/server-requests.js";

class Element {
  constructor(tag) {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attributes = {};
    this.dataset = {};
    this.value = "";
    this.hidden = false;
  }
  set innerHTML(_) {
    throw new Error("Untrusted content must never become HTML");
  }
  append(...nodes) {
    for (const node of nodes) {
      node.remove?.();
      this.children.push(node);
      node.parentNode = this;
    }
  }
  insertBefore(node, next) {
    node.remove();
    const i = next ? this.children.indexOf(next) : -1;
    this.children.splice(i < 0 ? this.children.length : i, 0, node);
    node.parentNode = this;
  }
  before(node) {
    this.parentNode.insertBefore(node, this);
  }
  remove() {
    if (this.parentNode) {
      const p = this.parentNode;
      p.children.splice(p.children.indexOf(this), 1);
      this.parentNode = null;
    }
  }
  setAttribute(key, value) {
    this.attributes[key] = value;
  }
  reportValidity() {
    return true;
  }
}
const descendants = (node) => [node, ...node.children.flatMap(descendants)];
const find = (root, predicate) => descendants(root).find(predicate);
const event = () => ({ preventDefault() {} });
const plain = (value) => JSON.parse(JSON.stringify(value));
function fixture(entry, respond = async () => {}) {
  const root = new Element("main"),
    composer = new Element("section");
  root.append(composer);
  let entries = Array.isArray(entry) ? entry : [entry],
    thread = "thread-a";
  const calls = [];
  const controller = createServerRequestTray({
    document: { createElement: (tag) => new Element(tag) },
    getContainer: () => composer,
    getThreadId: () => thread,
    command: async (request) => {
      calls.push(request);
      if (request.command === "server_requests") return { requests: structuredClone(entries) };
      await respond(request);
      entries = entries.filter((entry) => entry.id !== request.id);
      return {};
    },
  });
  return {
    root,
    calls,
    controller,
    setThread: (value) => {
      thread = value;
    },
    tray: () => find(root, (n) => n.id === "serverRequestTray"),
  };
}
const question = {
  id: 1,
  thread_id: "thread-a",
  request: {
    id: "original-opaque",
    method: "item/tool/requestUserInput",
    params: {
      questions: [
        {
          id: "choice",
          header: "方案",
          question: "<img onerror=alert(1)>",
          options: [
            { label: "A", description: "第一种方案" },
            { label: "B", description: "第二种方案" },
          ],
        },
        { id: "secret", header: "私密回答", question: "私密值", isSecret: true },
      ],
    },
  },
};

test("blocking questions require explicit input, retain drafts on polling, and reply once", async () => {
  let release;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  const f = fixture(question, () => gate);
  await f.controller.refresh();
  const card = f.tray().children[0],
    form = find(card, (n) => n.tagName === "FORM");
  const select = find(form, (n) => n.tagName === "SELECT");
  const secret = find(form, (n) => n.type === "password");
  assert.equal(find(card, (n) => n.textContent === "<img onerror=alert(1)>").tagName, "P");
  assert.equal(select.value, "", "No answer is selected automatically");
  assert.equal(f.calls.filter((r) => r.command === "server_request_respond").length, 0);
  select.value = "B";
  secret.value = "synthetic-private-answer";
  await f.controller.refresh();
  assert.equal(f.tray().children[0], card);
  assert.equal(secret.value, "synthetic-private-answer");
  const first = form.onsubmit(event());
  await form.onsubmit(event());
  const replies = f.calls.filter((r) => r.command === "server_request_respond");
  assert.equal(replies.length, 1);
  assert.deepEqual(plain(replies[0].response), {
    answers: { choice: { answers: ["B"] }, secret: { answers: ["synthetic-private-answer"] } },
  });
  assert.equal(replies[0].thread_id, "thread-a");
  release();
  await first;
  assert.equal(f.tray().hidden, true);
});

test("validation failure retains inputs for retry; skip is a distinct explicit response", async () => {
  const f = fixture(question);
  await f.controller.refresh();
  const form = find(f.tray(), (n) => n.tagName === "FORM");
  await form.onsubmit(event());
  assert.equal(f.calls.filter((r) => r.command === "server_request_respond").length, 0);
  const skip = find(form, (n) => n.textContent === "不回答");
  await skip.onclick();
  assert.deepEqual(plain(f.calls.find((r) => r.command === "server_request_respond").response), {
    answers: {},
  });
});

test("MCP forms preserve typed booleans, numeric values and multi-select enum choices", async () => {
  const f = fixture({
    id: 2,
    thread_id: "thread-a",
    request: {
      method: "mcpServer/elicitation/request",
      params: {
        mode: "form",
        serverName: "fixture-server",
        message: "确认输入",
        requestedSchema: {
          type: "object",
          properties: {
            amount: { type: "integer", minimum: 1 },
            allow: { type: "boolean" },
            plans: {
              type: "array",
              items: {
                anyOf: [
                  { const: "first", title: "第一项" },
                  { const: "second", title: "第二项" },
                ],
              },
            },
          },
          required: ["amount", "allow", "plans"],
        },
      },
    },
  });
  await f.controller.refresh();
  const form = find(f.tray(), (n) => n.tagName === "FORM");
  find(form, (n) => n.attributes["aria-label"] === "amount").value = "3";
  find(form, (n) => n.attributes["aria-label"] === "allow").value = "false";
  find(form, (n) => n.type === "checkbox" && n.value === "second").checked = true;
  await form.onsubmit(event());
  assert.deepEqual(plain(f.calls.find((r) => r.command === "server_request_respond").response), {
    action: "accept",
    content: { amount: 3, allow: false, plans: ["second"] },
  });
});

test("unsupported schema, unsafe URL and device proof have cancellation but no accept control", async () => {
  for (const params of [
    { mode: "openai/form", requestedSchema: { $ref: "https://example.com/schema" } },
    { mode: "url", url: "javascript:alert(1)" },
    { mode: "openai/userVerification", challenge: "synthetic-challenge" },
  ]) {
    const f = fixture({
      id: 3,
      thread_id: "thread-a",
      request: { method: "mcpServer/elicitation/request", params },
    });
    await f.controller.refresh();
    const form = find(f.tray(), (n) => n.tagName === "FORM");
    assert.equal(
      find(form, (n) => n.type === "submit"),
      undefined,
    );
    assert.equal(
      find(f.tray(), (n) => n.tagName === "A"),
      undefined,
    );
    assert.equal(f.calls.filter((r) => r.command === "server_request_respond").length, 0);
    await find(form, (n) => n.textContent === "取消请求").onclick();
    assert.deepEqual(plain(f.calls.find((r) => r.command === "server_request_respond").response), {
      action: "cancel",
    });
  }
});

test("switching threads blocks a stale form, and disconnect invalidates an in-flight snapshot", async () => {
  const f = fixture(question);
  await f.controller.refresh();
  const form = find(f.tray(), (n) => n.tagName === "FORM");
  f.setThread("thread-b");
  await find(form, (n) => n.textContent === "不回答").onclick();
  assert.equal(f.calls.filter((r) => r.command === "server_request_respond").length, 0);
  f.controller.render();
  assert.equal(f.tray().hidden, true);
  let release;
  const root = new Element("main"),
    composer = new Element("section");
  root.append(composer);
  const controller = createServerRequestTray({
    document: { createElement: (tag) => new Element(tag) },
    getContainer: () => composer,
    getThreadId: () => "thread-a",
    command: () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  });
  const pending = controller.refresh();
  controller.reset();
  release({ requests: [question] });
  await pending;
  assert.equal(
    find(root, (n) => n.id === "serverRequestTray"),
    undefined,
  );
});

test("safe URL and schema checks never resolve references or accept unsupported constraints", () => {
  assert.equal(safePromptUrl("https://example.com/verify"), "https://example.com/verify");
  assert.equal(safePromptUrl("data:text/html,test"), null);
  assert.equal(
    supportedPromptSchema({ type: "object", properties: { a: { type: "string", minLength: 1 } } }),
    true,
  );
  assert.equal(
    supportedPromptSchema({ type: "object", properties: { a: { $ref: "file:///unavailable" } } }),
    false,
  );
  assert.equal(supportedPromptSchema({ type: "string", pattern: ".*" }), false);
});
