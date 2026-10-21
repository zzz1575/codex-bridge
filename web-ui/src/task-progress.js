const PREFIX = "Y2YY_INTERNAL_SUMMARY ";
const NOTICE = "Read the ledger and summarize these changes. This is an internal notification, not a new task or authorization to write business data. Do not register this notification as a task.";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATUS = {
  done: "任务已完成", failed: "执行失败", canceled: "已取消",
  ambiguous: "执行状态待核对", external_conflict: "会话冲突，待核对",
  blocked_dependency: "前置任务未满足", awaiting_delivery: "等待交付验证",
  awaiting_evidence: "等待业务验证",
};
const REASON = {
  execution_finished_not_delivery_or_business_proof: "执行已结束，尚未完成交付或业务验证",
  original_delivery_receipt_unavailable_or_changed: "交付回执不可用或已变化，需要重新核验",
  predecessor_not_successful: "前置任务尚未成功完成",
  explicit_business_verification_not_recorded: "尚未记录业务验证结果",
  native_failed: "执行过程失败，请查看统筹汇总",
  native_interrupted: "执行已中断，请查看统筹汇总",
};

// Presentation only. Match the complete envelope, never examples or assistant summaries.
export function taskProgressPresentation(text, role) {
  if (role !== "user" || typeof text !== "string") return null;
  const envelope = text.trim().replaceAll("\r\n", "\n");
  const end = `\n${NOTICE}`;
  if (!envelope.startsWith(PREFIX) || !envelope.endsWith(end)) return null;
  let payload;
  try { payload = JSON.parse(envelope.slice(PREFIX.length, -end.length)); }
  catch { return null; }
  if (!payload || Object.keys(payload).sort().join(",") !== "changes,event_id" ||
      typeof payload.event_id !== "string" || !UUID.test(payload.event_id) || !Array.isArray(payload.changes) || !payload.changes.length) return null;
  const updates = [];
  for (const entry of payload.changes) {
    if (!entry || Object.keys(entry).sort().join(",") !== "change,task_id" ||
        typeof entry.task_id !== "string" || !/^T\d+$/.test(entry.task_id) || typeof entry.change !== "string" || !entry.change) return null;
    const [status, ...reason] = entry.change.split(":");
    updates.push({ taskId: entry.task_id, status: STATUS[status] || "任务状态已更新，待确认",
      detail: reason.length ? REASON[reason.join(":")] || "详细情况请查看统筹汇总" : "" });
  }
  return { eventId: payload.event_id, updates, raw: text };
}

export function taskProgressNode(progress, documentApi = globalThis.document) {
  const card = documentApi.createElement("details"), summary = documentApi.createElement("summary"),
    list = documentApi.createElement("div"), hint = documentApi.createElement("p"),
    raw = documentApi.createElement("details"), rawSummary = documentApi.createElement("summary"),
    original = documentApi.createElement("pre");
  card.className = "task-progress";
  card.open = false;
  summary.textContent = `任务进度 · ${progress.updates.length} 项更新`;
  summary.setAttribute("aria-label", summary.textContent);
  list.className = "task-progress-updates";
  for (const update of progress.updates) {
    const row = documentApi.createElement("div"), title = documentApi.createElement("strong");
    row.className = "task-progress-update";
    title.textContent = `${update.taskId} · ${update.status}`;
    row.appendChild(title);
    if (update.detail) {
      const detail = documentApi.createElement("p");
      detail.textContent = update.detail;
      row.appendChild(detail);
    }
    list.appendChild(row);
  }
  hint.className = "task-progress-hint";
  hint.textContent = "统筹自动回报 · 详细结果请查看统筹汇总";
  raw.className = "task-progress-raw";
  raw.open = false;
  rawSummary.textContent = "查看原始通知";
  original.textContent = progress.raw;
  raw.append(rawSummary, original);
  card.append(summary, list, hint, raw);
  return card;
}
