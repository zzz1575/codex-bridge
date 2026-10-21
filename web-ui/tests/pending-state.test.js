import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { composerSelectionIsCurrent } from "../src/service-tier.js";
import {
  isWithdrawableQueued,
  mergePendingSnapshot,
  pendingInputSummary,
  pendingPresentation,
  pendingSnapshotIsCurrent,
  reconcilePendingMessages,
  sendWithExplicitNewTurnFallback,
} from "../src/pending-state.js";

const mainScript = new URL("../src/main.js", import.meta.url);
const queued = (extra = {}) => ({
  id: "input-a",
  thread_id: "thread-a",
  action: "queue",
  text: "review the fixture",
  status: "queued",
  source: "app_server_queue",
  after_message_index: 4,
  ...extra,
});

test("accepted queue receipts say submitted, and only a real queued input can be withdrawn", () => {
  assert.deepEqual(pendingPresentation(queued()), {
    modeKey: "pendingQueued",
    statusKey: "pendingQueuedReady",
    withdrawable: true,
  });
  const accepted = queued({ status: "accepted" });
  assert.equal(pendingPresentation(accepted).modeKey, "pendingSubmitted");
  assert.equal(pendingPresentation(accepted).statusKey, "pendingRecordSync");
  assert.equal(isWithdrawableQueued(accepted), false);
  assert.equal(isWithdrawableQueued(queued({ status: "processing" })), false);
  assert.equal(isWithdrawableQueued(queued({ status: "failed" })), false);
  assert.equal(isWithdrawableQueued(queued({ source: "codex_queue" })), false);
  assert.equal(
    pendingPresentation(queued({ source: "codex_queue" })).modeKey,
    "pendingUnconfirmed",
  );
  assert.equal(isWithdrawableQueued(queued({ queue_unverified: true })), false);
  assert.equal(
    pendingPresentation(queued({ action: "steer", status: "accepted" })).modeKey,
    "followUp",
  );
});

test("unknown native reads retain the queue without pretending it was handed off or cancellable", () => {
  const accepted = queued({ id: "receipt-a", status: "accepted" });
  const current = [queued(), accepted];
  const unknown = mergePendingSnapshot([], current, new Set(), {
    threadId: "thread-a",
    queueAuthoritative: false,
  });
  assert.deepEqual(
    unknown.map((entry) => entry.id),
    ["input-a", "receipt-a"],
  );
  assert.equal(unknown[0].status, "queued");
  assert.equal(unknown[0].queue_unverified, true);
  assert.equal(isWithdrawableQueued(unknown[0]), false);
  assert.equal(pendingPresentation(unknown[0]).statusKey, "pendingQueueUnavailable");
  const compatibility = mergePendingSnapshot([queued({ status: "accepted" })], current, new Set(), {
    threadId: "thread-a",
    queueAuthoritative: false,
  });
  assert.equal(compatibility[0].status, "queued");
  assert.deepEqual(
    mergePendingSnapshot([], unknown, new Set(), {
      threadId: "thread-a",
      queueAuthoritative: true,
    }),
    [],
  );
});

test("authoritative empty reads remove old receipts but keep a real in-flight submission", () => {
  const inflight = queued({
    id: "inflight-a",
    source: "web_optimistic",
    status: "steering",
    action: "steer",
  });
  const result = mergePendingSnapshot(
    [],
    [queued({ status: "accepted" }), inflight],
    new Set([inflight.id]),
    {
      threadId: "thread-a",
    },
  );
  assert.deepEqual(result, [inflight]);
  assert.deepEqual(
    mergePendingSnapshot([queued({ thread_id: "thread-b" })], [], new Set(), {
      threadId: "thread-a",
    }),
    [],
  );
});

