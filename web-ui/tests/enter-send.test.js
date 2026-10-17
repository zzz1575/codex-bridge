import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";
const source = fs.readFileSync(new URL("../src/main.js", import.meta.url), "utf8");
for (const [input, end, button] of [
  ["messageText", "threadTitle", "submitBtn"],
  ["temporaryText", "temporaryText", "temporarySendBtn"],
]) {
  test(`${input}: Enter sends, newline and IME are preserved`, () => {
    let sent = 0;
    const elements = {
      [input]: {},
      [button]: { click: () => sent++ },
      composerCommandPicker: { hidden: true },
    };
    const start = source.indexOf(`$("${input}").onkeydown =`);
    const finish = source.indexOf(
      input === "messageText" ? `$("${end}").onclick` : `$("${end}").oninput`,
      start,
    );
    vm.runInNewContext(source.slice(start, finish), { $: (id) => elements[id] });
    function key(extra = {}) {
      let prevented = false;
      elements[input].onkeydown({
        key: "Enter",
        preventDefault() {
          prevented = true;
        },
        ...extra,
      });
      return prevented;
    }
    assert.equal(key(), true);
    assert.equal(sent, 1);
    for (const extra of [
      { shiftKey: true },
      { altKey: true },
      { isComposing: true },
      { keyCode: 229 },
      { key: "a" },
    ])
      assert.equal(key(extra), false);
    assert.equal(sent, 1);
    assert.equal(key({ ctrlKey: true }), true);
    assert.equal(sent, 2);
  });
}
