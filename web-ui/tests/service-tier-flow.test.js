import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import { confirmedServiceTier, supportedServiceTier, composerSelectionIsCurrent,
  rememberServiceTier, defaultServiceTier, normalizeServiceTier } from "../src/service-tier.js";

const source = readFileSync(new URL("../src/main.js", import.meta.url), "utf8");
const part = (start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
const materialize = part("const createdDraftSettings =", "const serverRequestTray =");
const write = part("async function write(name)", "let approvalGeneration =");
const apply = part("async function applyThreadSettings()", "async function refreshActivity()");
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const flush = () => new Promise((resolve) => setImmediate(resolve));

function fixture() {
  const elements = new Map();
  const $ = (id) => {
    if (!elements.has(id)) elements.set(id, { value: "", textContent: "", disabled: false, hidden: false });
    return elements.get(id);
  };
  const draft = { id: "draft:own", projectless_draft: true, preferred_model: "fixture-model", preferred_effort: "ultra", preferred_service_tier: "priority" };
  const state = { current: draft, openToken: 7, composerAttachments: [{ type: "image", url: "synthetic-red.png" }], composerReference: null,
    drafts: new Map(), attachmentDrafts: new Map(), referenceDrafts: new Map(), pending: [], pendingInFlight: new Set() };
  $("messageText").value = "original A message";
  const notices = [], submissions = [];
  const ctx = vm.createContext({ $, state, voiceRecorder: null, confirmedServiceTier, composerSelectionIsCurrent, normalizeServiceTier,
    tr: (key) => key, saveDraft: (id, text) => state.drafts.set(id, text), persistDrafts() {},
    applyThreadWriterLock() {}, renderProjects() {}, loadProjects: async () => {},
    setComposerSubmitting() {}, notify: (value) => notices.push(value),
    newSubmissionId: () => { submissions.push(true); throw new Error("REACHED_SEND_AFTER_SETTINGS"); },
    console });
  vm.runInContext(materialize + "\n" + write, ctx);
  return { ctx, state, $, notices, submissions };
}

test("created draft settings failure keeps the same thread and never reaches send", async () => {
  const { ctx, state, $, submissions } = fixture();
  const calls = [];
  ctx.command = async (request) => {
    calls.push(request.command);
    if (request.command === "thread_create") return { thread: { id: "native-A", cwd: "qa" } };
    throw new Error("SETTINGS_FAILED");
  };
  await assert.rejects(ctx.write("steer"), /SETTINGS_FAILED/);
  assert.deepEqual(calls, ["thread_create", "thread_settings_update"]);
  assert.equal(submissions.length, 0);
  assert.equal(state.current.id, "native-A");
  assert.equal($("messageText").value, "original A message");
  ctx.command = async (request) => { calls.push(request.command); return { service_tier: "priority" }; };
  await assert.rejects(ctx.write("steer"), /REACHED_SEND_AFTER_SETTINGS/);
  assert.deepEqual(calls, ["thread_create", "thread_settings_update", "thread_settings_update"]);
  assert.equal(submissions.length, 1, "Only confirmed settings permit entering the send path");
});

for (const phase of ["create", "settings"]) test(`switching session while awaiting ${phase} cannot hijack or send into B`, async () => {
  const { ctx, state, $, notices, submissions } = fixture();
  const gate = deferred(), calls = [];
  ctx.command = async (request) => {
    calls.push(request.command);
    if (request.command === "thread_create") return phase === "create" ? gate.promise : { thread: { id: "native-A", cwd: "qa" } };
    return phase === "settings" ? gate.promise : { service_tier: "priority" };
  };
  const pending = ctx.write("steer");
  await flush();
  const second = { id: "native-B" };
  state.current = second; state.openToken++;
  $("messageText").value = "B draft";
  state.composerAttachments = [{ type: "image", url: "B-only.png" }];
  gate.resolve(phase === "create" ? { thread: { id: "native-A", cwd: "qa" } } : { service_tier: "priority" });
  await pending;
  assert.equal(state.current, second);
  assert.equal($("messageText").value, "B draft");
  assert.equal(state.composerAttachments[0].url, "B-only.png");
  assert.equal(state.drafts.get("native-A"), "original A message");
  assert.equal(state.attachmentDrafts.get("native-A")[0].url, "synthetic-red.png");
  assert.equal(submissions.length, 0);
  assert.ok(notices.includes("draftSessionChanged"));
  assert.deepEqual(calls, ["thread_create", "thread_settings_update"]);
});

test("a late old-session settings acknowledgement cannot overwrite a newer explicit default", async () => {
  const values = new Map(), storage = { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
  const gates = { A: deferred(), B: deferred() }, elements = new Map();
  const $ = (id) => { if (!elements.has(id)) elements.set(id, { value: "", disabled: false }); return elements.get(id); };
  const model = { service_tiers: [{ id: "priority" }] };
  const state = { current: { id: "A" }, openToken: 1 };
  $("modelSelect").value = "fixture-model"; $("effortSelect").value = "ultra"; $("serviceTierSelect").value = "priority";
  const ctx = vm.createContext({ $, state, supportedServiceTier, confirmedServiceTier, composerSelectionIsCurrent, rememberServiceTier,
    composerSettingsSequence: 0, composerStatusSequence: 0, composerSettingsInFlight: new Set(), createdDraftSettings: new Map(),
    selectedModelOption: () => model, requireCurrentThreadWriter() {}, updateModelApplyState() {}, tr: (key) => key,
    window: { localStorage: storage }, command: (request) => gates[request.thread_id].promise,
    refreshComposerStatus: async () => {}, notify() {} });
  vm.runInContext(apply, ctx);
  const first = ctx.applyThreadSettings();
  state.current = { id: "B" }; state.openToken++;
  $("modelApply").disabled = false; $("serviceTierSelect").value = "standard";
  const second = ctx.applyThreadSettings();
  gates.B.resolve({ service_tier: "standard" }); await second;
  gates.A.resolve({ service_tier: "priority" }); await first;
  assert.equal(defaultServiceTier(storage), "standard");
});

test("a transport success without tier confirmation cannot save the new-session preference", async () => {
  const { ctx } = fixture();
  vm.runInContext("globalThis.applyCreatedDraftSettingsForTest = applyCreatedDraftSettings", ctx);
  ctx.command = async () => ({ service_tier: null, service_tier_error: { message: "not confirmed" } });
  const thread = { id: "native-A", pending_draft_settings: { model: "fixture-model", effort: "ultra", service_tier: "priority" } };
  await assert.rejects(ctx.applyCreatedDraftSettingsForTest(thread), /speedSettingsUnconfirmed/);
  assert.equal(thread.pending_draft_settings.service_tier, "priority");
});

test("opening a new draft clears only the inherited queue-sync warning", () => {
  const originalPending = [{ id: "queued-A", thread_id: "native-A", status: "queued" }];
  const oldAttachments = [{ type: "image", url: "synthetic-A.png" }];
  const oldReference = { thread_id: "native-A", text: "selected context" };
  const preservedAttachments = [{ type: "image", url: "synthetic-B.png" }];
  const state = {
    current: { id: "native-A" }, openToken: 7,
    composerModel: "fixture-model", composerEffort: "ultra",
    composerAttachments: oldAttachments, composerReference: oldReference,
    pending: originalPending, pendingQueueUnavailable: "native-A",
    drafts: new Map([["native-B", "B draft"]]),
    attachmentDrafts: new Map([["native-B", preservedAttachments]]),
    referenceDrafts: new Map([["native-B", { text: "B reference" }]]),
  };
  const elements = new Map(), commands = [], renderedWarnings = [];
  const $ = (id) => {
    if (!elements.has(id)) elements.set(id, { value: "", textContent: "", hidden: false, replaceChildren() {}, focus() {} });
    return elements.get(id);
  };
  $("messageText").value = "A draft";
  const ctx = vm.createContext({
    state, $, crypto: { randomUUID: () => "synthetic-new-draft" },
    defaultServiceTier: () => "priority", tr: (key) => key,
    window: { localStorage: {}, location: { pathname: "/qa", search: "" }, history: { pushState() {} } },
    command: (...args) => commands.push(args),
    closePanels() {}, setThreadHeaderExpanded() {},
    saveDraft: (id, text) => state.drafts.set(id, text),
    serverRequestTray: { render() {} }, renderApprovals() {}, resetThreadViewState() {},
    applyThreadWriterLock() {}, setSendMode() {}, syncTemporaryForCurrent() {},
    setMessageSyncPhase() {}, clearMessageTimeline() {}, renderRepairHint() {}, renderThreadStatistics() {},
    renderPending: () => renderedWarnings.push(state.pendingQueueUnavailable),
    renderProjects() {}, showActivity() {}, updateTurnNavigation() {},
    refreshComposerStatus: async () => {},
  });
  vm.runInContext(part("function openProjectlessDraft()", "async function createThread(worktree)"), ctx);
  ctx.openProjectlessDraft();
  assert.equal(state.current.id, "draft:synthetic-new-draft");
  assert.equal(state.current.preferred_service_tier, "priority");
  assert.equal(state.pendingQueueUnavailable, null, "A fresh draft must not inherit queue-sync failures");
  assert.deepEqual(renderedWarnings, [null], "The draft is rendered without a stale queue warning");
  assert.equal(state.pending.length, 0);
  assert.equal(originalPending.length, 1, "Opening a draft never mutates the old queue records");
  assert.equal(originalPending[0].status, "queued");
  assert.equal(state.drafts.get("native-A"), "A draft");
  assert.equal(state.drafts.get("native-B"), "B draft");
  assert.equal(state.attachmentDrafts.get("native-A"), oldAttachments);
  assert.equal(state.attachmentDrafts.get("native-B"), preservedAttachments);
  assert.equal(state.referenceDrafts.get("native-A"), oldReference);
  assert.equal(state.referenceDrafts.get("native-B").text, "B reference");
  assert.equal(commands.length, 0, "Opening a local draft must not change a remote queue");
});
