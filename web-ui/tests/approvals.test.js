import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
const source = fs.readFileSync(new URL("../src/main.js", import.meta.url), "utf8");
const code = source.slice(
  source.indexOf("let approvalGeneration ="),
  source.indexOf("function approval(name)"),
);
for (const choice of ["approve", "decline"]) {
  test(`approval tray restores snapshot, scopes thread, and submits ${choice} only once`, async () => {
    class Element {
      children = [];
      dataset = {};
      hidden = false;
      append(...children) {
        this.children.push(...children);
      }
      replaceChildren(...children) {
        this.children = children;
      }
      setAttribute() {}
    }
    const nodes = {};
    let resolved = false;
    const calls = [];
    const request = {
      id: 123,
      thread_id: "current",
      request: {
        method: "item/commandExecution/requestApproval",
        params: { command: "echo OK", reason: "<img onerror=alert(1)>" },
      },
    };
    const context = vm.createContext({
      $: (id) => nodes[id],
      state: { current: { id: "current" } },
      demoMode: false,
      document: {
        createElement: () => new Element(),
        querySelector: () => ({
          before: (node) => {
            nodes[node.id] = node;
          },
        }),
      },
      getLanguage: () => "zh",
      tr: (key) => key,
      run: (fn) => fn(),
      command: async (req) => {
        calls.push(req);
        if (req.command === "pending")
          return {
            requests: resolved ? [] : [request, { ...request, id: 124, thread_id: "other" }],
          };
        resolved = true;
        await Promise.resolve();
        return {};
      },
    });
    vm.runInContext(code, context);
    await vm.runInContext("refreshApprovals()", context);
    const tray = nodes.approvalTray;
    assert.equal(tray.children.length, 1);
    const box = tray.children[0];
    assert.equal(box.children[1].textContent, "<img onerror=alert(1)>");
    await vm.runInContext("refreshApprovals()", context);
    assert.equal(tray.children[0], box);
    const button = box.children[choice === "approve" ? 3 : 4];
    const first = button.onclick();
    await button.onclick();
    await first;
    assert.equal(calls.filter((call) => call.command === choice).length, 1);
    assert.equal(tray.hidden, true);
  });
}
