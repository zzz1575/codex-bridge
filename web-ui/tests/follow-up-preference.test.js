import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import vm from "node:vm";
import { sendWithExplicitNewTurnFallback } from "../src/pending-state.js";

const source = fs.readFileSync(new URL("../src/main.js", import.meta.url), "utf8");
function functionSource(start, end) {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from);
  assert.ok(from >= 0 && to > from);
  return source.slice(from, to);
}

function composer({ mode = "steer", active = false, writable = true, respond } = {}) {
  const elements = { messageText: { value: "Next instruction" }, sendMode: { value: mode } };
  const requests = [],
    actions = [],
    modeChanges = [];
  const state = {
    current: { id: "thread-fixture" },
    composerAttachments: [],
    composerReference: null,
    activeTurnId: active ? "turn-fixture" : null,
    authoritativeThreadActive: new Map([["thread-fixture", active]]),
    pending: [],
    pendingInFlight: new Set(),
    drafts: new Map(),
    attachmentDrafts: new Map(),
    referenceDrafts: new Map(),
    lastMessageIndex: 5,
  };
  const context = {
    state,
    $: (id) => elements[id],
    voiceRecorder: null,
    createdDraftSettings: new Map(),
    sendWithExplicitNewTurnFallback,
    pendingInputSummary: (text) => text,
    newSubmissionId: () => "submission-fixture",
    currentThreadWritable: () => writable,
    renderPending: () => actions.push(state.pending.map((p) => p.action).join(",")),
    setComposerSubmitting() {},
    notify() {},
    tr: (key) => key,
    command: async (request) => {
      requests.push(JSON.parse(JSON.stringify(request)));
      return respond ? respond(request, requests.length) : { status: "accepted" };
    },
    setSendMode: (value) => {
      modeChanges.push(value);
      elements.sendMode.value = value;
    },
    closeCommandPicker() {},
    resizeComposerTextarea() {},
    renderComposerAttachments() {},
    saveDraft: (id, text) => {
      if (text) state.drafts.set(id, text);
      else state.drafts.delete(id);
    },
    setComposerReference() {},
    openThread: async () => {},
    refreshPending: async () => {
      state.pending = [];
    },
  };
  vm.createContext(context);
  vm.runInContext(functionSource("async function write(name)", "let approvalGeneration"), context);
  return {
    context,
    state,
    elements,
    requests,
    actions,
    modeChanges,
    send: () => context.write(mode),
  };
}

test("idle follow-up waits for authoritative no-active-turn before starting work and keeps follow-up selected", async () => {
  const c = composer({
    respond: (_, attempt) => {
      if (attempt === 1) throw new Error("no_active_turn: idle on the server");
      return { status: "accepted" };
    },
  });
  await c.send();
  assert.deepEqual(
    c.requests.map((r) => r.command),
    ["steer", "send"],
  );
  assert.equal(c.requests[0].submission_id, c.requests[1].submission_id);
  assert.equal(c.elements.sendMode.value, "steer");
  assert.equal(c.state.pending[0].action, "queue");
  assert.deepEqual(c.actions, ["steer", "queue", "queue"]);
  assert.equal(c.state.pending[0].status, "accepted");
  assert.deepEqual(c.modeChanges, []);
});

test("a stale idle cache still steers into work already active on the server", async () => {
  const c = composer({ active: false, writable: true });
  await c.send();
  assert.deepEqual(
    c.requests.map((r) => r.command),
    ["steer"],
  );
  assert.equal(c.state.pending[0].action, "steer");
  assert.equal(c.elements.sendMode.value, "steer");
  assert.deepEqual(c.modeChanges, []);
});

test("an active turn receives guidance while explicit queue remains an explicit queue", async () => {
  for (const [mode, expectedCommand, expectedAction] of [
    ["steer", "steer", "steer"],
    ["send", "send", "queue"],
  ]) {
    const c = composer({ mode, active: true });
    await c.send();
    assert.equal(c.requests[0].command, expectedCommand);
    assert.equal(c.state.pending[0].action, expectedAction);
    assert.equal(c.elements.sendMode.value, mode);
  }
});

