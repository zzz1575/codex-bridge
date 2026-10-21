const COUNTS = [
  "total_tokens",
  "input_tokens",
  "cached_input_tokens",
  "cache_write_input_tokens",
  "output_tokens",
  "reasoning_output_tokens",
];

export function mergeModelUsage(current = {}, previous = {}) {
  const merged = {};
  for (const model of new Set([...Object.keys(previous), ...Object.keys(current)])) {
    merged[model] = {};
    for (const key of COUNTS) {
      merged[model][key] = Math.max(
        Number(current[model]?.[key] || 0),
        Number(previous[model]?.[key] || 0),
      );
    }
  }
  return merged;
}

export function modelUsageRows(statistics) {
  const rows = Object.entries(statistics?.model_usage || {})
    .map(([model, usage]) => ({ model, ...usage }))
    .filter((row) => Number(row.total_tokens || 0) > 0);
  const attributed = rows.reduce((sum, row) => sum + Number(row.total_tokens || 0), 0);
  const remaining = Math.max(0, Number(statistics?.total_tokens || 0) - attributed);
  if (remaining) {
    const unknown = rows.find((row) => row.model === "unknown");
    if (unknown) unknown.total_tokens = Number(unknown.total_tokens || 0) + remaining;
    else rows.push({ model: "unknown", total_tokens: remaining });
  }
  return rows.sort((a, b) => Number(b.total_tokens || 0) - Number(a.total_tokens || 0));
}
