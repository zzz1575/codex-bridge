import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

const source = readFileSync(new URL("../public/assets/passkey.js", import.meta.url), "utf8");

function client({ create, get }) {
  const requests = [];
  const window = { isSecureContext: true, PublicKeyCredential: class {} };
  const context = {
    window,
    PublicKeyCredential: window.PublicKeyCredential,
    navigator: { credentials: { create, get } },
    location: { hostname: "example.com", protocol: "https:" },
    document: { getElementById: () => null },
    atob,
    btoa,
    fetch: async (path, options) => {
      requests.push({ path, options });
      return {
        ok: true,
        json: async () =>
          path.endsWith("/start")
            ? {
                challengeId: "challenge-1",
                options: {
                  publicKey: {
                    challenge: "AQID",
                    user: { id: "BAUG", name: "codex" },
                    excludeCredentials: [{ id: "Bwg", type: "public-key" }],
                    allowCredentials: [{ id: "Bwg", type: "public-key" }],
                  },
                },
              }
            : { ok: true },
      };
    },
  };
  vm.runInNewContext(source, context);
  return { api: window.codexPasskeys, requests };
}

test("passkey registration converts browser binary data to WebAuthn JSON", async () => {
  const { api, requests } = client({
    create: async ({ publicKey }) => {
      assert.deepEqual(Array.from(publicKey.challenge), [1, 2, 3]);
      assert.deepEqual(Array.from(publicKey.user.id), [4, 5, 6]);
      assert.deepEqual(Array.from(publicKey.excludeCredentials[0].id), [7, 8]);
      return {
        id: "Bwg",
        rawId: Uint8Array.of(7, 8),
        type: "public-key",
        getClientExtensionResults: () => ({}),
        response: {
          attestationObject: Uint8Array.of(9, 10),
          clientDataJSON: Uint8Array.of(11, 12),
          getTransports: () => ["internal"],
        },
      };
    },
  });
  await api.register("Laptop", "secret");
  assert.equal(requests[0].path, "/api/passkeys/register/start");
  const body = JSON.parse(requests[1].options.body);
  assert.equal(body.challengeId, "challenge-1");
  assert.equal(body.credential.rawId, "Bwg");
  assert.equal(body.credential.response.attestationObject, "CQo");
  assert.deepEqual(body.credential.response.transports, ["internal"]);
});

test("passkey login sends assertion fields accepted by the server", async () => {
  const { api, requests } = client({
    get: async ({ publicKey }) => {
      assert.deepEqual(Array.from(publicKey.challenge), [1, 2, 3]);
      assert.deepEqual(Array.from(publicKey.allowCredentials[0].id), [7, 8]);
      return {
        id: "Bwg",
        rawId: Uint8Array.of(7, 8),
        type: "public-key",
        getClientExtensionResults: () => ({}),
        response: {
          authenticatorData: Uint8Array.of(1),
          clientDataJSON: Uint8Array.of(2),
          signature: Uint8Array.of(3),
          userHandle: null,
        },
      };
    },
  });
  await api.login();
  assert.equal(requests[0].path, "/api/passkeys/login/start");
  const body = JSON.parse(requests[1].options.body);
  assert.equal(body.credential.response.authenticatorData, "AQ");
  assert.equal(body.credential.response.clientDataJSON, "Ag");
  assert.equal(body.credential.response.signature, "Aw");
  assert.equal(body.credential.response.userHandle, null);
});
