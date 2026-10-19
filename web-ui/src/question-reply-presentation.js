const OPEN_REPLY = "<send_user_message_question_reply>";
const CLOSE_REPLY = "</send_user_message_question_reply>";

function replyLabels(labels) {
  return {
    question: typeof labels?.question === "string" ? labels.question : "Question",
    answer: typeof labels?.answer === "string" ? labels.answer : "Answer",
  };
}

// This is a view of an exact protocol envelope, never a rewrite of the message.
export function questionReplyPresentation(text, role, labels = {}) {
  if (role !== "user" || typeof text !== "string") return null;
  const envelope = text.trim();
  if (!envelope.startsWith(OPEN_REPLY) || !envelope.endsWith(CLOSE_REPLY)) return null;
  let entries;
  try {
    entries = JSON.parse(envelope.slice(OPEN_REPLY.length, -CLOSE_REPLY.length));
  } catch {
    return null;
  }
  if (!Array.isArray(entries) || !entries.length) return null;
  const replies = [];
  for (const entry of entries) {
    if (
      !entry ||
      typeof entry !== "object" ||
      Array.isArray(entry) ||
      Object.keys(entry).sort().join(",") !== "answer,question,questionItemId" ||
      typeof entry.question !== "string" ||
      typeof entry.answer !== "string" ||
      typeof entry.questionItemId !== "string"
    )
      return null;
    let identity;
    try {
      identity = JSON.parse(entry.questionItemId);
    } catch {
      return null;
    }
    if (
      !Array.isArray(identity) ||
      identity.length !== 3 ||
      identity[0] !== "request_user_input_async" ||
      typeof identity[1] !== "string" ||
      !identity[1] ||
      !Number.isSafeInteger(identity[2]) ||
      identity[2] < 0
    )
      return null;
    replies.push({ question: entry.question, answer: entry.answer });
  }
  const translated = replyLabels(labels);
  return {
    replies,
    text: replies
      .map(
        ({ question, answer }) =>
          `${translated.question}: ${question}\n${translated.answer}: ${answer}`,
      )
      .join("\n\n"),
  };
}

export function questionReplyNode(presentation, labels = {}, documentApi = globalThis.document) {
  const root = documentApi.createElement("div"),
    translated = replyLabels(labels);
  root.className = "question-replies";
  for (const reply of presentation.replies) {
    const section = documentApi.createElement("section"),
      question = documentApi.createElement("p"),
      answer = documentApi.createElement("p");
    section.className = "question-reply";
    question.className = "question-reply-question";
    answer.className = "question-reply-answer";
    question.textContent = `${translated.question}: ${reply.question}`;
    answer.textContent = `${translated.answer}: ${reply.answer}`;
    section.append(question, answer);
    root.appendChild(section);
  }
  return root;
}