test("durable echoes reconcile only their own thread, position and attachment fingerprint", () => {
  const own = queued();
  const other = queued({ id: "input-b", thread_id: "thread-b" });
  const echo = [{ role: "user", message_index: 5, content: [{ kind: "text", text: own.text }] }];
  assert.deepEqual(reconcilePendingMessages([other, own], echo, "thread-a"), [other]);
  assert.deepEqual(
    reconcilePendingMessages([own], [{ ...echo[0], message_index: 4 }], "thread-a"),
    [own],
  );
  const image = queued({ text: "look here\n[Image attachment]" });
  assert.deepEqual(
    reconcilePendingMessages(
      [image],
      [
        {
          role: "user",
          message_index: 5,
          content: [
            { kind: "text", text: "look here" },
            { kind: "context", label: "Image attachment" },
          ],
        },
      ],
      "thread-a",
    ),
    [],
  );
  assert.deepEqual(
    reconcilePendingMessages(
      [image],
      [
        {
          role: "user",
          message_index: 5,
          content: [
            { kind: "text", text: "look here" },
            { kind: "image", url: "https://example.com/fixture.png" },
          ],
        },
      ],
      "thread-a",
    ),
    [],
  );
});

test("late polls cannot cross a thread switch, an A-B-A view cycle or a newer applied generation", () => {
  const snapshot = { threadId: "thread-a", openToken: 4, generation: 8 };
  assert.equal(
    pendingSnapshotIsCurrent(snapshot, {
      threadId: "thread-a",
      openToken: 4,
      appliedGeneration: 7,
    }),
    true,
  );
  assert.equal(
    pendingSnapshotIsCurrent(snapshot, {
      threadId: "thread-b",
      openToken: 5,
      appliedGeneration: 7,
    }),
    false,
  );
  assert.equal(
    pendingSnapshotIsCurrent(snapshot, {
      threadId: "thread-a",
      openToken: 6,
      appliedGeneration: 7,
    }),
    false,
  );
  assert.equal(
    pendingSnapshotIsCurrent(snapshot, {
      threadId: "thread-a",
      openToken: 4,
      appliedGeneration: 9,
    }),
    false,
  );
});

test("the real pending response application rejects old views and filters foreign thread payloads", async () => {
  const source = await readFile(mainScript, "utf8");
  const application = source.slice(
    source.indexOf("function applyPendingSnapshot("),
    source.indexOf("async function refreshPending("),
  );
  const state = {
    current: { id: "thread-a" },
    openToken: 6,
    pending: [queued()],
    pendingInFlight: new Set(),
  };
  let renders = 0;
  const context = {
    state,
    pendingSnapshotIsCurrent,
    renderPending: () => renders++,
    mergePendingResponse: (remote, _preserve, _messages, authoritative) =>
      mergePendingSnapshot(remote, state.pending, state.pendingInFlight, {
        threadId: state.current.id,
        queueAuthoritative: authoritative,
      }),
  };
  const app = runInNewContext(
    `let pendingAppliedGeneration = 0;
    ${application}
    ({ apply: applyPendingSnapshot, applied: () => pendingAppliedGeneration })`,
    context,
  );
  assert.equal(
    app.apply({ messages: [] }, { threadId: "thread-a", openToken: 4, generation: 1 }),
    false,
  );
  assert.equal(state.pending.length, 1);
  assert.equal(renders, 0);
  assert.equal(
    app.apply(
      { messages: [queued({ thread_id: "thread-b" })] },
      { threadId: "thread-a", openToken: 6, generation: 2 },
    ),
    true,
  );
  assert.deepEqual(state.pending, []);
  state.pending = [queued()];
  assert.equal(
    app.apply(
      { messages: [], queue_available: false, queue_authoritative: false },
      { threadId: "thread-a", openToken: 6, generation: 3 },
    ),
    true,
  );
  assert.equal(state.pending[0].queue_unverified, true);
  assert.equal(state.pendingQueueUnavailable, "thread-a");
  assert.equal(
    app.apply({ messages: [] }, { threadId: "thread-a", openToken: 6, generation: 4 }),
    true,
  );
  assert.deepEqual(state.pending, []);
  assert.equal(state.pendingQueueUnavailable, null);
  assert.equal(
    app.apply({ messages: [queued()] }, { threadId: "thread-a", openToken: 6, generation: 3 }),
    false,
  );
});

