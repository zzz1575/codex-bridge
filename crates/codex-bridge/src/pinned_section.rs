//! Native section identity for Bridge pins. Never invent a server-owned UUID.
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use codex_bridge::BackendFailure;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

pub const LEGACY_ID: &str = "01984de2-8f74-7c91-a3b2-5c5e937cf318";
const NAME: &str = "Pinned";
const RECEIPT: &str = "bridge-pinned-section.json";

#[derive(Clone, Serialize, Deserialize)]
struct Receipt {
    version: u8,
    state: String,
    section_id: Option<String>,
    before_ids: Vec<String>,
}

fn failure(code: &'static str, message: impl Into<String>) -> BackendFailure {
    BackendFailure {
        code,
        message: message.into(),
    }
}

fn read(home: &Path) -> Result<Option<Receipt>, BackendFailure> {
    let path = home.join(RECEIPT);
    let metadata = match fs::symlink_metadata(&path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(_) => {
            return Err(failure(
                "pinned_section_receipt_unavailable",
                "Cannot read the pin section receipt",
            ))
        }
    };
    if !metadata.is_file() || metadata.nlink() != 1 || metadata.len() > 65_536 {
        return Err(failure(
            "pinned_section_receipt_invalid",
            "Pin section receipt is not a bounded regular file",
        ));
    }
    let bytes = fs::read(&path).map_err(|_| {
        failure(
            "pinned_section_receipt_unavailable",
            "Cannot read the pin section receipt",
        )
    })?;
    let receipt: Receipt = serde_json::from_slice(&bytes).map_err(|_| {
        failure(
            "pinned_section_receipt_invalid",
            "Pin section receipt is invalid; preserve it for reconciliation",
        )
    })?;
    if receipt.version != 1 || !matches!(receipt.state.as_str(), "creating" | "confirmed") {
        return Err(failure(
            "pinned_section_receipt_invalid",
            "Unknown pin section receipt version or state",
        ));
    }
    Ok(Some(receipt))
}

