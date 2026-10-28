export function moveSession(ids, id, offset) {
  const from = ids.indexOf(id),
    to = from + offset;
  if (from < 0 || to < 0 || to >= ids.length) return [...ids];
  const result = [...ids];
  result.splice(to, 0, result.splice(from, 1)[0]);
  return result;
}

export function dropSession(ids, id, target, after = false) {
  if (id === target || !ids.includes(id) || !ids.includes(target)) return [...ids];
  const result = ids.filter((candidate) => candidate !== id);
  result.splice(result.indexOf(target) + Number(after), 0, id);
  return result;
}

function sessionNumber(title) {
  const match = String(title || "")
    .normalize("NFKC")
    .match(/^\s*(\d+)(?:\s*[|:、.\-—]|\s*号(?:\s|$)|\s|$)/u);
  const number = match ? Number(match[1]) : NaN;
  return Number.isSafeInteger(number) ? number : Number.MAX_SAFE_INTEGER;
}

export function arrangeSessions(ids, threads, mode) {
  const positions = new Map(ids.map((id, index) => [id, index]));
  return [...ids].sort((left, right) => {
    const a = threads.get(left),
      b = threads.get(right);
    const difference =
      mode === "number"
        ? sessionNumber(a?.title) - sessionNumber(b?.title)
        : (Number(b?.updated_at_ms) || 0) - (Number(a?.updated_at_ms) || 0);
    return difference || positions.get(left) - positions.get(right);
  });
}

export function sameSessionOrder(left, right) {
  return (
    Array.isArray(left) &&
    Array.isArray(right) &&
    left.length === right.length &&
    left.every((id, index) => id === right[index])
  );
}

