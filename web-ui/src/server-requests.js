// Server prompts are transient user interaction, never persisted or automatically answered.
const SCHEMA_KEYS = new Set([
  "$schema",
  "type",
  "title",
  "description",
  "default",
  "examples",
  "properties",
  "required",
  "additionalProperties",
  "enum",
  "enumNames",
  "const",
  "oneOf",
  "anyOf",
  "allOf",
  "items",
  "minItems",
  "maxItems",
  "uniqueItems",
  "minLength",
  "maxLength",
  "minimum",
  "maximum",
  "exclusiveMinimum",
  "exclusiveMaximum",
  "format",
]);
export function supportedPromptSchema(schema, depth = 0) {
  if (!schema || typeof schema !== "object" || Array.isArray(schema) || depth > 8) return false;
  if (Object.keys(schema).some((key) => !SCHEMA_KEYS.has(key))) return false;
  if (
    schema.type &&
    ![schema.type]
      .flat()
      .every((type) =>
        ["object", "array", "string", "number", "integer", "boolean", "null"].includes(type),
      )
  )
    return false;
  if (
    schema.properties &&
    (typeof schema.properties !== "object" ||
      Array.isArray(schema.properties) ||
      Object.keys(schema.properties).length > 128 ||
      Object.values(schema.properties).some((child) => !supportedPromptSchema(child, depth + 1)))
  )
    return false;
  if (schema.items && !supportedPromptSchema(schema.items, depth + 1)) return false;
  for (const key of ["oneOf", "anyOf", "allOf"]) {
    if (
      schema[key] &&
      (!Array.isArray(schema[key]) ||
        !schema[key].length ||
        schema[key].length > 128 ||
        schema[key].some((child) => !supportedPromptSchema(child, depth + 1)))
    )
      return false;
  }
  return !schema.format || ["email", "uri", "date", "date-time"].includes(schema.format);
}
export function safePromptUrl(value) {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) ? url.href : null;
  } catch {
    return null;
  }
}
function choices(schema) {
  if (Array.isArray(schema.enum) && schema.enum.every((value) => typeof value === "string"))
    return schema.enum.map((value, i) => ({
      value,
      title: schema.enumNames?.[i] ?? String(value),
    }));
  const variants = schema.oneOf || schema.anyOf;
  return Array.isArray(variants) && variants.every((entry) => typeof entry.const === "string")
    ? variants.map((entry) => ({ value: entry.const, title: entry.title || entry.const }))
    : null;
}
function simpleField(schema) {
  return (
    !!choices(schema) ||
    ["string", "number", "integer", "boolean"].includes(schema.type) ||
    (schema.type === "array" && !!choices(schema.items || {}))
  );
}

