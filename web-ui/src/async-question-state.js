export function asyncQuestionReplyMode(questionTurnId, activeTurnId) {
  if (!questionTurnId || !activeTurnId) return "expired";
  return activeTurnId === questionTurnId ? "steer" : "changed";
}
