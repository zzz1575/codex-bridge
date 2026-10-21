use std::collections::HashMap;
use std::fs::{self, OpenOptions};
use std::io::{ErrorKind, Write};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{bail, Context, Result};
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use base64::Engine;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use webauthn_rs::prelude::{
    Passkey, PasskeyAuthentication, PasskeyRegistration, PublicKeyCredential,
    RegisterPublicKeyCredential, Url, Uuid, WebauthnBuilder,
};

const CHALLENGE_TTL: Duration = Duration::from_secs(300);
const MAX_CHALLENGES: usize = 32;
const MAX_PASSKEYS: usize = 16;

#[derive(Clone, Serialize, Deserialize)]
pub struct SavedPasskey {
    pub id: String,
    pub name: String,
    pub rp_id: String,
    pub created_at: u64,
    passkey: Passkey,
}

#[derive(Clone, Serialize, Deserialize)]
struct PasskeyFile {
    username: String,
    user_id: Uuid,
    credentials: Vec<SavedPasskey>,
}

struct Registration {
    origin: String,
    name: String,
    state: PasskeyRegistration,
    expires: Instant,
}

struct Authentication {
    origin: String,
    state: PasskeyAuthentication,
    expires: Instant,
}

struct Inner {
    file: PasskeyFile,
    registrations: HashMap<String, Registration>,
    authentications: HashMap<String, Authentication>,
}

pub struct PasskeyManager {
    path: PathBuf,
    inner: Mutex<Inner>,
}

fn webauthn(origin: &str) -> Result<webauthn_rs::Webauthn> {
    let url = Url::parse(origin).context("invalid passkey origin")?;
    let host = url.host_str().context("passkey origin has no hostname")?;
    if !(url.scheme() == "https" || (url.scheme() == "http" && host == "localhost"))
        || host.parse::<std::net::IpAddr>().is_ok()
        || url.path() != "/"
        || url.query().is_some()
        || url.fragment().is_some()
    {
        bail!("passkeys require HTTPS with a domain name, or localhost");
    }
    Ok(WebauthnBuilder::new(host, &url)
        .context("invalid passkey relying party")?
        .rp_name("Codex Bridge")
        .build()
        .context("could not configure passkeys")?)
}