test("turn completion during submission retries once with the same identity and keeps follow-up selected", async () => {
  const c = composer({
    active: true,
    respond: (_, attempt) => {
      if (attempt === 1) throw new Error("no_active_turn: finished during submission");
      return { status: "accepted" };
    },
  });
  await c.send();
  assert.deepEqual(
    c.requests.map((r) => r.command),
    ["steer", "send"],
  );
  assert.equal(c.requests[0].submission_id, c.requests[1].submission_id);
  assert.equal(c.state.pending[0].action, "queue");
  assert.equal(c.elements.sendMode.value, "steer");
  assert.deepEqual(c.modeChanges, []);
});

test("follow-up never queues through another writer even when cached ownership says owned", async () => {
  for (const writable of [true, false]) {
    const c = composer({
      active: false,
      writable,
      respond: () => {
        throw new Error("thread_writer_external: owned elsewhere");
      },
    });
    await assert.rejects(c.send(), /thread_writer_external/);
    assert.deepEqual(
      c.requests.map((r) => r.command),
      ["steer"],
    );
    assert.equal(c.elements.sendMode.value, "steer");
    assert.deepEqual(c.actions, ["steer"]);
    assert.deepEqual(c.modeChanges, []);
    assert.equal(c.elements.messageText.value, "Next instruction");
  }
});

test("idle status updates preserve both follow-up preference and a manually selected queue", () => {
  for (const mode of ["steer", "send"]) {
    const c = composer({ mode, active: true });
    Object.assign(c.state, { threadRunStates: new Map([["thread-fixture", "active"]]), threadRunTurnIds: new Map() });
    Object.assign(c.context, {
      setThreadRunState: (id, status) => c.state.threadRunStates.set(id, status),
      completedActivityOverlay: () => ({}),
      showActivity() {},
      renderAsyncQuestion() {},
      renderProjects() {},
    });
    vm.runInContext(
      functionSource("function updateThreadLiveFromStatus(", "function resetHorizontalPosition("),
      c.context,
    );
    c.context.updateThreadLiveFromStatus("thread-fixture", { type: "idle" });
    assert.equal(c.elements.sendMode.value, mode);
    assert.deepEqual(c.modeChanges, []);
  }
});

test("polling idle activity does not replace follow-up with queue", async () => {
  const c = composer();
  Object.assign(c.state, {
    threadRunStates: new Map(),
    activityFileLen: 1,
    activityPhase: null,
    activeToolCallId: null,
  });
  Object.assign(c.context, {
    command: async () => ({ activity: { file_len: 1 } }),
    activityOverlayConfirmed: () => false,
    mergeActivityProjection: () => ({
      activeTurnId: null,
      activityPhase: null,
      activeToolCallId: null,
    }),
    effectiveActiveTurnId: () => null,
    showActivity() {},
    renderAsyncQuestion() {},
  });
  vm.runInContext(
    functionSource("async function refreshActivity()", "async function pollActivity()"),
    c.context,
  );
  await c.context.refreshActivity();
  assert.equal(c.elements.sendMode.value, "steer");
  assert.deepEqual(c.modeChanges, []);
});

test("a temporary branch opens separately without replacing the main follow-up preference", async () => {
  const c = composer();
  Object.assign(c.state, {
    temporaryThreads: new Map(),
    activeTemporaryBySource: new Map(),
    temporaryCreating: false,
  });
  const temporaryText = { value: "" },
    panel = { hidden: true };
  c.context.$ = (id) => c.elements[id] || (id === "temporaryText" ? temporaryText : panel);
  Object.assign(c.context, {
    command: async () => ({ thread: { id: "temporary-fixture" } }),
    renderTemporaryMessages() {},
    openTemporaryPanel() {},
  });
  vm.runInContext(
    functionSource("async function createTemporaryThread(", "async function sendTemporaryMessage("),
    c.context,
  );
  assert.equal(await c.context.createTemporaryThread("thread-fixture", "turn-fixture"), true);
  assert.equal(c.state.temporaryThread.id, "temporary-fixture");
  assert.equal(c.elements.messageText.value, "Next instruction");
  assert.equal(c.elements.sendMode.value, "steer");
  assert.deepEqual(c.modeChanges, []);
});