function pendingDom() {
  const created = [];
  const document = {
    createElement(tag) {
      const node = {
        tag,
        children: [],
        dataset: {},
        append(...nodes) {
          this.children.push(...nodes);
        },
        appendChild(node) {
          this.children.push(node);
        },
        prepend(node) {
          this.children.unshift(node);
        },
        setAttribute() {},
      };
      created.push(node);
      return node;
    },
  };
  return { document, created };
}

test("actual pending cards label accepted receipts as submitted and never render a fake withdraw", async () => {
  const source = await readFile(mainScript, "utf8");
  const renderer = source.slice(
    source.indexOf("function pendingNode(entry) {"),
    source.indexOf("function renderPending() {"),
  );
  for (const entry of [
    queued({ status: "accepted" }),
    queued({ queue_unverified: true }),
    queued(),
  ]) {
    const { document, created } = pendingDom();
    runInNewContext(`${renderer}\npendingNode(entry)`, {
      document,
      entry,
      state: { pending: [entry] },
      pendingPresentation,
      isWithdrawableQueued,
      markdownNode: () => document.createElement("span"),
      markdownOptions: () => ({}),
      tr: (key) => key,
      run: () => {},
    });
    const labels = created.map((node) => node.textContent);
    assert.equal(labels.includes("withdraw"), isWithdrawableQueued(entry));
    if (entry.status === "accepted") {
      assert.ok(labels.includes("pendingSubmitted"));
      assert.ok(labels.includes("pendingRecordSync"));
      assert.ok(!labels.includes("queue"));
      assert.ok(!labels.includes("sentCannotWithdraw"));
    }
  }
});

test("only an explicit no_active_turn permits exactly one send with the same submission and attachments", async () => {
  const request = {
    command: "steer",
    thread_id: "thread-a",
    submission_id: "submission-a",
    text: "review fixture",
    attachments: [{ id: "image-a", type: "image" }],
  };
  const calls = [];
  let fallback = 0;
  const result = await sendWithExplicitNewTurnFallback(
    request,
    async (body) => {
      calls.push(body);
      if (body.command === "steer") throw new Error("no_active_turn: no active turn");
      return { status: "started" };
    },
    () => fallback++,
  );
  assert.equal(result.command, "send");
  assert.deepEqual(
    calls.map((body) => body.command),
    ["steer", "send"],
  );
  assert.equal(calls[0].submission_id, calls[1].submission_id);
  assert.deepEqual(calls[0].attachments, calls[1].attachments);
  assert.equal(request.command, "steer");
  assert.equal(fallback, 1);
  for (const error of [
    new Error("request timed out"),
    new Error("upstream_error: no_active_turn"),
    Object.assign(new Error("no_active_turn: partial"), { partial: true }),
  ]) {
    const attempted = [];
    await assert.rejects(
      sendWithExplicitNewTurnFallback(request, async (body) => {
        attempted.push(body);
        throw error;
      }),
    );
    assert.equal(attempted.length, 1);
  }
});

function composerContext(reply) {
  const text = { value: "review the fixture" },
    calls = [],
    notices = [];
  const state = {
    current: { id: "thread-a" },
    openToken: 1,
    composerAttachments: [],
    composerReference: null,
    pending: [],
    pendingInFlight: new Set(),
    lastMessageIndex: 4,
    drafts: new Map(),
    attachmentDrafts: new Map(),
    referenceDrafts: new Map(),
  };
  return {
    state,
    text,
    calls,
    notices,
    context: {
      state,
      createdDraftSettings: new Map(),
      composerSelectionIsCurrent,
      $: () => text,
      voiceRecorder: null,
      newSubmissionId: () => "submission-a",
      pendingInputSummary,
      sendWithExplicitNewTurnFallback,
      renderPending() {},
      setComposerSubmitting() {},
      command: async (request) => {
        calls.push(request);
        return reply(request);
      },
      closeCommandPicker() {},
      resizeComposerTextarea() {},
      saveDraft: (id, value) => (value ? state.drafts.set(id, value) : state.drafts.delete(id)),
      refreshPending: async () => {},
      renderComposerAttachments() {},
      setComposerReference() {},
      openThread: async () => {},
      notify: (value, bad) => notices.push({ value, bad }),
      tr: (key) => key,
    },
  };
}

