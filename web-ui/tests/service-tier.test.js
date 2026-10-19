import assert from "node:assert/strict";
import test from "node:test";
import {
  SERVICE_TIER_STORAGE_KEY,
  defaultServiceTier,
  fastServiceTier,
  normalizeServiceTier,
  rememberServiceTier,
  supportedServiceTier,
  serviceTierFromStatus,
  confirmedServiceTier,
  serviceTierLabelKey,
  initialComposerModel,
  composerSelectionIsCurrent,
} from "../src/service-tier.js";

const model = { service_tiers: [{ id: "priority", name: "Fast", description: "2x speed" }] };
const storage = () => {
  const values = new Map();
  return { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value) };
};

test("only supported Fast aliases opt into higher usage", () => {
  for (const value of [null, undefined, "", "standard", "ultrafast", "invalid"])
    assert.equal(normalizeServiceTier(value), "standard");
  assert.equal(normalizeServiceTier("fast"), "priority");
  assert.equal(normalizeServiceTier("priority"), "priority");
});

test("model capability controls Fast availability without inventing a multiplier", () => {
  assert.equal(fastServiceTier(model).description, "2x speed");
  assert.equal(supportedServiceTier("priority", model), "priority");
  assert.equal(supportedServiceTier("priority", { service_tiers: [] }), "unknown");
  assert.equal(supportedServiceTier("priority", {}), "unknown");
  assert.equal(supportedServiceTier("standard", model), "standard");
});

test("new threads remember explicit selections across clients reloading storage", () => {
  const store = storage();
  assert.equal(defaultServiceTier(store), "standard");
  assert.equal(rememberServiceTier(store, "priority"), true);
  assert.equal(defaultServiceTier(store), "priority");
  rememberServiceTier(store, "standard");
  assert.equal(defaultServiceTier(store), "standard");
});

test("reading an existing standard session does not overwrite the Fast default", () => {
  const store = storage();
  rememberServiceTier(store, "priority");
  assert.equal(serviceTierFromStatus({ service_tier: "standard" }), "standard");
  assert.equal(defaultServiceTier(store), "priority");
});

test("unavailable browser storage stays usable and never silently enables Fast", () => {
  const store = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("full"); } };
  assert.equal(defaultServiceTier(store), "standard");
  assert.equal(rememberServiceTier(store, "priority"), false);
});

test("unexpected stored tiers cannot opt new sessions into paid tiers", () => {
  const store = storage();
  store.setItem(SERVICE_TIER_STORAGE_KEY, "ultrafast");
  assert.equal(defaultServiceTier(store), "standard");
});

test("failed or missing Bridge tier reads remain visibly unconfirmed", () => {
  for (const value of [{}, { service_tier: null }, { service_tier: null, service_tier_error: { message: "unavailable" } }, { service_tier: "priority", service_tier_error: "unavailable" }]) {
    assert.equal(serviceTierFromStatus(value), "unknown");
  }
  assert.equal(serviceTierLabelKey("unknown"), "speedUnconfirmed");
  assert.equal(serviceTierFromStatus({ serviceTier: null }), "standard");
  assert.equal(supportedServiceTier("unknown", model), "unknown");
});

test("only an explicit matching receipt confirms a saved tier", () => {
  for (const receipt of [{}, { service_tier: null }, { service_tier: "standard" }, { service_tier: "priority", service_tier_error: "unknown" }])
    assert.equal(confirmedServiceTier(receipt, "priority"), null);
  assert.equal(confirmedServiceTier({ service_tier: "priority" }, "priority"), "priority");
  assert.equal(confirmedServiceTier({ service_tier: "standard" }, "standard"), "standard");
  const store = storage();
  rememberServiceTier(store, "priority");
  assert.equal(rememberServiceTier(store, "unknown"), false);
  assert.equal(defaultServiceTier(store), "priority");
});

test("existing unknown model never silently becomes the first option", () => {
  const choices = [{ id: "first" }, { id: "default", is_default: true }];
  assert.equal(initialComposerModel(choices, "", false), "");
  assert.equal(initialComposerModel(choices, "unlisted", false), "");
  assert.equal(initialComposerModel(choices, "", true), "default");
  assert.equal(initialComposerModel([{ id: "first" }], "", true), "");
  assert.equal(initialComposerModel(choices, "first", false), "first");
});

test("same UUID after navigation cannot reuse an obsolete composer generation", () => {
  const first = { id: "exact-a" }, second = { id: "exact-b" };
  assert.equal(composerSelectionIsCurrent({ current: first, openToken: 3 }, first, 3), true);
  assert.equal(composerSelectionIsCurrent({ current: second, openToken: 4 }, first, 3), false);
  assert.equal(composerSelectionIsCurrent({ current: first, openToken: 5 }, first, 3), false);
  assert.equal(composerSelectionIsCurrent({ current: { ...first }, openToken: 3 }, first, 3), false);
});
