import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import { completionMatchesActiveTurn } from "../src/composer-state.js";

const source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
const handler = source.slice(source.indexOf("function handleBridgeEvent("), source.indexOf("function resetHorizontalPosition("));
const setters = source.slice(source.indexOf("function setThreadRunState("), source.indexOf("function knownThread("));
function fixture() {
  const state = { current: { id: "coordinator" }, activeTurnId: null, updatingThreads: new Set(),
    threadRunStates: new Map(), threadRunTurnIds: new Map(), threadRunEventSequences: new Map(),
    authoritativeThreadActive: new Map(), taskTrackedIds: new Set(), taskOverviews: new Map(), taskDirtyIds: new Set() };
  const renders = [];
  const ctx = vm.createContext({ state, Number, Set,
    renderProjects: () => renders.push(new Map(state.threadRunStates)), renderTaskOverviews() {}, renderTasksButton() {},
    renderAsyncQuestion() {}, trackTask: id => state.taskTrackedIds.add(id), markTaskDirty() {},
    handleTemporaryAppServerEvent: () => false, notifyTurnFinished() {}, finishAsyncQuestionTurn() {},
    completionMatchesActiveTurn, scheduleEventRefresh() {}, setTimeout: () => 0,
    scheduleSubagentConversationRefresh() {}, serverRequestTray: {refresh() {}}, setMessageSyncPhase() {},
    scheduleTasksRefresh() {}, loadProjects: async () => {},
  });
  vm.runInContext(setters + handler, ctx);
  return {state, renders, event: value => ctx.handleBridgeEvent(value)};
}
const ack = (turn = "turn-a", sequence = 10) => ({type: "bridge_thread_run_state", thread_id: "worker", turn_id: turn, run_state: "active", bridge_sequence: sequence});
const snapshot = (status, turn, sequence) => ({type: "bridge_thread_activity_snapshot", active_thread_ids: status === "active" ? ["worker"] : [],
  thread_states: {worker: status}, thread_turn_ids: {worker: turn}, thread_sequences: {worker: sequence}});
const complete = (turn, sequence = 20) => ({type: "app_server", bridge_sequence: sequence, message: {method: "turn/completed", params: {threadId: "worker", turn: {id: turn, status: "completed"}}}});

test("a delegated worker becomes active without selecting it or refreshing messages", () => {
  const f = fixture(); f.event(ack());
  assert.equal(f.state.threadRunStates.get("worker"), "active");
  assert.equal(f.renders.at(-1).get("worker"), "active");
  assert.equal(f.state.current.id, "coordinator");
  assert.equal(f.state.activeTurnId, null);
  assert.equal(f.state.taskTrackedIds.has("worker"), true);
});
test("a late idle snapshot cannot erase a just-dispatched worker", () => {
  const f = fixture(); f.event(ack("turn-new", 12)); f.event(snapshot("completed", "turn-old", 10));
  assert.equal(f.renders.at(-1).get("worker"), "active");
  assert.equal(f.state.threadRunTurnIds.get("worker"), "turn-new");
});
test("a late active snapshot cannot restore a completed worker", () => {
  const f = fixture(); f.event(ack()); f.event(complete("turn-a", 20)); f.event(snapshot("active", "turn-a", 10));
  assert.equal(f.renders.at(-1).get("worker"), "completed");
});
test("a successor on an unselected worker survives the previous completion", () => {
  const f = fixture(); f.event(ack()); f.event(ack("turn-b", 15)); f.event(complete("turn-a", 18));
  assert.equal(f.renders.at(-1).get("worker"), "active");
  assert.equal(f.state.threadRunTurnIds.get("worker"), "turn-b");
  f.event(complete("turn-b", 21));
  assert.equal(f.renders.at(-1).get("worker"), "completed");
});
test("an authoritative newer snapshot recovers a missed delegated start and completion", () => {
  const f = fixture(); f.event(ack("turn-a", 10)); f.event(snapshot("active", "turn-b", 30));
  assert.equal(f.state.threadRunTurnIds.get("worker"), "turn-b");
  f.event(snapshot("completed", "turn-b", 35));
  assert.equal(f.renders.at(-1).get("worker"), "completed");
  assert.equal(f.state.authoritativeThreadActive.get("worker"), false);
});

test("Bridge restart resets per-thread event cursors before native state recovery", () => {
  const f = fixture(); f.event(ack("old-runtime-turn", 500));
  f.event({type: "bridge_event_gap", reason: "sequence_reset"});
  f.event(snapshot("active", "new-runtime-turn", 2));
  assert.equal(f.state.threadRunTurnIds.get("worker"), "new-runtime-turn");
  f.event(snapshot("completed", "new-runtime-turn", 3));
  assert.equal(f.renders.at(-1).get("worker"), "completed");
});