test("the actual composer treats partial queue acceptance as accepted, warns and never reposts", async () => {
  const source = await readFile(mainScript, "utf8");
  const writer = source.slice(
    source.indexOf("async function write(name) {"),
    source.indexOf("function approval(name) {"),
  );
  const fixture = composerContext(() => ({
    status: "queued",
    backend: { queued_submission_id: "submission-a" },
    partial: true,
    start_error: { code: "thread_state_unavailable", message: "unavailable" },
  }));
  await runInNewContext(`${writer}\nwrite("send")`, fixture.context);
  assert.equal(fixture.calls.length, 1);
  assert.equal(fixture.calls[0].submission_id, "submission-a");
  assert.equal(fixture.state.pending[0].status, "queued");
  assert.equal(fixture.state.pending[0].start_error.code, "thread_state_unavailable");
  assert.equal(fixture.state.pendingInFlight.size, 0);
  assert.equal(fixture.text.value, "");
  assert.ok(
    fixture.notices.some((notice) => notice.value === "pendingStartFailed" && notice.bad === true),
  );
});

test("the actual composer calls idle fallback a new submission rather than another queued request", async () => {
  const source = await readFile(mainScript, "utf8");
  const writer = source.slice(
    source.indexOf("async function write(name) {"),
    source.indexOf("function approval(name) {"),
  );
  const fixture = composerContext((request) => {
    if (request.command === "steer") throw new Error("no_active_turn: idle");
    return { status: "started" };
  });
  await runInNewContext(`${writer}\nwrite("steer")`, fixture.context);
  assert.deepEqual(
    fixture.calls.map((request) => request.command),
    ["steer", "send"],
  );
  assert.equal(fixture.state.pending[0].intent, "new_turn");
  assert.equal(pendingPresentation(fixture.state.pending[0]).modeKey, "pendingSubmitted");
  assert.ok(fixture.notices.some((notice) => notice.value === "pendingSubmitted"));
  assert.ok(!fixture.notices.some((notice) => notice.value === "messageQueued"));
});

test("queue API errors preserve the last good projection and no selected thread never polls global state", async () => {
  const source = await readFile(mainScript, "utf8");
  const application = source.slice(
    source.indexOf("function applyPendingSnapshot("),
    source.indexOf("async function deletePending("),
  );
  const state = {
    current: { id: "thread-a" },
    openToken: 6,
    pending: [queued()],
    pendingInFlight: new Set(),
  };
  const calls = [];
  const context = {
    state,
    pendingSnapshotIsCurrent,
    renderPending() {},
    command: async (request) => {
      calls.push(request);
      throw new Error("queue_state_unavailable: temporarily unavailable");
    },
    mergePendingResponse: (remote, _preserve, _messages, authoritative) =>
      mergePendingSnapshot(remote, state.pending, state.pendingInFlight, {
        threadId: state.current.id,
        queueAuthoritative: authoritative,
      }),
  };
  const refresh = runInNewContext(
    `let pendingAppliedGeneration = 0, pendingSnapshotGeneration = 0;
    ${application}
    refreshPending`,
    context,
  );
  await assert.rejects(refresh(), /queue_state_unavailable/);
  assert.equal(calls.length, 1);
  assert.equal(state.pending[0].id, "input-a");
  assert.equal(state.pending[0].status, "queued");
  assert.equal(state.pending[0].queue_unverified, true);
  assert.equal(state.pendingQueueUnavailable, "thread-a");
  state.current = null;
  await refresh();
  assert.equal(calls.length, 1);
});

