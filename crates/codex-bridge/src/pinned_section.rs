//! Native section identity for Bridge pins. Never invent a server-owned UUID.
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::Path;
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use codex_bridge::BackendFailure;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

pub const LEGACY_ID: &str = "01984de2-8f74-7c91-a3b2-5c5e937cf318";
const NAME: &str = "Pinned";
const RECEIPT: &str = "bridge-pinned-section.json";
static PIN_MUTATION: Mutex<()> = Mutex::new(());

/// One Bridge process serializes pin membership, ordering, and canonical reads.
/// External native clients are still checked through the snapshot and final read-back.
pub fn mutation_guard() -> Result<MutexGuard<'static, ()>, BackendFailure> {
    PIN_MUTATION.lock().map_err(|_| {
        failure(
            "thread_pins_busy",
            "Pin state is unavailable; refresh before saving again",
        )
    })
}

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
        for thread in data {
            let id = thread
                .get("id")
                .and_then(Value::as_str)
                .filter(|id| !id.is_empty())
                .ok_or_else(|| {
                    failure(
                        "app_server_protocol_error",
                        "Pin data contains no thread identity",
                    )
                })?;
            if ids.iter().any(|existing| existing == id) {
                return Err(failure(
                    "app_server_protocol_error",
                    "Pin data contains a duplicate thread identity",
                ));
            }
            ids.push(id.to_owned());
        }
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

#[derive(Debug)]
pub struct Reordered {
    pub section_id: Option<String>,
    pub thread_ids: Vec<String>,
    pub changed: bool,
}

/// Publish current native facts after an unsuccessful save, never the requested
/// target order. A missing snapshot is an explicit request for clients to refresh.
pub fn refresh_after_failed_reorder<F>(home: &Path, rpc: F) -> Value
where
    F: FnMut(&str, Value) -> Result<Value, BackendFailure>,
{
    let mut event = json!({
        "type": "bridge_thread_pins_updated",
        "refresh_required": true,
        "save_status": "unconfirmed",
    });
    if let Ok(_guard) = mutation_guard() {
        if let Ok((section_id, thread_ids)) = thread_ids(home, rpc) {
            event["section_id"] = json!(section_id);
            event["thread_ids"] = json!(thread_ids);
        }
    }
    event
}

fn validate_order(ids: &[String]) -> Result<(), BackendFailure> {
    let mut unique = std::collections::HashSet::new();
    if ids.len() > 10_000
        || ids.iter().any(|id| {
            id.is_empty()
                || id.len() > 256
                || id.trim() != id
                || id.chars().any(char::is_control)
                || !unique.insert(id)
        })
    {
        return Err(failure(
            "thread_reorder_invalid_order",
            "Use each current pinned thread exactly once; refresh before saving again",
        ));
    }
    Ok(())
}

