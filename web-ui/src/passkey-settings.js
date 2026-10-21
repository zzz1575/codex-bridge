import { getLanguage } from "./i18n.js";

export function initPasskeySettings(demoMode) {
  const card = document.getElementById("passkeySettings");
  if (!card) return;
  const api = window.codexPasskeys;
  if (demoMode || !api?.supported()) {
    card.hidden = true;
    document.querySelector('[data-settings-nav="account"]')?.setAttribute("hidden", "");
    return;
  }
  const list = document.getElementById("passkeyList");
  const status = document.getElementById("passkeyStatus");
  const name = document.getElementById("passkeyName");
  const password = document.getElementById("passkeyPassword");
  const add = document.getElementById("passkeyAdd");
  const zh = () => getLanguage() === "zh";
  const show = (message) => {
    status.textContent = message;
  };
  const render = (items) => {
    list.replaceChildren();
    if (!items.length) {
      list.textContent = zh() ? "尚未添加 passkey" : "No passkeys added";
      return;
    }
    for (const item of items) {
      const row = document.createElement("div");
      row.className = "passkey-row";
      const label = document.createElement("span");
      label.textContent = `${item.name} · ${item.rpId}`;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = zh() ? "移除" : "Remove";
      remove.addEventListener("click", async () => {
        if (!password.value) {
          show(zh() ? "请输入当前密码" : "Enter your current password");
          return;
        }
        remove.disabled = true;
        try {
          const result = await api.remove(item.id, password.value);
          password.value = "";
          render(result.passkeys);
          show(zh() ? "Passkey 已移除" : "Passkey removed");
        } catch (error) {
          show(error.message);
        } finally {
          remove.disabled = false;
        }
      });
      row.append(label, remove);
      list.append(row);
    }
  };
  const refresh = async () => {
    try {
      render(await api.list());
    } catch (error) {
      show(error.message);
    }
  };
  add.addEventListener("click", async () => {
    if (!password.value) {
      show(zh() ? "请输入当前密码" : "Enter your current password");
      return;
    }
    add.disabled = true;
    show(zh() ? "等待验证设备…" : "Waiting for your device…");
    try {
      const result = await api.register(name.value || "My passkey", password.value);
      password.value = "";
      name.value = "";
      render(result.passkeys);
      show(zh() ? "Passkey 已添加" : "Passkey added");
    } catch (error) {
      show(error.message);
    } finally {
      add.disabled = false;
    }
  });
  document.getElementById("settingsPanel")?.addEventListener("settings-page-change", (event) => {
    if (event.detail.page === "account") void refresh();
  });
}
