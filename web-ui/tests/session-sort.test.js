import test from "node:test";
import assert from "node:assert/strict";
import {
  arrangeSessions,
  dropSession,
  moveSession,
  sameSessionOrder,
} from "../src/session-sort.js";

test("number preset puts zero coordinator first and compares numbers, not strings", () => {
  const rows = [
    ["a", "10｜报表"],
    ["b", "１｜财务"],
    ["c", "0｜总控"],
    ["d", "2号 PDF"],
    ["e", "普通会话"],
  ];
  const ids = rows.map(([id]) => id),
    threads = new Map(rows.map(([id, title]) => [id, { title }]));
  assert.deepEqual(arrangeSessions(ids, threads, "number"), ["c", "b", "d", "a", "e"]);
  assert.deepEqual(ids, ["a", "b", "c", "d", "e"]);
});
test("unnumbered, duplicate prefixes and missing metadata keep their original relative order", () => {
  const threads = new Map([
    ["a", { title: "2｜甲" }],
    ["b", { title: "2｜乙" }],
    ["c", { title: "没有编号" }],
  ]);
  assert.deepEqual(arrangeSessions(["c", "b", "missing", "a"], threads, "number"), [
    "b",
    "a",
    "c",
    "missing",
  ]);
});
test("recent activity preset is descending and stable for unknown timestamps", () => {
  const threads = new Map([
    ["a", { updated_at_ms: 10 }],
    ["b", { updated_at_ms: 30 }],
  ]);
  assert.deepEqual(arrangeSessions(["a", "x", "b", "y"], threads, "recent"), ["b", "a", "x", "y"]);
});
test("up/down respect boundaries and retain every identity", () => {
  const ids = ["a", "b", "c"];
  assert.deepEqual(moveSession(ids, "a", -1), ids);
  assert.deepEqual(moveSession(ids, "c", 1), ids);
  assert.deepEqual(moveSession(ids, "missing", 1), ids);
  assert.deepEqual(moveSession(ids, "b", -1), ["b", "a", "c"]);
  assert.deepEqual(moveSession(ids, "b", 1), ["a", "c", "b"]);
});
test("dragging across positions inserts above or below the target without off-by-one loss", () => {
  const ids = ["a", "b", "c", "d"];
  assert.deepEqual(dropSession(ids, "a", "c"), ["b", "a", "c", "d"]);
  assert.deepEqual(dropSession(ids, "a", "c", true), ["b", "c", "a", "d"]);
  assert.deepEqual(dropSession(ids, "d", "b"), ["a", "d", "b", "c"]);
  assert.deepEqual(dropSession(ids, "d", "b", true), ["a", "b", "d", "c"]);
  assert.deepEqual(dropSession(ids, "a", "a", true), ids);
  assert.deepEqual(dropSession(ids, "missing", "a"), ids);
});
test("save verification requires the complete exact order, including tail identities", () => {
  assert.equal(sameSessionOrder(["a", "b"], ["a", "b"]), true);
  assert.equal(sameSessionOrder(["a", "b"], ["a"]), false);
  assert.equal(sameSessionOrder(["a", "b"], ["b", "a"]), false);
  assert.equal(sameSessionOrder(["a"], null), false);
});