/// Reorder existing pins only. No section creation, pinning, or automatic retry.
/// A timeout is reconciled by a read, never by replaying an uncertain move.
pub fn reorder<F>(
    home: &Path,
    desired: &[String],
    expected: &[String],
    mut rpc: F,
) -> Result<Reordered, BackendFailure>
where
    F: FnMut(&str, Value) -> Result<Value, BackendFailure>,
{
    validate_order(desired)?;
    validate_order(expected)?;
    let _guard = mutation_guard()?;
    let (section_id, mut current) = thread_ids(home, &mut rpc).map_err(|error| {
        failure(
            "thread_reorder_read_failed",
            format!(
                "Cannot read current pin order ({}); refresh before saving again",
                error.code
            ),
        )
    })?;
    if current == desired {
        return Ok(Reordered {
            section_id,
            thread_ids: current,
            changed: false,
        });
    }
    if current != expected {
        return Err(failure("thread_reorder_stale_snapshot", "Pinned conversations changed elsewhere; refresh and review the new order before saving"));
    }
    let mut current_set = current.iter().collect::<Vec<_>>();
    let mut desired_set = desired.iter().collect::<Vec<_>>();
    current_set.sort();
    desired_set.sort();
    if current_set != desired_set {
        return Err(failure("thread_reorder_invalid_order", "The requested order must include every current pin exactly once; refresh before saving again"));
    }
    let id = section_id.as_ref().ok_or_else(|| {
        failure(
            "thread_reorder_invalid_order",
            "The native pin section is missing; refresh before saving again",
        )
    })?;
    let mut before: Option<&String> = None;
    for thread in desired.iter().rev() {
        let position = current
            .iter()
            .position(|item| item == thread)
            .expect("validated permutation");
        if current.get(position + 1) == before {
            before = Some(thread);
            continue;
        }
        if let Err(error) = rpc(
            "thread/section/move",
            json!({
                "threadId": thread, "sectionId": id, "beforeThreadId": before,
            }),
        ) {
            if let Ok((verified_section, verified)) = thread_ids(home, &mut rpc) {
                if verified_section == section_id && verified == desired {
                    return Ok(Reordered {
                        section_id,
                        thread_ids: verified,
                        changed: true,
                    });
                }
            }
            return Err(failure("thread_reorder_outcome_unknown", format!("A native move could not be confirmed ({}); some order may have changed. Refresh and review before saving again; no move was retried", error.code)));
        }
        current.remove(position);
        let insertion = before
            .and_then(|next| current.iter().position(|item| item == next))
            .unwrap_or(current.len());
        current.insert(insertion, thread.clone());
        before = Some(thread);
    }
    match thread_ids(home, &mut rpc) {
        Ok((verified_section, verified)) if verified_section == section_id && verified == desired =>
            Ok(Reordered {section_id, thread_ids: verified, changed: true}),
        _ => Err(failure("thread_reorder_verify_failed", "Native pin order could not be verified; refresh and review before saving again. No success was recorded")),
    }
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

    struct NativeFixture {
        order: Vec<String>,
        moves: usize,
        reads: usize,
        fail_move: Option<usize>,
        fail_read: Option<usize>,
        ignore_moves: bool,
    }

    impl NativeFixture {
        fn new(order: &[&str]) -> Self {
            Self {
                order: ids(order),
                moves: 0,
                reads: 0,
                fail_move: None,
                fail_read: None,
                ignore_moves: false,
            }
        }

        fn rpc(&mut self, method: &str, params: Value) -> Result<Value, BackendFailure> {
            match method {
                "threadSection/list" => Ok(json!({"data":[{"id":LEGACY_ID,"name":NAME}]})),
                "thread/list" => {
                    self.reads += 1;
                    if self.fail_read == Some(self.reads) {
                        return Err(denied());
                    }
                    assert_eq!(params["sectionId"], LEGACY_ID);
                    assert_eq!(params["sortKey"], "section_position");
                    Ok(
                        json!({"data": self.order.iter().map(|id| json!({"id":id})).collect::<Vec<_>>()}),
                    )
                }
                "thread/section/move" => {
                    self.moves += 1;
                    assert_eq!(params["sectionId"], LEGACY_ID);
                    if !self.ignore_moves {
                        let thread = params["threadId"].as_str().unwrap().to_owned();
                        self.order.retain(|id| id != &thread);
                        let insert = params["beforeThreadId"]
                            .as_str()
                            .map(|next| self.order.iter().position(|id| id == next).unwrap())
                            .unwrap_or(self.order.len());
                        self.order.insert(insert, thread);
                    }
                    if self.fail_move == Some(self.moves) {
                        Err(denied())
                    } else {
                        Ok(json!({}))
                    }
                }
                _ => panic!("Reorder must not create sections, pin, or submit turns: {method}"),
            }
        }
    }

    fn ids(items: &[&str]) -> Vec<String> {
        items.iter().map(|id| (*id).to_owned()).collect()
    }

    #[test]
    fn pinned_reorder_validates_full_permutation_and_verifies_native_order() {
        let home = home();
        let mut fixture = NativeFixture::new(&["finance", "dispatcher", "pdf"]);
        let desired = ids(&["dispatcher", "finance", "pdf"]);
        let saved = reorder(&home, &desired, &fixture.order.clone(), |m, p| {
            fixture.rpc(m, p)
        })
        .unwrap();
        assert!(saved.changed);
        assert_eq!(saved.thread_ids, desired);
        assert_eq!(saved.section_id.as_deref(), Some(LEGACY_ID));
        assert_eq!(
            fixture.moves, 1,
            "Already positioned neighbours need no extra move"
        );
        assert_eq!(fixture.reads, 2);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_duplicate_and_invalid_ids_never_reach_native() {
        let home = home();
        for desired in [ids(&["a", "a"]), ids(&[" "]), ids(&["a\nb"])] {
            let result = reorder(&home, &desired, &ids(&["a", "b"]), |_, _| {
                panic!("Invalid request must not call native")
            });
            assert_eq!(result.unwrap_err().code, "thread_reorder_invalid_order");
        }
        let result = reorder(&home, &ids(&["a", "b"]), &ids(&["a", "a"]), |_, _| {
            panic!("Invalid snapshot must not call native")
        });
        assert_eq!(result.unwrap_err().code, "thread_reorder_invalid_order");
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_cannot_add_drop_or_unpin_threads() {
        let home = home();
        for desired in [ids(&["a"]), ids(&["a", "b", "c"]), ids(&["a", "unknown"])] {
            let mut fixture = NativeFixture::new(&["a", "b"]);
            let result = reorder(&home, &desired, &ids(&["a", "b"]), |m, p| fixture.rpc(m, p));
            assert_eq!(result.unwrap_err().code, "thread_reorder_invalid_order");
            assert_eq!(fixture.moves, 0);
            assert_eq!(fixture.order, ids(&["a", "b"]));
        }
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_stale_snapshot_never_writes() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "c", "b"]);
        let result = reorder(
            &home,
            &ids(&["c", "b", "a"]),
            &ids(&["a", "b", "c"]),
            |m, p| fixture.rpc(m, p),
        );
        assert_eq!(result.unwrap_err().code, "thread_reorder_stale_snapshot");
        assert_eq!(fixture.moves, 0);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_exact_retry_reads_success_without_another_move() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b"]);
        let result = reorder(&home, &ids(&["a", "b"]), &ids(&["b", "a"]), |m, p| {
            fixture.rpc(m, p)
        })
        .unwrap();
        assert!(!result.changed);
        assert_eq!(fixture.moves, 0);
        assert_eq!(fixture.reads, 1);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_final_read_failure_is_not_reported_saved() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b"]);
        fixture.fail_read = Some(2);
        let result = reorder(&home, &ids(&["b", "a"]), &ids(&["a", "b"]), |m, p| {
            fixture.rpc(m, p)
        });
        assert_eq!(result.unwrap_err().code, "thread_reorder_verify_failed");
        assert_eq!(fixture.moves, 1);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_partial_timeout_stops_without_retry_or_compensation() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b", "c"]);
        fixture.fail_move = Some(1);
        let result = reorder(
            &home,
            &ids(&["c", "b", "a"]),
            &ids(&["a", "b", "c"]),
            |m, p| fixture.rpc(m, p),
        );
        assert_eq!(result.unwrap_err().code, "thread_reorder_outcome_unknown");
        assert_eq!(fixture.moves, 1);
        assert_eq!(fixture.order, ids(&["b", "c", "a"]));
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_partial_failure_notifies_other_clients_with_current_facts() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b", "c"]);
        fixture.fail_move = Some(1);
        let desired = ids(&["c", "b", "a"]);
        assert!(
            reorder(&home, &desired, &ids(&["a", "b", "c"]), |m, p| fixture
                .rpc(m, p))
            .is_err()
        );
        let event = refresh_after_failed_reorder(&home, |m, p| fixture.rpc(m, p));
        assert_eq!(event["type"], "bridge_thread_pins_updated");
        assert_eq!(event["save_status"], "unconfirmed");
        assert_eq!(event["refresh_required"], true);
        assert_eq!(event["thread_ids"], json!(["b", "c", "a"]));
        assert_ne!(event["thread_ids"], json!(desired));
        assert!(event.get("verified").is_none());
        let (_, read_by_other_client) = thread_ids(&home, |m, p| fixture.rpc(m, p)).unwrap();
        assert_eq!(event["thread_ids"], json!(read_by_other_client));
        assert_eq!(
            fixture.moves, 1,
            "Notification and reconciliation never retry writes"
        );
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_failed_reconciliation_requests_refresh_without_invented_ids() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b"]);
        fixture.fail_read = Some(1);
        let event = refresh_after_failed_reorder(&home, |m, p| fixture.rpc(m, p));
        assert_eq!(event["refresh_required"], true);
        assert_eq!(event["save_status"], "unconfirmed");
        assert!(event.get("thread_ids").is_none());
        assert!(event.get("section_id").is_none());
        assert!(event.get("verified").is_none());
        assert_eq!(fixture.moves, 0);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_last_move_timeout_is_reconciled_by_read_only() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b"]);
        fixture.fail_move = Some(1);
        let result = reorder(&home, &ids(&["b", "a"]), &ids(&["a", "b"]), |m, p| {
            fixture.rpc(m, p)
        })
        .unwrap();
        assert!(result.changed);
        assert_eq!(result.thread_ids, ids(&["b", "a"]));
        assert_eq!(fixture.moves, 1);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_native_noop_or_external_drift_fails_readback() {
        let home = home();
        let mut fixture = NativeFixture::new(&["a", "b"]);
        fixture.ignore_moves = true;
        let result = reorder(&home, &ids(&["b", "a"]), &ids(&["a", "b"]), |m, p| {
            fixture.rpc(m, p)
        });
        assert_eq!(result.unwrap_err().code, "thread_reorder_verify_failed");
        assert_eq!(fixture.moves, 1);
        fs::remove_dir_all(home).unwrap();
    }

    #[test]
    fn pinned_reorder_two_concurrent_saves_cannot_interleave_moves() {
        let home = home();
        let fixture = std::sync::Arc::new(Mutex::new(NativeFixture::new(&["a", "b", "c"])));
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(2));
        let handles = [ids(&["b", "a", "c"]), ids(&["a", "c", "b"])]
            .into_iter()
            .map(|desired| {
                let (home, fixture, barrier) = (home.clone(), fixture.clone(), barrier.clone());
                std::thread::spawn(move || {
                    barrier.wait();
                    reorder(&home, &desired, &ids(&["a", "b", "c"]), |m, p| {
                        fixture.lock().unwrap().rpc(m, p)
                    })
                })
            })
            .collect::<Vec<_>>();
        let results = handles
            .into_iter()
            .map(|handle| handle.join().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(results.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(
            results
                .iter()
                .filter(|result| result
                    .as_ref()
                    .is_err_and(|error| error.code == "thread_reorder_stale_snapshot"))
                .count(),
            1
        );
        fs::remove_dir_all(home).unwrap();
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