export function createServerRequestTray({
  document,
  getContainer,
  getThreadId,
  command,
  isChinese = () => true,
  enabled = () => true,
}) {
  let snapshot = [],
    revision = 0,
    tray;
  const cards = new Map();
  const text = (zh, en) => (isChinese() ? zh : en);
  function element(tag, content, className) {
    const node = document.createElement(tag);
    if (content !== undefined) node.textContent = content;
    if (className) node.className = className;
    return node;
  }
  function button(label, click, danger = false) {
    const node = element("button", label, danger ? "danger" : "");
    node.type = "button";
    node.onclick = click;
    return node;
  }
  function removeCard(id) {
    const card = cards.get(id);
    card?.node.remove();
    cards.delete(id);
  }
  function reset() {
    revision++;
    snapshot = [];
    for (const id of cards.keys()) removeCard(id);
    if (tray) tray.hidden = true;
  }
  function inputFor(schema, required) {
    const options = choices(schema);
    let input, read;
    if (schema.type === "array" && choices(schema.items || {})) {
      input = element("div");
      const boxes = [];
      for (const option of choices(schema.items)) {
        const label = element("label"),
          box = element("input");
        box.type = "checkbox";
        box.value = option.value;
        label.append(box, element("span", option.title));
        input.append(label);
        boxes.push(box);
      }
      read = () => {
        const value = boxes.filter((box) => box.checked).map((box) => box.value);
        return value.length || required ? value : undefined;
      };
    } else if (options || schema.type === "boolean") {
      input = element("select");
      const blank = element("option", text("请选择", "Choose an answer"));
      blank.value = "";
      input.append(blank);
      for (const option of options || [
        { value: true, title: text("是", "Yes") },
        { value: false, title: text("否", "No") },
      ]) {
        const item = element("option", option.title);
        item.value = String(option.value);
        input.append(item);
      }
      input.required = required;
      read = () =>
        input.value === ""
          ? undefined
          : schema.type === "boolean"
            ? input.value === "true"
            : input.value;
    } else {
      input = element("input");
      input.type = ["number", "integer"].includes(schema.type)
        ? "number"
        : schema.format === "email"
          ? "email"
          : schema.format === "date"
            ? "date"
            : "text";
      input.required = required;
      if (schema.type === "integer") input.step = "1";
      else if (schema.type === "number") input.step = "any";
      for (const [key, prop] of [
        ["minimum", "min"],
        ["maximum", "max"],
        ["minLength", "minLength"],
        ["maxLength", "maxLength"],
      ])
        if (schema[key] !== undefined) input[prop] = schema[key];
      read = () =>
        input.value === "" && !required
          ? undefined
          : ["number", "integer"].includes(schema.type)
            ? Number(input.value)
            : input.value;
    }
    return {
      node: input,
      read,
      inputs:
        input.tagName === "DIV"
          ? Array.from(input.children).map((label) => label.children[0])
          : [input],
    };
  }
  function buildCard(entry) {
    const node = element("article"),
      request = entry.request || {},
      params = request.params || {};
    const heading = element(
      "strong",
      request.method === "item/tool/requestUserInput"
        ? text("等待你的回答", "Your answer is needed")
        : text("等待你的确认", "Your confirmation is needed"),
    );
    node.append(heading);
    const form = element("form"),
      fields = [],
      controls = [],
      error = element("p", "", "server-request-error");
    error.setAttribute("role", "alert");
    form.autocomplete = "off";
    const card = { node, signature: JSON.stringify(entry), busy: false, controls, error };
    async function submit(response) {
      if (card.busy) return;
      if (getThreadId() !== entry.thread_id) {
        error.textContent = text("请返回提出请求的会话", "Return to the requesting session");
        return;
      }
      card.busy = true;
      error.textContent = "";
      revision++;
      controls.forEach((control) => {
        control.disabled = true;
      });
      try {
        await command({
          command: "server_request_respond",
          id: entry.id,
          thread_id: entry.thread_id,
          response,
        });
        revision++;
        snapshot = snapshot.filter((item) => item.id !== entry.id);
        removeCard(entry.id);
        await refresh();
      } catch (reason) {
        // Validation and connectivity failures retain the user's inputs for an explicit retry.
        error.textContent =
          reason?.message || text("提交失败，请刷新后重试", "Could not submit; refresh and retry");
      } finally {
        card.busy = false;
        controls.forEach((control) => {
          control.disabled = false;
        });
      }
    }
    function addButton(label, response, danger) {
      const control = button(label, () => submit(response), danger);
      controls.push(control);
      form.append(control);
      return control;
    }
    let readResponse,
      mayAccept = true;
    if (request.method === "item/tool/requestUserInput") {
      for (const question of params.questions || []) {
        const group = element("fieldset"),
          legend = element("legend", question.header || ""),
          prompt = element("p", question.question || "");
        group.append(legend, prompt);
        let select;
        if (Array.isArray(question.options) && question.options.length) {
          select = element("select");
          const blank = element("option", text("请选择，或补充说明", "Choose or provide a note"));
          blank.value = "";
          select.append(blank);
          for (const option of question.options) {
            const choice = element("option", option.label);
            choice.value = option.label;
            select.append(choice);
            if (option.description) group.append(element("p", option.description, "caption"));
          }
          select.setAttribute("aria-label", question.header || question.question);
          group.append(select);
          controls.push(select);
        }
        const note = element(question.isSecret ? "input" : "textarea");
        if (question.isSecret) {
          note.type = "password";
          note.autocomplete = "off";
          note.spellcheck = false;
        }
        note.setAttribute("aria-label", question.question || question.id);
        note.placeholder = select
          ? text("补充说明（可选）", "Additional notes (optional)")
          : text("输入回答", "Enter your answer");
        group.append(note);
        form.append(group);
        controls.push(note);
        fields.push({ question, select, note });
      }
      mayAccept = fields.length > 0;
      readResponse = () => {
        const answers = Object.create(null);
        for (const { question, select, note } of fields) {
          const values = [];
          if (select?.value) values.push(select.value);
          if (note.value?.trim()) values.push(note.value.trim());
          if (!values.length)
            throw new Error(
              text("请回答每个问题，或选择不回答", "Answer each question, or choose to skip"),
            );
          Object.defineProperty(answers, question.id, {
            value: { answers: values },
            enumerable: true,
          });
        }
        return { answers };
      };
      addButton(text("不回答", "Skip these questions"), { answers: {} }, true);
    } else if (request.method === "mcpServer/elicitation/request") {
      node.append(element("p", params.message || params.description || params.title || ""));
      if (params.serverName) node.append(element("small", params.serverName));
      if (["form", "openai/form", "openaiForm"].includes(params.mode)) {
        const schema = params.requestedSchema;
        mayAccept = supportedPromptSchema(schema);
        if (!mayAccept)
          node.append(
            element(
              "p",
              text(
                "暂不支持此表单格式，可取消或使用兼容客户端处理。",
                "This form is unsupported. Cancel or use a compatible client.",
              ),
            ),
          );
        else if (
          schema.type === "object" &&
          schema.properties &&
          Object.values(schema.properties).every(simpleField)
        ) {
          const readers = [];
          for (const [name, property] of Object.entries(schema.properties)) {
            const required = schema.required?.includes(name),
              label = element("label", (property.title || name) + (required ? " *" : ""));
            const field = inputFor(property, required);
            field.node.setAttribute("aria-label", property.title || name);
            label.append(field.node);
            if (property.description) label.append(element("small", property.description));
            form.append(label);
            controls.push(...field.inputs);
            readers.push([name, field.read]);
          }
          readResponse = () => {
            const content = Object.create(null);
            for (const [name, read] of readers) {
              const value = read();
              if (value !== undefined)
                Object.defineProperty(content, name, { value, enumerable: true });
            }
            return { action: "accept", content };
          };
        } else {
          const label = element("label", text("填写 JSON 内容", "JSON response")),
            editor = element("textarea");
          editor.value = "{}";
          editor.spellcheck = false;
          editor.setAttribute("aria-label", "JSON");
          const details = element("details");
          details.append(
            element("summary", text("查看表单结构", "Form schema")),
            element("pre", JSON.stringify(schema, null, 2)),
          );
          label.append(editor);
          form.append(details, label);
          controls.push(editor);
          readResponse = () => {
            try {
              return { action: "accept", content: JSON.parse(editor.value) };
            } catch {
              throw new Error(text("JSON 格式不正确", "Invalid JSON"));
            }
          };
        }
      } else if (params.mode === "url") {
        const url = safePromptUrl(params.url);
        mayAccept = !!url;
        if (url) {
          const link = element("a", text("打开验证页面", "Open verification page"));
          link.href = url;
          link.target = "_blank";
          link.rel = "noopener noreferrer";
          link.referrerPolicy = "no-referrer";
          node.append(link);
        } else
          node.append(
            element(
              "p",
              text(
                "验证地址不受支持，请取消此请求。",
                "Unsupported verification URL. Cancel this request.",
              ),
            ),
          );
        readResponse = () => ({ action: "accept" });
      } else {
        mayAccept = false;
        node.append(
          element(
            "p",
            text(
              "此请求需要兼容客户端或设备验证，不能在这里直接确认。",
              "This request needs a compatible client or device verification.",
            ),
          ),
        );
      }
      addButton(text("拒绝", "Decline"), { action: "decline" }, true);
      addButton(text("取消请求", "Cancel request"), { action: "cancel" });
    } else mayAccept = false;
    if (mayAccept && readResponse) {
      const accept = element(
        "button",
        params.mode === "url"
          ? text("我已在页面完成", "I completed the verification")
          : text("提交回答", "Submit answer"),
      );
      accept.type = "submit";
      controls.push(accept);
      form.append(accept);
      form.onsubmit = async (event) => {
        event.preventDefault();
        if (card.busy || (typeof form.reportValidity === "function" && !form.reportValidity()))
          return;
        try {
          await submit(readResponse());
        } catch (reason) {
          error.textContent = reason?.message || text("请检查输入内容", "Check the response");
        }
      };
    }
    form.append(error);
    node.append(form);
    return card;
  }
  function render() {
    if (!enabled()) {
      reset();
      return;
    }
    if (!tray) {
      tray = element("section", undefined, "server-request-tray");
      tray.id = "serverRequestTray";
      tray.setAttribute("aria-live", "polite");
      const container = getContainer();
      if (!container) return;
      container.before(tray);
    }
    const entries = snapshot.filter((entry) => entry.thread_id === getThreadId());
    const wanted = new Set(entries.map((entry) => entry.id));
    for (const id of cards.keys()) if (!wanted.has(id)) removeCard(id);
    for (const [index, entry] of entries.entries()) {
      let card = cards.get(entry.id);
      const signature = JSON.stringify(entry);
      if (card && card.signature !== signature && !card.busy) {
        removeCard(entry.id);
        card = null;
      }
      if (!card) {
        card = buildCard(entry);
        cards.set(entry.id, card);
      }
      if (tray.children[index] !== card.node)
        tray.insertBefore(card.node, tray.children[index] || null);
    }
    tray.hidden = entries.length === 0;
  }
  async function refresh() {
    if (!enabled()) return;
    const current = ++revision;
    try {
      const response = await command({ command: "server_requests" });
      if (current !== revision) return;
      snapshot = Array.isArray(response.requests) ? response.requests : [];
      render();
    } catch {
      /* Preserve a draft on transient HTTP errors; transport disconnect explicitly resets it. */
    }
  }
  return { refresh, render, reset };
}
