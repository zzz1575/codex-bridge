import { t } from "./i18n.js";

const pageTitles = {
  general: "settingsGeneral",
  account: "settingsAccount",
  usage: "settingsUsage",
  connection: "settingsConnection",
};

export function initSettingsPanel() {
  const button = document.getElementById("settingsBtn");
  const overlay = document.getElementById("settingsOverlay");
  const panel = document.getElementById("settingsPanel");
  const title = document.getElementById("settingsTitle");
  const back = document.getElementById("settingsBackBtn");
  const closeButton = document.getElementById("settingsCloseBtn");
  const navButtons = [...panel.querySelectorAll("[data-settings-nav]")];
  const pages = [...panel.querySelectorAll("[data-settings-page]")];
  const mobile = window.matchMedia("(max-width: 800px)");
  let selected = "general";
  let opener = null;
  let sidebarWasOpen = false;

  const updateTitle = (key) => {
    title.dataset.i18n = key;
    title.textContent = t(key);
  };
  const selectPage = (page) => {
    if (!pageTitles[page]) return;
    selected = page;
    for (const item of pages) item.hidden = item.dataset.settingsPage !== page;
    for (const item of navButtons) {
      if (item.dataset.settingsNav === page) item.setAttribute("aria-current", "page");
      else item.removeAttribute("aria-current");
    }
    panel.dataset.mobileDetail = "true";
    updateTitle(pageTitles[page]);
    panel.dispatchEvent(new CustomEvent("settings-page-change", { detail: { page } }));
    if (mobile.matches) back.focus({ preventScroll: true });
  };
  const showCategories = () => {
    panel.dataset.mobileDetail = "false";
    updateTitle("settings");
    navButtons
      .find((item) => item.dataset.settingsNav === selected && !item.hidden)
      ?.focus({
        preventScroll: true,
      });
  };
  const close = () => {
    if (panel.hidden) return;
    panel.hidden = true;
    overlay.hidden = true;
    button.classList.remove("active");
    button.setAttribute("aria-expanded", "false");
    if (sidebarWasOpen) {
      document.getElementById("sidebar")?.classList.add("open");
      document.getElementById("scrim")?.classList.add("show");
    }
    panel.dispatchEvent(new Event("settings-close"));
    opener?.focus({ preventScroll: true });
  };
  const open = () => {
    opener = document.activeElement instanceof HTMLElement ? document.activeElement : button;
    sidebarWasOpen = document.getElementById("sidebar")?.classList.contains("open") ?? false;
    overlay.hidden = false;
    panel.hidden = false;
    button.classList.add("active");
    button.setAttribute("aria-expanded", "true");
    document.getElementById("sidebar")?.classList.remove("open");
    document.getElementById("scrim")?.classList.remove("show");
    panel.dispatchEvent(new Event("settings-open"));
    if (mobile.matches) showCategories();
    else {
      selectPage(selected);
      closeButton.focus({ preventScroll: true });
    }
  };

  button.addEventListener("click", () => (panel.hidden ? open() : close()));
  closeButton.addEventListener("click", close);
  back.addEventListener("click", showCategories);
  overlay.addEventListener("click", (event) => {
    if (event.target === overlay) close();
  });
  for (const item of navButtons) {
    item.addEventListener("click", () => selectPage(item.dataset.settingsNav));
  }
  document.addEventListener(
    "keydown",
    (event) => {
      if (panel.hidden) return;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopImmediatePropagation();
        close();
      } else if (event.key === "Tab") {
        const focusable = [
          ...panel.querySelectorAll("button, input, select, summary, [tabindex]"),
        ].filter(
          (element) =>
            !element.closest("[hidden]") &&
            !element.disabled &&
            element.getClientRects().length &&
            getComputedStyle(element).visibility !== "hidden",
        );
        if (!focusable.length) return;
        const edge = event.shiftKey ? focusable[0] : focusable.at(-1);
        if (document.activeElement !== edge) return;
        event.preventDefault();
        (event.shiftKey ? focusable.at(-1) : focusable[0]).focus();
      }
    },
    true,
  );
}