test("failed or unacknowledged receipts never turn a rejected composer submission into success", async () => {
  const source = await readFile(mainScript, "utf8");
  const writer = source.slice(
    source.indexOf("async function write(name) {"),
    source.indexOf("function approval(name) {"),
  );
  for (const status of ["failed", "sending", "steering"]) {
    const fixture = composerContext(() => {
      throw new Error("upstream_error: fixture rejected");
    });
    const attachment = { id: "image-a", type: "image" };
    fixture.state.composerAttachments = [attachment];
    fixture.state.attachmentDrafts.set("thread-a", [attachment]);
    fixture.context.refreshPending = async () => {
      fixture.state.pending = [
        {
          id: "submission-a",
          thread_id: "thread-a",
          status,
          source: "bridge",
        },
      ];
    };
    await assert.rejects(
      runInNewContext(`${writer}\nwrite("steer")`, fixture.context),
      /upstream_error: fixture rejected/,
    );
    assert.equal(fixture.calls.length, 1);
    assert.equal(fixture.text.value, "review the fixture");
    assert.equal(fixture.state.drafts.get("thread-a"), "review the fixture");
    assert.deepEqual(fixture.state.composerAttachments, [attachment]);
    assert.deepEqual(fixture.state.attachmentDrafts.get("thread-a"), [attachment]);
    assert.equal(fixture.state.pendingInFlight.size, 0);
    assert.equal(fixture.notices.length, 0);
  }
});

test("a failed card offers only remove-and-restore and unknown queue state disables that recovery", async () => {
  const source = await readFile(mainScript, "utf8");
  const renderer = source.slice(
    source.indexOf("function pendingNode(entry) {"),
    source.indexOf("function renderPending() {"),
  );
  const deletion = source.slice(
    source.indexOf("async function deletePending(entry, button) {"),
    source.indexOf("async function convertPendingToSteer(entry, button) {"),
  );
  const entry = queued({ status: "failed" });
  const state = { current: { id: "thread-a" }, pending: [entry] };
  const { document, created } = pendingDom();
  const text = { value: "", focus() {}, setSelectionRange() {} };
  const calls = [];
  let recovery;
  const context = {
    document,
    entry,
    state,
    pendingPresentation,
    isWithdrawableQueued,
    markdownNode: () => document.createElement("span"),
    markdownOptions: () => ({}),
    tr: (key) => key,
    run: (action) => (recovery = action()),
    $: () => text,
    command: async (request) => {
      calls.push(request);
      return {
        text: entry.text,
        message_action: "queue",
        queue_deleted: false,
      };
    },
    resizeComposerTextarea() {},
    saveDraft() {},
    setSendMode() {},
    currentThreadWritable: () => true,
    renderPending() {},
    notify() {},
    refreshPending: async () => {},
    openThread: async () => {},
  };
  runInNewContext(`${deletion}\n${renderer}\npendingNode(entry)`, context);
  const restore = created.find(
    (node) => node.tag === "button" && node.textContent === "restoreFailedDraft",
  );
  assert.ok(restore);
  assert.equal(
    created.some((node) =>
      ["withdraw", "convertToSteer", "mergeQueuedMessages"].includes(node.textContent),
    ),
    false,
  );
  restore.onclick({ stopPropagation() {} });
  await recovery;
  assert.deepEqual(
    calls.map((request) => request.command),
    ["pending_message_delete"],
  );
  assert.equal(text.value, entry.text);
  assert.equal(state.pending.length, 0);
  const unknown = pendingDom();
  runInNewContext(`${renderer}\npendingNode(entry)`, {
    ...context,
    document: unknown.document,
    state: { current: { id: "thread-a" }, pending: [entry], pendingQueueUnavailable: "thread-a" },
  });
  assert.equal(
    unknown.created.some((node) => node.tag === "button"),
    false,
  );
});