export async function showSessionSortDialog({ command, tr, onSaved, onFailure, notify }) {
  const existing = document.getElementById("sessionSortDialog");
  if (existing) {
    existing.focus();
    return;
  }
  const trigger = document.activeElement;
  const dialog = document.createElement("dialog");
  dialog.id = "sessionSortDialog";
  dialog.className = "session-sort-dialog";
  dialog.setAttribute("aria-labelledby", "sessionSortTitle");
  dialog.innerHTML = `<header class="session-sort-head"><h2 id="sessionSortTitle"></h2><button type="button" class="session-sort-close">×</button></header>
    <p class="session-sort-hint"></p><div class="session-sort-toolbar"><button type="button" data-sort="number"></button><button type="button" data-sort="recent"></button><button type="button" class="session-sort-reload"></button></div>
    <p class="session-sort-status" aria-live="polite" role="status"></p><ol class="session-sort-list"></ol>
    <footer class="session-sort-footer"><button type="button" class="session-sort-cancel"></button><button type="button" class="session-sort-save primary"></button></footer>`;
  const get = (selector) => dialog.querySelector(selector);
  get("h2").textContent = tr("sessionSort");
  get(".session-sort-hint").textContent = tr("sessionSortHint");
  get(".session-sort-close").setAttribute("aria-label", tr("close"));
  get('[data-sort="number"]').textContent = tr("sessionSortNumber");
  get('[data-sort="recent"]').textContent = tr("sessionSortRecent");
  get(".session-sort-reload").textContent = tr("refresh");
  get(".session-sort-cancel").textContent = tr("cancel");
  const status = get(".session-sort-status"),
    list = get(".session-sort-list"),
    save = get(".session-sort-save");
  let expected = [],
    draft = [],
    threads = new Map(),
    busy = false,
    loaded = false,
    dragged = null;
  const setBusy = (value) => {
    busy = value;
    for (const button of dialog.querySelectorAll("button")) button.disabled = value;
    save.textContent = tr(value ? "sessionSortSaving" : "sessionSortSave");
    save.disabled = value || !loaded || sameSessionOrder(draft, expected);
    dialog.setAttribute("aria-busy", String(value));
  };
  const message = (text, error = false) => {
    status.textContent = text;
    status.classList.toggle("error", error);
    status.setAttribute("role", error ? "alert" : "status");
  };
  const render = (focusId = null, focusAction = "up") => {
    const scrollTop = list.scrollTop;
    list.replaceChildren();
    for (const [index, id] of draft.entries()) {
      const row = document.createElement("li");
      row.className = "session-sort-row";
      row.dataset.id = id;
      const handle = document.createElement("button");
      handle.type = "button";
      handle.className = "session-sort-handle";
      handle.textContent = "⠿";
      handle.draggable = !busy;
      handle.title = tr("sessionSortDrag");
      handle.setAttribute("aria-label", tr("sessionSortDrag"));
      handle.ondragstart = (event) => {
        if (busy) {
          event.preventDefault();
          return;
        }
        dragged = id;
        event.dataTransfer.effectAllowed = "move";
        event.dataTransfer.setData("text/plain", id);
      };
      handle.ondragend = () => {
        dragged = null;
        for (const item of list.children) item.classList.remove("drop-target");
      };
      const rank = document.createElement("span");
      rank.className = "session-sort-rank";
      rank.textContent = String(index + 1);
      const title = document.createElement("span");
      title.className = "session-sort-title";
      title.textContent = threads.get(id)?.title || id;
      const up = document.createElement("button"),
        down = document.createElement("button");
      for (const [button, direction, offset] of [
        [up, "up", -1],
        [down, "down", 1],
      ]) {
        button.type = "button";
        button.dataset.action = direction;
        button.textContent = offset < 0 ? "↑" : "↓";
        button.setAttribute(
          "aria-label",
          tr(offset < 0 ? "sessionSortUp" : "sessionSortDown", { title: title.textContent }),
        );
        button.disabled = busy || (offset < 0 ? index === 0 : index === draft.length - 1);
        button.onclick = () => {
          draft = moveSession(draft, id, offset);
          render(id, direction);
        };
      }
      row.ondragover = (event) => {
        if (dragged && !busy) {
          event.preventDefault();
          event.dataTransfer.dropEffect = "move";
          row.classList.add("drop-target");
        }
      };
      row.ondragleave = () => row.classList.remove("drop-target");
      row.ondrop = (event) => {
        event.preventDefault();
        if (!dragged || busy) return;
        const after =
          event.clientY > row.getBoundingClientRect().top + row.getBoundingClientRect().height / 2;
        draft = dropSession(draft, dragged, id, after);
        dragged = null;
        render(id);
      };
      row.append(handle, rank, title, up, down);
      list.appendChild(row);
    }
    list.scrollTop = scrollTop;
    save.disabled = busy || !loaded || sameSessionOrder(draft, expected);
    if (focusId) {
      const row = [...list.children].find((item) => item.dataset.id === focusId);
      const target =
        row?.querySelector(`[data-action="${focusAction}"]:not(:disabled)`) ||
        row?.querySelector(".session-sort-handle");
      target?.focus({ preventScroll: true });
    }
  };
  const reload = async () => {
    setBusy(true);
    message(tr("sessionSortLoading"));
    try {
      const snapshot = await command({ command: "thread_pins" }, false);
      if (!dialog.open) return;
      if (
        !snapshot.available ||
        !Array.isArray(snapshot.thread_ids) ||
        new Set(snapshot.thread_ids).size !== snapshot.thread_ids.length
      )
        throw new Error("unavailable");
      expected = [...snapshot.thread_ids];
      draft = [...expected];
      threads = new Map((snapshot.threads || []).map((thread) => [thread.id, thread]));
      loaded = true;
      message(
        draft.length ? tr("sessionSortCount", { count: draft.length }) : tr("sessionSortEmpty"),
      );
      render();
    } catch {
      if (dialog.open) message(tr("sessionSortLoadError"), true);
    } finally {
      if (dialog.open) {
        setBusy(false);
        render();
      }
    }
  };
  for (const button of dialog.querySelectorAll("[data-sort]"))
    button.onclick = () => {
      draft = arrangeSessions(draft, threads, button.dataset.sort);
      render();
    };
  get(".session-sort-reload").onclick = reload;
  const close = () => {
    if (!busy) dialog.close();
  };
  get(".session-sort-close").onclick = close;
  get(".session-sort-cancel").onclick = close;
  dialog.addEventListener("cancel", (event) => {
    if (busy) event.preventDefault();
  });
  save.onclick = async () => {
    if (busy || !loaded || sameSessionOrder(draft, expected)) return;
    setBusy(true);
    message(tr("sessionSortSaving"));
    try {
      const result = await command(
        { command: "thread_reorder", thread_ids: [...draft], expected_thread_ids: [...expected] },
        false,
      );
      if (!result.verified || !sameSessionOrder(result.thread_ids, draft))
        throw new Error("unverified");
      expected = [...result.thread_ids];
      await onSaved();
      notify(tr("sessionSortSaved"));
      dialog.close();
    } catch {
      if (dialog.open) {
        message(tr("sessionSortSaveError"), true);
        try {
          await onFailure?.();
        } catch {
          /* Keep the editable draft and error visible. */
        }
      }
    } finally {
      if (dialog.open) {
        setBusy(false);
        render();
      }
    }
  };
  dialog.addEventListener(
    "close",
    () => {
      dialog.remove();
      if (trigger?.isConnected) trigger.focus({ preventScroll: true });
    },
    { once: true },
  );
  document.body.appendChild(dialog);
  dialog.showModal();
  await reload();
}
