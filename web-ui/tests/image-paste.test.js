import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";

const source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
const start = source.indexOf('$("messageText").addEventListener("paste",');
const end = source.indexOf(
  'document.querySelector(".composer-shell").addEventListener("focusout"',
  start,
);
assert.ok(start >= 0 && end > start);

function paste(clipboardData, disabled = false) {
  let handler;
  const added = [];
  runInNewContext(source.slice(start, end), {
    $: (id) =>
      id === "messageText"
        ? {
            addEventListener: (_, fn) => {
              handler = fn;
            },
          }
        : { disabled },
    run: (action) => action(),
    addImages: (images) => added.push(...images),
    shouldAttachPastedText: () => false,
  });
  const event = {
    clipboardData,
    prevented: false,
    preventDefault() {
      this.prevented = true;
    },
  };
  handler(event);
  return { added, prevented: event.prevented };
}

test("screenshot is attached once when both clipboard APIs expose it", () => {
  const image = { type: "image/png", name: "screenshot.png" };
  const result = paste({
    files: [image],
    items: [
      {
        kind: "file",
        type: image.type,
        getAsFile: () => {
          throw new Error("duplicate read");
        },
      },
    ],
  });
  assert.deepEqual(result.added, [image]);
  assert.equal(result.prevented, true);
});

test("item fallback handles an empty FileList and null files", () => {
  const image = { type: "image/jpeg", name: "image.jpg" };
  const result = paste({
    files: [],
    items: [
      { kind: "file", type: image.type, getAsFile: () => image },
      { kind: "file", type: "image/png", getAsFile: () => null },
    ],
  });
  assert.deepEqual(result.added, [image]);
  assert.equal(result.prevented, true);
});

test("disabled attachment controls reject image paste", () => {
  const result = paste({ files: [{ type: "image/png" }] }, true);
  assert.deepEqual(result.added, []);
  assert.equal(result.prevented, false);
});

test("text and non-image files retain native paste behavior", () => {
  for (const data of [
    { files: [], getData: () => "hello" },
    { files: [{ type: "application/pdf" }] },
    undefined,
  ]) {
    const result = paste(data);
    assert.deepEqual(result.added, []);
    assert.equal(result.prevented, false);
  }
});
