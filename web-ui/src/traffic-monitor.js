export const TRAFFIC_MONITOR_STORAGE_KEY = "codex-bridge.traffic-monitor.v1";
const MAX_RECENT = 100;

export function createTrafficMonitor(
  storage = typeof window === "undefined" ? null : window.localStorage,
) {
  let enabled = false;
  try {
    enabled = storage?.getItem(TRAFFIC_MONITOR_STORAGE_KEY) === "1";
  } catch {
    // Monitoring remains optional when browser storage is unavailable.
  }
  const listeners = new Set();
  const totals = new Map();
  const recent = [];
  const notify = () => listeners.forEach((listener) => listener());
  return {
    get enabled() {
      return enabled;
    },
    setEnabled(value) {
      enabled = Boolean(value);
      try {
        storage?.setItem(TRAFFIC_MONITOR_STORAGE_KEY, enabled ? "1" : "0");
      } catch {
        // Keep the current-page preference when storage is unavailable.
      }
      notify();
    },
    record(name, bytes, status = "ok") {
      if (!enabled || !Number.isSafeInteger(bytes) || bytes < 0) return;
      const key = String(name);
      const total = totals.get(key) || { name: key, count: 0, bytes: 0, maxBytes: 0 };
      total.count += 1;
      total.bytes += bytes;
      total.maxBytes = Math.max(total.maxBytes, bytes);
      totals.set(key, total);
      recent.unshift({ name: key, bytes, status, time: Date.now() });
      recent.length = Math.min(recent.length, MAX_RECENT);
      notify();
    },
    snapshot() {
      return {
        enabled,
        totals: [...totals.values()].sort((left, right) => right.bytes - left.bytes),
        recent: [...recent],
      };
    },
    clear() {
      totals.clear();
      recent.length = 0;
      notify();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export const trafficMonitor = createTrafficMonitor();

if (typeof PerformanceObserver !== "undefined") {
  const monitoredFetchPaths = new Set([
    "/api/auth",
    "/api/command",
    "/api/file-ticket",
    "/api/file-preview",
    "/api/attachment-upload",
  ]);
  try {
    new PerformanceObserver((list) => {
      if (!trafficMonitor.enabled) return;
      for (const entry of list.getEntries()) {
        const url = new URL(entry.name);
        if (url.origin !== location.origin || !url.pathname.startsWith("/api/")) continue;
        if (monitoredFetchPaths.has(url.pathname)) continue;
        const bytes = entry.decodedBodySize || entry.encodedBodySize;
        if (bytes > 0) trafficMonitor.record(url.pathname.slice(5), bytes, "resource");
      }
    }).observe({ type: "resource", buffered: false });
  } catch {
    // Resource Timing is supplementary; fetch and WebSocket counting still work.
  }
}

export async function monitoredJson(response, name, monitor = trafficMonitor) {
  if (!monitor.enabled) return response.json();
  const bytes = await response.arrayBuffer();
  monitor.record(name, bytes.byteLength, response.status);
  return JSON.parse(new TextDecoder().decode(bytes));
}

export async function monitoredText(response, name, monitor = trafficMonitor) {
  if (!monitor.enabled) return response.text();
  const bytes = await response.arrayBuffer();
  monitor.record(name, bytes.byteLength, response.status);
  return new TextDecoder().decode(bytes);
}
