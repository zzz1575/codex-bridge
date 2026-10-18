export function goalPanelKey(threadId, goal) {
  return goal ? `${threadId}:${goal.createdAt || goal.objective}` : null;
}

export function questionPanelKey(threadId, question) {
  return question ? `${threadId}:${question.item_id}` : null;
}

export function snapshotIsCurrent(
  requestVersion,
  currentVersion,
  requestThreadId,
  currentThreadId,
) {
  return requestVersion === currentVersion && requestThreadId === currentThreadId;
}
