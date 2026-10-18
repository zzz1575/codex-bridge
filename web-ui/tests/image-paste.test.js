import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { runInNewContext } from "node:vm";
import { imageFileFromSource, pastedFiles, richClipboardParts } from "../src/clipboard-images.js";

const source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
const start = source.indexOf('$("messageText").addEventListener("paste",');
const end = source.indexOf(
  'document.querySelector(".composer-shell").addEventListener("focusout"',
  start,
);
assert.ok(start >= 0 && end > start);

async function paste(clipboardData, { disabled = false, navigate = false } = {}) {
  if (clipboardData && !clipboardData.getData)
    clipboardData = { getData: () => "", ...clipboardData };
  let handler;
  const added = [],
    texts = [],
    pending = [];
  const state = { composerAttachments: [] };
  runInNewContext(source.slice(start, end), {
    $: (id) =>
      id === "messageText"
        ? {
            addEventListener: (_, fn) => {
              handler = fn;
            },
          }
        : { disabled },
    state,
    run: (action) => pending.push(Promise.resolve().then(action)),
    addFiles: (files) => added.push(...files),
    pasteComposerText: (text) => {
      if (text) texts.push(text);
    },
    pastedFiles,
    richClipboardParts: (html, plain) =>
      richClipboardParts(html, plain, () => ({
        querySelectorAll: () =>
          [...html.matchAll(/<img src="([^"]+)"/g)].map((match) => ({
            getAttribute: () => match[1],
          })),
        body: { textContent: "" },
      })),
    imageFileFromSource: async (url) => {
      const image = await imageFileFromSource(url);
      if (navigate) state.composerAttachments = [];
      return image;
    },
    navigator: {},
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
  await Promise.all(pending);
  return { added, texts, prevented: event.prevented };
}

test("screenshot is uploaded once when both clipboard APIs expose it", async () => {
  const image = { type: "image/png", name: "screenshot.png" };
  const result = await paste({
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

test("item fallback handles an empty FileList and null files", async () => {
  const image = { type: "image/jpeg", name: "image.jpg" };
  const result = await paste({
    files: [],
    items: [
      { kind: "file", type: image.type, getAsFile: () => image },
      { kind: "file", type: "image/png", getAsFile: () => null },
    ],
  });
  assert.deepEqual(result.added, [image]);
  assert.equal(result.prevented, true);
});

test("disabled attachment controls reject image paste", async () => {
  const result = await paste({ files: [{ type: "image/png" }] }, { disabled: true });
  assert.deepEqual(result.added, []);
  assert.equal(result.prevented, false);
});

test("plain text keeps native paste while the new file pipeline accepts non-image files", async () => {
  for (const data of [{ files: [], getData: () => "hello" }, undefined]) {
    const result = await paste(data);
    assert.deepEqual(result.added, []);
    assert.equal(result.prevented, false);
  }
  const file = { type: "application/pdf", name: "fixture.pdf" };
  const result = await paste({ files: [file] });
  assert.deepEqual(result.added, [file]);
  assert.equal(result.prevented, true);
});

test("rich copied fragments retain their text and upload inline images", async () => {
  const result = await paste({
    files: [],
    getData: (format) =>
      format === "text/html"
        ? '<p>Copied note<img src="data:image/png;base64,YWJj"></p>'
        : "Copied note",
  });
  assert.deepEqual(result.texts, ["Copied note"]);
  assert.equal(result.added.length, 1);
  assert.equal(result.added[0].type, "image/png");
  assert.equal(result.prevented, true);
});

test("late rich image decoding cannot add attachments to a different conversation", async () => {
  const result = await paste(
    {
      files: [],
      getData: (format) => (format === "text/html" ? '<img src="data:image/png;base64,YWJj">' : ""),
    },
    { navigate: true },
  );
  assert.deepEqual(result.added, []);
  assert.equal(result.prevented, true);
});
