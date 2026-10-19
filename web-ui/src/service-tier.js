export const SERVICE_TIER_STORAGE_KEY = "codex-bridge.new-thread-service-tier";

export function normalizeServiceTier(value) {
  return value === "priority" || value === "fast" ? "priority" : "standard";
}

export function fastServiceTier(model) {
  return model?.service_tiers?.find((tier) => ["priority", "fast"].includes(tier.id)) || null;
}

export function supportedServiceTier(value, model) {
  if (value === "standard") return "standard";
  if (["priority", "fast"].includes(value) && fastServiceTier(model)) return "priority";
  return "unknown";
}

export function serviceTierFromStatus(status) {
  if (status?.service_tier_error) return "unknown";
  if (Object.hasOwn(status || {}, "service_tier"))
    return ["standard", "priority", "fast"].includes(status.service_tier)
      ? normalizeServiceTier(status.service_tier) : "unknown";
  if (Object.hasOwn(status || {}, "serviceTier"))
    return status.serviceTier === null ? "standard"
      : ["standard", "priority", "fast"].includes(status.serviceTier)
        ? normalizeServiceTier(status.serviceTier) : "unknown";
  return "unknown";
}

export function confirmedServiceTier(receipt, requested) {
  return !receipt?.service_tier_error && ["standard", "priority", "fast"].includes(receipt?.service_tier)
    && normalizeServiceTier(receipt.service_tier) === requested ? requested : null;
}

export function serviceTierLabelKey(value) {
  return value === "priority" ? "speedFast" : value === "standard" ? "speedStandard" : "speedUnconfirmed";
}

export function initialComposerModel(models, current, draft = false) {
  return models.some((model) => model.id === current) ? current
    : draft ? models.find((model) => model.is_default)?.id || "" : "";
}

export function composerSelectionIsCurrent(state, thread, openToken) {
  return state.current === thread && state.openToken === openToken;
}

export function defaultServiceTier(storage) {
  try {
    return normalizeServiceTier(storage.getItem(SERVICE_TIER_STORAGE_KEY));
  } catch {
    return "standard";
  }
}

export function rememberServiceTier(storage, value) {
  if (!["standard", "priority", "fast"].includes(value)) return false;
  try {
    storage.setItem(SERVICE_TIER_STORAGE_KEY, normalizeServiceTier(value));
    return true;
  } catch {
    return false;
  }
}