test("a submission accepted after switching threads cannot clear the new thread draft or attachments", async () => {
  const source = await readFile(mainScript, "utf8");
  const writer = source.slice(
    source.indexOf("async function write(name) {"),
    source.indexOf("function approval(name) {"),
  );
  let accept;
  const fixture = composerContext(
    () =>
      new Promise((resolve) => {
        accept = resolve;
      }),
  );
  const imageA = { id: "image-a", type: "image" },
    imageB = { id: "image-b", type: "image" };
  fixture.state.composerAttachments = [imageA];
  fixture.state.attachmentDrafts.set("thread-a", [imageA]);
  fixture.state.drafts.set("thread-a", fixture.text.value);
  let attachmentRenders = 0,
    referenceClears = 0;
  fixture.context.renderComposerAttachments = () => attachmentRenders++;
  fixture.context.setComposerReference = () => referenceClears++;
  const sending = runInNewContext(`${writer}\nwrite("steer")`, fixture.context);
  assert.equal(fixture.calls.length, 1);
  fixture.state.current = { id: "thread-b" };
  fixture.text.value = "new thread draft";
  fixture.state.drafts.set("thread-b", "new thread draft");
  fixture.state.composerAttachments = [imageB];
  fixture.state.attachmentDrafts.set("thread-b", [imageB]);
  fixture.state.referenceDrafts.set("thread-b", { text: "new thread reference" });
  accept({ status: "steered" });
  await sending;
  assert.equal(fixture.text.value, "new thread draft");
  assert.equal(fixture.state.drafts.get("thread-b"), "new thread draft");
  assert.equal(fixture.state.drafts.has("thread-a"), false);
  assert.deepEqual(fixture.state.composerAttachments, [imageB]);
  assert.deepEqual(fixture.state.attachmentDrafts.get("thread-b"), [imageB]);
  assert.equal(fixture.state.attachmentDrafts.has("thread-a"), false);
  assert.equal(fixture.state.referenceDrafts.has("thread-b"), true);
  assert.equal(attachmentRenders, 0);
  assert.equal(referenceClears, 0);
});


test("upstream local receipts survive delayed echoes without downgrading accepted input", () => {
  const receipt = queued({ status: "accepted", local_submission: true, intent: "new_turn" });
  const stale = mergePendingSnapshot([queued({ status: "queueing" })], [receipt], new Set(), { threadId: "thread-a" });
  assert.equal(stale[0].status, "accepted");
  assert.equal(stale[0].local_submission, true);
  assert.equal(stale[0].intent, "new_turn");
  const missing = mergePendingSnapshot([], stale, new Set(), { threadId: "thread-a" });
  assert.equal(missing[0].status, "accepted");
  assert.equal(isWithdrawableQueued(missing[0]), false);
  const echo = [{ role: "user", id: receipt.id, content: [{ kind: "text", text: receipt.text }] }];
  assert.deepEqual(reconcilePendingMessages(missing, echo, "thread-a"), []);
});

test("native queue outages keep local submissions unverified until an authoritative read", () => {
  const local = queued({ local_submission: true });
  const unknown = mergePendingSnapshot([], [local], new Set(), { threadId: "thread-a", queueAuthoritative: false });
  assert.equal(unknown[0].status, "queued");
  assert.equal(unknown[0].queue_unverified, true);
  assert.equal(isWithdrawableQueued(unknown[0]), false);
  const delivered = mergePendingSnapshot([], unknown, new Set(), { threadId: "thread-a" });
  assert.equal(delivered[0].status, "accepted");
  assert.equal(delivered[0].queue_unverified, false);
  assert.equal(isWithdrawableQueued(delivered[0]), false);
});

test("retained local acknowledgements remain scoped to the selected conversation", () => {
  const other = queued({ thread_id: "thread-b", status: "accepted", local_submission: true });
  assert.deepEqual(mergePendingSnapshot([], [other], new Set(), { threadId: "thread-a" }), []);
});