fn challenge_id() -> Result<String> {
    let mut bytes = [0_u8; 24];
    getrandom::fill(&mut bytes).context("could not generate passkey challenge ID")?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

impl PasskeyManager {
    pub fn new(path: PathBuf, username: &str) -> Result<Self> {
        let file = match fs::symlink_metadata(&path) {
            Ok(metadata) => {
                if !metadata.file_type().is_file()
                    || metadata.uid() != unsafe { libc::geteuid() }
                    || metadata.mode() & 0o077 != 0
                    || metadata.len() > 1024 * 1024
                {
                    bail!("passkey store is not a private regular file");
                }
                serde_json::from_slice::<PasskeyFile>(&fs::read(&path)?)
                    .context("could not read passkey store")?
            }
            Err(error) if error.kind() == ErrorKind::NotFound => PasskeyFile {
                username: username.to_owned(),
                user_id: Uuid::new_v4(),
                credentials: Vec::new(),
            },
            Err(error) => return Err(error).context("could not inspect passkey store"),
        };
        if file.username != username || file.credentials.len() > MAX_PASSKEYS {
            bail!("passkey store does not match this Web UI account");
        }
        Ok(Self {
            path,
            inner: Mutex::new(Inner {
                file,
                registrations: HashMap::new(),
                authentications: HashMap::new(),
            }),
        })
    }

    pub fn list(&self) -> Vec<Value> {
        let inner = self.inner.lock().unwrap();
        inner
            .file
            .credentials
            .iter()
            .map(|item| {
                serde_json::json!({
                    "id": item.id, "name": item.name, "rpId": item.rp_id,
                    "createdAt": item.created_at,
                })
            })
            .collect()
    }

    pub fn available(&self, origin: &str) -> bool {
        let Ok(url) = Url::parse(origin) else {
            return false;
        };
        let Some(host) = url.host_str() else {
            return false;
        };
        webauthn(origin).is_ok()
            && self
                .inner
                .lock()
                .unwrap()
                .file
                .credentials
                .iter()
                .any(|item| item.rp_id == host)
    }

    pub fn begin_registration(&self, origin: &str, name: &str) -> Result<Value> {
        let webauthn = webauthn(origin)?;
        let name = name.trim();
        if name.is_empty() || name.chars().count() > 64 {
            bail!("passkey name must be 1–64 characters");
        }
        let mut inner = self.inner.lock().unwrap();
        inner
            .registrations
            .retain(|_, entry| entry.expires > Instant::now());
        if inner.registrations.len() >= MAX_CHALLENGES
            || inner.file.credentials.len() >= MAX_PASSKEYS
        {
            bail!("passkey registration limit reached");
        }
        let host = Url::parse(origin)?.host_str().unwrap().to_owned();
        let exclude = inner
            .file
            .credentials
            .iter()
            .filter(|item| item.rp_id == host)
            .map(|item| item.passkey.cred_id().clone())
            .collect::<Vec<_>>();
        let (options, state) = webauthn
            .start_passkey_registration(
                inner.file.user_id,
                &inner.file.username,
                &inner.file.username,
                if exclude.is_empty() {
                    None
                } else {
                    Some(exclude)
                },
            )
            .context("could not start passkey registration")?;
        let id = challenge_id()?;
        inner.registrations.insert(
            id.clone(),
            Registration {
                origin: origin.to_owned(),
                name: name.to_owned(),
                state,
                expires: Instant::now() + CHALLENGE_TTL,
            },
        );
        Ok(serde_json::json!({"challengeId": id, "options": options}))
    }

    pub fn finish_registration(&self, origin: &str, id: &str, response: Value) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        let entry = inner
            .registrations
            .remove(id)
            .context("registration challenge expired")?;
        if entry.expires <= Instant::now() || entry.origin != origin {
            bail!("registration challenge expired");
        }
        let credential: RegisterPublicKeyCredential =
            serde_json::from_value(response).context("invalid registration response")?;
        let passkey = webauthn(origin)?
            .finish_passkey_registration(&credential, &entry.state)
            .context("passkey registration verification failed")?;
        let key_id = URL_SAFE_NO_PAD.encode(passkey.cred_id());
        if inner.file.credentials.iter().any(|item| item.id == key_id) {
            bail!("passkey is already registered");
        }
        if inner.file.credentials.len() >= MAX_PASSKEYS {
            bail!("passkey registration limit reached");
        }
        let mut updated = inner.file.clone();
        updated.credentials.push(SavedPasskey {
            id: key_id,
            name: entry.name,
            rp_id: Url::parse(origin)?.host_str().unwrap().to_owned(),
            created_at: SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs(),
            passkey,
        });
        self.save(&updated)?;
        inner.file = updated;
        Ok(())
    }

    pub fn begin_authentication(&self, origin: &str) -> Result<Value> {
        let webauthn = webauthn(origin)?;
        let host = Url::parse(origin)?.host_str().unwrap().to_owned();
        let mut inner = self.inner.lock().unwrap();
        inner
            .authentications
            .retain(|_, entry| entry.expires > Instant::now());
        if inner.authentications.len() >= MAX_CHALLENGES {
            bail!("too many passkey requests");
        }
        let credentials = inner
            .file
            .credentials
            .iter()
            .filter(|item| item.rp_id == host)
            .map(|item| item.passkey.clone())
            .collect::<Vec<_>>();
        if credentials.is_empty() {
            bail!("no passkey is registered for this site");
        }
        let (options, state) = webauthn
            .start_passkey_authentication(&credentials)
            .context("could not start passkey login")?;
        let id = challenge_id()?;
        inner.authentications.insert(
            id.clone(),
            Authentication {
                origin: origin.to_owned(),
                state,
                expires: Instant::now() + CHALLENGE_TTL,
            },
        );
        Ok(serde_json::json!({"challengeId": id, "options": options}))
    }

    pub fn finish_authentication(&self, origin: &str, id: &str, response: Value) -> Result<()> {
        let mut inner = self.inner.lock().unwrap();
        let entry = inner
            .authentications
            .remove(id)
            .context("login challenge expired")?;
        if entry.expires <= Instant::now() || entry.origin != origin {
            bail!("login challenge expired");
        }
        let credential: PublicKeyCredential =
            serde_json::from_value(response).context("invalid login response")?;
        let result = webauthn(origin)?
            .finish_passkey_authentication(&credential, &entry.state)
            .context("passkey verification failed")?;
        let mut updated = inner.file.clone();
        let saved = updated
            .credentials
            .iter_mut()
            .find(|item| item.passkey.cred_id() == result.cred_id())
            .context("unrecognized passkey")?;
        if result.needs_update() {
            saved.passkey.update_credential(&result);
            self.save(&updated)?;
            inner.file = updated;
        }
        Ok(())
    }

    pub fn remove(&self, id: &str) -> Result<bool> {
        let mut inner = self.inner.lock().unwrap();
        let mut updated = inner.file.clone();
        updated.credentials.retain(|item| item.id != id);
        if updated.credentials.len() == inner.file.credentials.len() {
            return Ok(false);
        }
        self.save(&updated)?;
        inner.file = updated;
        Ok(true)
    }

    fn save(&self, data: &PasskeyFile) -> Result<()> {
        let parent = self
            .path
            .parent()
            .context("passkey store has no directory")?;
        let tmp = parent.join(format!(".passkeys-{}.tmp", challenge_id()?));
        let result = (|| -> Result<()> {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&tmp)?;
            file.write_all(&serde_json::to_vec(data)?)?;
            file.sync_all()?;
            fs::rename(&tmp, &self.path)?;
            fs::File::open(parent)?.sync_all()?;
            Ok(())
        })();
        if result.is_err() {
            let _ = fs::remove_file(&tmp);
        }
        result.context("could not save passkey store")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn passkeys_require_a_secure_rp_and_one_time_challenges() {
        let path = std::env::temp_dir().join(format!(
            "codex-passkey-test-{}.json",
            challenge_id().unwrap()
        ));
        let manager = PasskeyManager::new(path, "codex").unwrap();
        assert!(manager
            .begin_registration("http://example.com", "Laptop")
            .is_err());
        assert!(manager
            .begin_registration("https://127.0.0.1", "Laptop")
            .is_err());
        assert!(manager
            .begin_registration("https://example.com", " ")
            .is_err());
        let first = manager
            .begin_registration("https://example.com", "Laptop")
            .unwrap();
        let second = manager
            .begin_registration("https://example.com", "Laptop")
            .unwrap();
        assert_ne!(first["challengeId"], second["challengeId"]);
        assert!(first["options"]["publicKey"]["challenge"].is_string());
        assert!(manager.list().is_empty());
        assert!(!manager.available("https://example.com"));
    }
}
