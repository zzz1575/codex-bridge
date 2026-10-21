const attachmentLabels = new Map([
  ["Image attachment", "image"],
  ["Audio attachment", "audio"],
  ["Pasted text attachment", "pasted_text"],
  ["File attachment", "file"],
]);

function inputFingerprint(textParts, attachments) {
  return JSON.stringify({
    text: textParts.join("\n"),
    image: attachments.filter((kind) => kind === "image").length,
    audio: attachments.filter((kind) => kind === "audio").length,
    pastedText: attachments.filter((kind) => kind === "pasted_text").length,
    file: attachments.filter((kind) => kind === "file").length,
  });
}

function pendingFingerprint(entry) {
  const textParts = [],
    attachments = [];
  for (const line of String(entry?.text || "").split("\n")) {
    const match =
      /^\[(Image attachment|Audio attachment|Pasted text attachment|File attachment)\]$/.exec(
        line.trim(),
      );
    if (match) attachments.push(attachmentLabels.get(match[1]));
    else textParts.push(line);
  }
  return inputFingerprint(textParts, attachments);
}

function messageFingerprint(message) {
  const textParts = [],
    attachments = [];
  for (const item of message?.content || []) {
    if (item.kind === "text" && typeof item.text === "string") textParts.push(item.text);
    else if (item.kind === "image") attachments.push("image");
    else if (item.kind === "audio") attachments.push("audio");
    else if (item.kind === "pasted_text") attachments.push("pasted_text");
    else if (item.kind === "uploaded_file") attachments.push("file");
    else if (item.kind === "context" && item.label === "Attached-file context")
      attachments.push("file");
    else if (item.kind === "context" && attachmentLabels.has(item.label)) {
      attachments.push(attachmentLabels.get(item.label));
    }
  }
  return inputFingerprint(textParts, attachments);
}

export function pendingInputSummary(text, attachments) {
  const parts = [];
  if (text.trim()) parts.push(text);
  for (const attachment of attachments) {
    parts.push(
      attachment.type === "audio"
        ? "[Audio attachment]"
        : attachment.type === "pasted_text"
          ? "[Pasted text attachment]"
          : attachment.type === "file"
            ? "[File attachment]"
            : "[Image attachment]",
    );
  }
  return parts.join("\n");
}

export function isWithdrawableQueued(entry) {
  return (
    entry?.status === "queued" &&
    ["app_server_queue", "demo_wasm"].includes(entry.source) &&
    !entry.queue_unverified &&
    !entry.handoff
  );
}

export function pendingPresentation(entry) {
  if (isWithdrawableQueued(entry))
    return {
      modeKey: "pendingQueued",
      statusKey: entry.start_error ? "pendingStartFailed" : "pendingQueuedReady",
      withdrawable: true,
    };
  const submitted = ["accepted", "processing", "dequeued", "steered", "applied"].includes(
    entry?.status,
  );
  return {
    modeKey:
      entry?.action === "steer"
        ? "followUp"
        : submitted
          ? "pendingSubmitted"
          : entry?.status === "failed"
            ? "pendingSending"
            : entry?.status === "queued"
              ? "pendingUnconfirmed"
              : entry?.intent === "new_turn"
                ? "pendingSending"
                : "pendingQueueRequest",
    statusKey: entry?.start_error
      ? "pendingStartFailed"
      : entry?.queue_unverified
        ? "pendingQueueUnavailable"
        : entry?.status === "queued"
          ? "pendingQueueUnavailable"
          : entry?.status === "failed"
            ? "sendFailed"
            : entry?.status === "processing"
              ? "processingShort"
              : submitted
                ? "pendingRecordSync"
                : "submittingToServer",
    withdrawable: false,
  };
}

export function pendingSnapshotIsCurrent(snapshot, current) {
  return (
    snapshot.threadId === current.threadId &&
    snapshot.openToken === current.openToken &&
    Number.isSafeInteger(snapshot.generation) &&
    snapshot.generation >= current.appliedGeneration
  );
}

export function mergePendingSnapshot(remote, local, inFlightIds, options = {}) {
  const { threadId = null, queueAuthoritative = true } = options;
  const scoped = (entry) => !threadId || entry.thread_id === threadId;
  const previous = new Map(local.filter(scoped).map((entry) => [entry.id, entry]));
  let entries = (Array.isArray(remote) ? remote : []).filter(scoped).map((entry) => {
    const prior = previous.get(entry.id);
    // A failed native queue read cannot turn a previously observed queued item
    // into an accepted receipt. Keep that projection explicitly unverified.
    const value = !queueAuthoritative && prior?.status === "queued" ? prior : entry;
    return {
      ...value,
      ...(prior?.intent ? { intent: prior.intent } : {}),
      ...(prior?.local_submission ? { local_submission: true } : {}),
      ...(prior?.status === "accepted" && ["queueing", "steering"].includes(value.status)
        ? { status: "accepted" }
        : {}),
      ...(!queueAuthoritative && value.status === "queued" ? { queue_unverified: true } : {}),
    };
  });
  const ids = new Set(entries.map((entry) => entry.id));
  if (!queueAuthoritative) {
    entries = [
      ...entries,
      ...local
        .filter(
          (entry) =>
            scoped(entry) &&
            !ids.has(entry.id) &&
            (entry.source !== "web_optimistic" ||
              inFlightIds.has(entry.id) ||
              ["accepted", "queued", "processing"].includes(entry.status)),
        )
        .map((entry) => ({
          ...entry,
          ...(entry.status === "queued" ? { queue_unverified: true } : {}),
        })),
    ];
  }
  const retainedIds = new Set(entries.map((entry) => entry.id));
  return [
    ...entries,
    ...local.filter(
      (entry) =>
        scoped(entry) &&
        !retainedIds.has(entry.id) &&
        ((entry.source === "web_optimistic" && inFlightIds.has(entry.id)) ||
          (entry.local_submission && entry.status !== "failed")),
    ).map((entry) =>
      queueAuthoritative && entry.local_submission && entry.status === "queued" && !inFlightIds.has(entry.id)
        ? { ...entry, status: "accepted", queue_unverified: false }
        : entry,
    ),
  ];
}

export function reconcilePendingMessages(entries, authoritativeMessages, threadId = null) {
  const claimed = new Set(),
    messages = Array.isArray(authoritativeMessages) ? authoritativeMessages : [];
  return entries.filter((entry) => {
    if (threadId && entry.thread_id !== threadId) return true;
    const fingerprint = pendingFingerprint(entry);
    const landedAt = messages.findIndex((message, index) => {
      if (claimed.has(index) || message.role !== "user") return false;
      if (message.id && message.id === entry.id) return true;
      if (!Number.isSafeInteger(entry.after_message_index)) return false;
      if (message.message_index <= entry.after_message_index) return false;
      return messageFingerprint(message) === fingerprint;
    });
    if (landedAt < 0) return true;
    claimed.add(landedAt);
    return false;
  });
}

export async function sendWithExplicitNewTurnFallback(request, submit, onNewTurn = () => {}) {
  try {
    return { value: await submit(request), command: request.command };
  } catch (error) {
    // Only an explicit no-write/no-active-turn rejection allows one new-turn
    // attempt. Timeouts, partial queue acceptance and all unknown failures do not.
    if (
      request.command !== "steer" ||
      error?.partial ||
      error?.start_error ||
      !String(error?.message || "").startsWith("no_active_turn:")
    )
      throw error;
    onNewTurn();
    return { value: await submit({ ...request, command: "send" }), command: "send" };
  }
}