fn write(home: &Path, receipt: &Receipt) -> Result<(), BackendFailure> {
    let path = home.join(RECEIPT);
    if fs::symlink_metadata(&path).is_ok_and(|m| !m.is_file() || m.nlink() != 1) {
        return Err(failure(
            "pinned_section_receipt_invalid",
            "Refusing an unsafe pin section receipt",
        ));
    }
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let temporary = home.join(format!(".bridge-pin-{}-{nonce}", std::process::id()));
    let result = (|| -> std::io::Result<()> {
        let mut stream = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temporary)?;
        stream.write_all(&serde_json::to_vec(receipt)?)?;
        stream.sync_all()?;
        fs::rename(&temporary, &path)?;
        File::open(home)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result.map_err(|_| {
        failure(
            "pinned_section_receipt_unavailable",
            "Cannot persist the pin section intention; no create was retried",
        )
    })
}

fn present(sections: &[Value], id: &str) -> bool {
    sections
        .iter()
        .any(|s| s.get("id").and_then(Value::as_str) == Some(id))
}

/// Read-only resolution. A missing receipt never creates a native section.
pub fn resolve(home: &Path, sections: &[Value]) -> Result<Option<String>, BackendFailure> {
    if present(sections, LEGACY_ID) {
        return Ok(Some(LEGACY_ID.to_owned()));
    }
    let receipt = read(home)?;
    if let Some(id) = receipt.as_ref().and_then(|r| r.section_id.as_ref()) {
        return if present(sections, id) {
            Ok(Some(id.clone()))
        } else {
            Err(failure("pinned_section_missing", "The recorded pin section is missing; refresh or reconcile it before creating another"))
        };
    }
    let matches: Vec<String> = sections
        .iter()
        .filter(|section| section.get("name").and_then(Value::as_str) == Some(NAME))
        .filter_map(|section| section.get("id").and_then(Value::as_str).map(str::to_owned))
        .filter(|id| receipt.as_ref().is_none_or(|r| !r.before_ids.contains(id)))
        .collect();
    match matches.as_slice() {
        [id] => Ok(Some(id.clone())),
        [] if receipt.is_none() => Ok(None),
        [] => Err(failure("pinned_section_create_unknown", "Pin section creation is unresolved; inspect the original receipt and refresh instead of creating again")),
        _ => Err(failure("pinned_section_ambiguous", "More than one pin section matches; preserve them and select the intended identity explicitly")),
    }
}

fn list<F>(rpc: &mut F) -> Result<Vec<Value>, BackendFailure>
where
    F: FnMut(&str, Value) -> Result<Value, BackendFailure>,
{
    let mut rows = Vec::new();
    let mut cursor = None::<String>;
    let mut seen = std::collections::HashSet::new();
    for _ in 0..100 {
        let result = rpc("threadSection/list", json!({"limit":100,"cursor":cursor}))?;
        let data = result
            .get("data")
            .and_then(Value::as_array)
            .ok_or_else(|| {
                failure(
                    "app_server_protocol_error",
                    "threadSection/list returned no section data",
                )
            })?;
        rows.extend(data.iter().cloned());
        match result.get("nextCursor").and_then(Value::as_str) {
            None => return Ok(rows),
            Some(next) if seen.insert(next.to_owned()) => cursor = Some(next.to_owned()),
            Some(_) => {
                return Err(failure(
                    "app_server_protocol_error",
                    "threadSection/list repeated a cursor",
                ))
            }
        }
    }
    Err(failure(
        "app_server_protocol_error",
        "threadSection/list exceeded its bounded inventory",
    ))
}

/// Preserve the server's explicit section order, including pagination.
pub fn thread_ids<F>(
    home: &Path,
    mut rpc: F,
) -> Result<(Option<String>, Vec<String>), BackendFailure>
where
    F: FnMut(&str, Value) -> Result<Value, BackendFailure>,
{
    let sections = list(&mut rpc)?;
    let section_id = resolve(home, &sections)?;
    let Some(id) = section_id.as_ref() else {
        return Ok((None, Vec::new()));
    };
    let mut ids = Vec::new();
    let mut cursor = None::<String>;
    let mut seen = std::collections::HashSet::new();
    for _ in 0..100 {
        let result = rpc(
            "thread/list",
            json!({"cursor":cursor,"limit":100,"modelProviders":[],
            "sectionId":id,"sortKey":"section_position","sortDirection":"asc","useStateDbOnly":true}),
        )?;
        let data = result
            .get("data")
            .and_then(Value::as_array)
            .ok_or_else(|| {
                failure(
                    "app_server_protocol_error",
                    "thread/list returned no pin data",
                )
            })?;
        ids.extend(
            data.iter()
                .filter_map(|t| t.get("id").and_then(Value::as_str).map(str::to_owned)),
        );
        match result.get("nextCursor").and_then(Value::as_str) {
            None => return Ok((section_id, ids)),
            Some(next) if seen.insert(next.to_owned()) => cursor = Some(next.to_owned()),
            Some(_) => {
                return Err(failure(
                    "app_server_protocol_error",
                    "thread/list repeated the pin cursor",
                ))
            }
        }
    }
    Err(failure(
        "app_server_protocol_error",
        "Pin list exceeded its bounded inventory",
    ))
}

/// Serialize native creation and fsync the intent before the one external call.
/// Unknown outcomes are reconciled by listing; this function never resends create.
pub fn ensure<F>(home: &Path, mut rpc: F) -> Result<String, BackendFailure>
where
    F: FnMut(&str, Value) -> Result<Value, BackendFailure>,
{
    if !home.is_dir() || home.is_symlink() {
        return Err(failure(
            "pinned_section_receipt_invalid",
            "Pin state requires an ordinary Codex home directory",
        ));
    }
    let lock = OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(false)
        .mode(0o600)
        .custom_flags(libc::O_NOFOLLOW)
        .open(home.join(".bridge-pinned-section.lock"))
        .map_err(|_| {
            failure(
                "pinned_section_receipt_unavailable",
                "Cannot lock the pin section intention",
            )
        })?;
    // This lock is local and held only for the native list/create round-trip.
    if unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX) } != 0 {
        return Err(failure(
            "pinned_section_receipt_unavailable",
            "Cannot lock the pin section intention",
        ));
    }
    let sections = list(&mut rpc)?;
    if let Some(id) = resolve(home, &sections)? {
        if id != LEGACY_ID {
            write(
                home,
                &Receipt {
                    version: 1,
                    state: "confirmed".into(),
                    section_id: Some(id.clone()),
                    before_ids: Vec::new(),
                },
            )?;
        }
        return Ok(id);
    }
    let mut receipt = Receipt {
        version: 1,
        state: "creating".into(),
        section_id: None,
        before_ids: sections
            .iter()
            .filter_map(|s| s.get("id").and_then(Value::as_str).map(str::to_owned))
            .collect(),
    };
    write(home, &receipt)?;
    let created = match rpc("threadSection/create", json!({"name":NAME})) {
        Ok(created) => created,
        Err(error) => {
            if let Ok(sections) = list(&mut rpc) {
                if let Ok(Some(id)) = resolve(home, &sections) {
                    receipt.state = "confirmed".into();
                    receipt.section_id = Some(id.clone());
                    write(home, &receipt)?;
                    return Ok(id);
                }
            }
            return Err(failure("pinned_section_create_unknown", format!("Pin section creation could not be confirmed ({}); refresh, do not create again",error.code)));
        }
    };
    let id = created
        .pointer("/section/id")
        .and_then(Value::as_str)
        .filter(|id| !id.is_empty())
        .ok_or_else(|| {
            failure(
                "pinned_section_create_unknown",
                "Native create returned no section identity; refresh instead of creating again",
            )
        })?;
    receipt.state = "confirmed".into();
    receipt.section_id = Some(id.to_owned());
    write(home, &receipt)?;
    Ok(id.to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    fn home() -> std::path::PathBuf {
        let p = std::env::temp_dir().join(format!(
            "bridge-pin-test-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&p).unwrap();
        p
    }
    fn denied() -> BackendFailure {
        failure("timeout", "fixture timeout")
    }
    #[test]
    fn pinned_section_legacy_identity_is_reused_without_creation() {
        let home = home();
        let mut calls = 0;
        let id = ensure(&home, |method, _| {
            assert_eq!(method, "threadSection/list");
            calls += 1;
            Ok(json!({"data":[{"id":LEGACY_ID,"name":"Pinned"}]}))
        })
        .unwrap();
        assert_eq!(id, LEGACY_ID);
        assert_eq!(calls, 1);
        assert!(!home.join(RECEIPT).exists());
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_read_missing_has_no_side_effects() {
        let home = home();
        assert_eq!(resolve(&home, &[]).unwrap(), None);
        assert_eq!(fs::read_dir(&home).unwrap().count(), 0);
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_native_uuid_is_created_once_and_persisted() {
        let home = home();
        let id = "01984de2-1111-7111-a111-111111111111";
        let mut creates = 0;
        assert_eq!(
            ensure(&home, |method, _| match method {
                "threadSection/list" => Ok(json!({"data":[]})),
                "threadSection/create" => {
                    creates += 1;
                    Ok(json!({"section":{"id":id,"name":NAME}}))
                }
                _ => panic!(),
            })
            .unwrap(),
            id
        );
        assert_eq!(
            ensure(&home, |method, _| {
                assert_eq!(method, "threadSection/list");
                Ok(json!({"data":[{"id":id,"name":NAME}]}))
            })
            .unwrap(),
            id
        );
        assert_eq!(creates, 1);
        assert_eq!(
            fs::metadata(home.join(RECEIPT)).unwrap().mode() & 0o777,
            0o600
        );
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_timeout_after_accept_is_reconciled_without_second_create() {
        let home = home();
        let id = "01984de2-2222-7222-a222-222222222222";
        let mut created = false;
        let mut count = 0;
        let actual = ensure(&home, |method, _| match method {
            "threadSection/list" => {
                Ok(json!({"data":if created {vec![json!({"id":id,"name":NAME})]} else {vec![]}}))
            }
            "threadSection/create" => {
                created = true;
                count += 1;
                Err(denied())
            }
            _ => panic!(),
        })
        .unwrap();
        assert_eq!(actual, id);
        assert_eq!(count, 1);
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_unknown_create_is_never_reissued_after_restart() {
        let home = home();
        let mut count = 0;
        assert!(
            ensure(&home, |method, _| if method == "threadSection/create" {
                count += 1;
                Err(denied())
            } else {
                Ok(json!({"data":[]}))
            })
            .is_err()
        );
        assert!(ensure(&home, |method, _| {
            assert_eq!(method, "threadSection/list");
            Ok(json!({"data":[]}))
        })
        .is_err());
        assert_eq!(count, 1);
        assert_eq!(read(&home).unwrap().unwrap().state, "creating");
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_existing_unique_named_group_is_adopted() {
        let home = home();
        let id = "01984de2-3333-7333-a333-333333333333";
        assert_eq!(
            ensure(&home, |method, _| {
                assert_eq!(method, "threadSection/list");
                Ok(json!({"data":[{"id":id,"name":NAME}]}))
            })
            .unwrap(),
            id
        );
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_ambiguous_groups_do_not_create_another() {
        let home = home();
        assert!(ensure(&home, |method, _| {
            assert_eq!(method, "threadSection/list");
            Ok(json!({"data":[{"id":"one","name":NAME},{"id":"two","name":NAME}]}))
        })
        .is_err());
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_thread_list_preserves_native_manual_order_across_pages() {
        let home = home();
        let id = "01984de2-4444-7444-a444-444444444444";
        write(
            &home,
            &Receipt {
                version: 1,
                state: "confirmed".into(),
                section_id: Some(id.into()),
                before_ids: vec![],
            },
        )
        .unwrap();
        let (section, ids) = thread_ids(&home, |method, params| match method {
            "threadSection/list" => Ok(json!({"data":[{"id":id,"name":NAME}]})),
            "thread/list" => {
                assert_eq!(params["sectionId"], id);
                assert_eq!(params["sortKey"], "section_position");
                assert_eq!(params["sortDirection"], "asc");
                if params["cursor"].is_null() {
                    Ok(json!({"data":[{"id":"finance"},{"id":"dispatcher"}],"nextCursor":"next"}))
                } else {
                    Ok(json!({"data":[{"id":"pdf"},{"id":"ab"}]}))
                }
            }
            _ => panic!("Read-only pins must not mutate native state"),
        })
        .unwrap();
        assert_eq!(section, Some(id.into()));
        assert_eq!(ids, vec!["finance", "dispatcher", "pdf", "ab"]);
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_unknown_created_id_is_discovered_by_list_after_restart() {
        let home = home();
        write(
            &home,
            &Receipt {
                version: 1,
                state: "creating".into(),
                section_id: None,
                before_ids: vec!["old".into()],
            },
        )
        .unwrap();
        let id = "01984de2-5555-7555-a555-555555555555";
        assert_eq!(
            ensure(&home, |method, _| {
                assert_eq!(method, "threadSection/list");
                Ok(json!({"data":[{"id":id,"name":NAME}]}))
            })
            .unwrap(),
            id
        );
        assert_eq!(read(&home).unwrap().unwrap().state, "confirmed");
        fs::remove_dir_all(home).unwrap();
    }
    #[test]
    fn pinned_section_deleted_recorded_group_is_not_recreated() {
        let home = home();
        write(
            &home,
            &Receipt {
                version: 1,
                state: "confirmed".into(),
                section_id: Some("old".into()),
                before_ids: vec![],
            },
        )
        .unwrap();
        assert!(ensure(&home, |method, _| {
            assert_eq!(method, "threadSection/list");
            Ok(json!({"data":[]}))
        })
        .is_err());
        fs::remove_dir_all(home).unwrap();
    }
}
