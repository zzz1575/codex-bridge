import assert from "node:assert/strict";
import test from "node:test";

import { sendFileUpload } from "../src/file-upload.js";

function requestFixture() {
  return {
    upload: {},
    headers: new Map(),
    open(method, url) {
      this.method = method;
      this.url = url;
    },
    setRequestHeader(name, value) {
      this.headers.set(name, value);
    },
    send(file) {
      this.file = file;
    },
  };
}

test("file upload reports bytes and resolves only after the server responds", async () => {
  const request = requestFixture();
  const file = { name: "archive.zip", size: 100 };
  const progress = [];
  let settled = false;
  const upload = sendFileUpload(
    file,
    "YXJjaGl2ZS56aXA=",
    (value) => progress.push(value),
    () => request,
  );
  upload.then(() => {
    settled = true;
  });

  request.upload.onprogress({ lengthComputable: true, loaded: 50, total: 100 });
  request.upload.onprogress({ lengthComputable: true, loaded: 100, total: 100 });
  await Promise.resolve();
  assert.deepEqual(progress, [50, 99]);
  assert.equal(settled, false);
  assert.equal(request.file, file);
  assert.equal(request.url, "/api/attachment-upload");
  assert.equal(request.headers.get("X-Attachment-Name"), "YXJjaGl2ZS56aXA=");

  request.status = 200;
  request.responseText = '{"id":"uploaded","name":"archive.zip","size":100}';
  request.onload();
  assert.deepEqual(await upload, { id: "uploaded", name: "archive.zip", size: 100 });
});

test("file upload surfaces server failures with status", async () => {
  const request = requestFixture();
  const upload = sendFileUpload(
    { size: 1 },
    "YQ==",
    () => {},
    () => request,
  );
  request.status = 401;
  request.responseText = "unauthorized";
  request.onload();
  await assert.rejects(upload, { message: "unauthorized", status: 401 });
});
