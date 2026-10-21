/* Shared by the standalone login page and the bundled settings view. */
(() => {
  const decode = (value) => {
    const text = value.replace(/-/g, "+").replace(/_/g, "/");
    const bytes = atob(text.padEnd(Math.ceil(text.length / 4) * 4, "="));
    return Uint8Array.from(bytes, (char) => char.charCodeAt(0));
  };
  const encode = (buffer) => {
    const bytes = new Uint8Array(buffer);
    let text = "";
    for (const byte of bytes) text += String.fromCharCode(byte);
    return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  };
  const post = async (path, body = {}) => {
    const response = await fetch(path, {
      method: "POST",
      credentials: "same-origin",
      cache: "no-store",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok)
      throw new Error(result.error || `Passkey request failed (${response.status})`);
    return result;
  };
  const registrationOptions = (options) => {
    if (PublicKeyCredential.parseCreationOptionsFromJSON)
      return { publicKey: PublicKeyCredential.parseCreationOptionsFromJSON(options.publicKey) };
    const key = options.publicKey;
    return {
      publicKey: {
        ...key,
        challenge: decode(key.challenge),
        user: { ...key.user, id: decode(key.user.id) },
        excludeCredentials: (key.excludeCredentials || []).map((item) => ({
          ...item,
          id: decode(item.id),
        })),
      },
    };
  };
  const authenticationOptions = (options) => {
    if (PublicKeyCredential.parseRequestOptionsFromJSON)
      return { publicKey: PublicKeyCredential.parseRequestOptionsFromJSON(options.publicKey) };
    const key = options.publicKey;
    return {
      publicKey: {
        ...key,
        challenge: decode(key.challenge),
        allowCredentials: (key.allowCredentials || []).map((item) => ({
          ...item,
          id: decode(item.id),
        })),
      },
    };
  };
  const credentialJSON = (credential) => {
    if (credential.toJSON) return credential.toJSON();
    const response = credential.response;
    const common = {
      id: credential.id,
      rawId: encode(credential.rawId),
      type: credential.type,
      clientExtensionResults: credential.getClientExtensionResults(),
    };
    if (response.attestationObject) {
      return {
        ...common,
        response: {
          attestationObject: encode(response.attestationObject),
          clientDataJSON: encode(response.clientDataJSON),
          transports: response.getTransports?.() || [],
        },
      };
    }
    return {
      ...common,
      response: {
        authenticatorData: encode(response.authenticatorData),
        clientDataJSON: encode(response.clientDataJSON),
        signature: encode(response.signature),
        userHandle: response.userHandle ? encode(response.userHandle) : null,
      },
    };
  };
  const supported = () =>
    window.isSecureContext &&
    (location.hostname === "localhost" ||
      (location.protocol === "https:" &&
        !/^\d+\.\d+\.\d+\.\d+$/.test(location.hostname) &&
        !location.hostname.includes(":"))) &&
    !!window.PublicKeyCredential &&
    !!navigator.credentials;
  window.codexPasskeys = {
    supported,
    async available() {
      const response = await fetch("/api/passkeys/available", {
        cache: "no-store",
        credentials: "same-origin",
      });
      return response.ok && (await response.json()).available;
    },
    async list() {
      const response = await fetch("/api/passkeys", {
        cache: "no-store",
        credentials: "same-origin",
      });
      if (!response.ok) throw new Error(`Could not load passkeys (${response.status})`);
      return (await response.json()).passkeys;
    },
    async register(name, password) {
      const start = await post("/api/passkeys/register/start", { name, password });
      const credential = await navigator.credentials.create(registrationOptions(start.options));
      if (!credential) throw new Error("Passkey registration was cancelled");
      return post("/api/passkeys/register/finish", {
        challengeId: start.challengeId,
        credential: credentialJSON(credential),
      });
    },
    async login() {
      const start = await post("/api/passkeys/login/start");
      const credential = await navigator.credentials.get(authenticationOptions(start.options));
      if (!credential) throw new Error("Passkey login was cancelled");
      return post("/api/passkeys/login/finish", {
        challengeId: start.challengeId,
        credential: credentialJSON(credential),
      });
    },
    async remove(id, password) {
      return post("/api/passkeys/remove", { id, password });
    },
  };

  const button = document.getElementById("passkeyLogin");
  if (button && supported()) {
    window.codexPasskeys
      .available()
      .then((available) => {
        button.hidden = !available;
      })
      .catch(() => {});
    button.addEventListener("click", async () => {
      const error = document.getElementById("passkeyError");
      error.hidden = true;
      button.disabled = true;
      try {
        await window.codexPasskeys.login();
        window.location.assign("/");
      } catch (cause) {
        error.textContent = cause.message;
        error.hidden = false;
      } finally {
        button.disabled = false;
      }
    });
  }
})();
