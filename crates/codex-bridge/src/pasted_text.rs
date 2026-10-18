use std::fmt::Write as _;
use std::fs::{self, DirBuilder, OpenOptions};
use std::io::Write as _;
use std::os::unix::fs::{DirBuilderExt as _, OpenOptionsExt as _};
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};
use codex_bridge::ComposerAttachment;

pub(crate) const MAX_PASTED_TEXT_BYTES: usize = 2 * 1024 * 1024;
pub(crate) const MAX_UPLOADED_FILE_BYTES: usize = 32 * 1024 * 1024;
const FILE_NAME: &str = "pasted-text.txt";
const FILE_HEADING: &str = "## Pasted text.txt: ";
const REQUEST_HEADING: &str = "## My request:\n";

pub(crate) struct PastedTextWrapper<'a> {
    pub paths: Vec<&'a str>,
    pub request: &'a str,
}

fn valid_id(id: &str) -> bool {
    id.len() == 36
        && id.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
}

fn valid_upload_id(id: &str) -> bool {
    id.len() == 32 && id.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn safe_filename(name: &str) -> String {
    let mut safe = String::new();
    for character in name.chars() {
        let replacement = if character.is_control() || matches!(character, '/' | '\\' | ':') {
            '_'
        } else {
            character
        };
        if safe.len() + replacement.len_utf8() > 120 {
            break;
        }
        safe.push(replacement);
    }
    let safe = safe.trim().trim_matches('.');
    if safe.is_empty() {
        "attachment".to_owned()
    } else {
        safe.to_owned()
    }
}

fn upload_path(codex_home: &Path, id: &str, name: &str) -> Result<PathBuf> {
    if !valid_upload_id(id) || safe_filename(name) != name {
        bail!("invalid uploaded-file reference");
    }
    let path = codex_home
        .join("attachments")
        .join(format!("bridge-upload-{id}"))
        .join(name);
    if !fs::symlink_metadata(&path)?.file_type().is_file() {
        bail!("uploaded attachment is not a regular file");
    }
    Ok(path)
}

pub(crate) fn store_upload(
    codex_home: &Path,
    name: &str,
    bytes: &[u8],
) -> Result<(String, String)> {
    if bytes.is_empty() || bytes.len() > MAX_UPLOADED_FILE_BYTES {
        bail!("uploaded file must be between 1 byte and 32 MiB");
    }
    let name = safe_filename(name);
    let root = codex_home.join("attachments");
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&root)?;
    for _ in 0..4 {
        let mut random = [0u8; 16];
        getrandom::fill(&mut random)?;
        let mut id = String::with_capacity(32);
        for byte in random {
            write!(&mut id, "{byte:02x}")?;
        }
        let directory = root.join(format!("bridge-upload-{id}"));
        match DirBuilder::new().mode(0o700).create(&directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(error) => return Err(error).context("failed to create upload directory"),
        }
        let path = directory.join(&name);
        let result = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
            .and_then(|mut file| file.write_all(bytes));
        if let Err(error) = result {
            let _ = fs::remove_dir_all(&directory);
            return Err(error).context("failed to write uploaded attachment");
        }
        return Ok((id, name));
    }
    bail!("could not allocate a unique upload directory")
}

pub(crate) fn materialize(
    codex_home: &Path,
    prompt: &str,
    attachments: &[ComposerAttachment],
) -> Result<String> {
    let pasted = attachments
        .iter()
        .filter_map(|attachment| match attachment {
            ComposerAttachment::PastedText { id, text } => Some((id.as_str(), text.as_str())),
            _ => None,
        })
        .collect::<Vec<_>>();
    let uploaded = attachments
        .iter()
        .filter_map(|attachment| match attachment {
            ComposerAttachment::File { id, name } => Some((id.as_str(), name.as_str())),
            _ => None,
        })
        .collect::<Vec<_>>();
    if pasted.is_empty() && uploaded.is_empty() {
        return Ok(prompt.to_owned());
    }
    if pasted.iter().any(|(id, text)| {
        !valid_id(id) || text.trim().is_empty() || text.len() > MAX_PASTED_TEXT_BYTES
    }) {
        bail!("invalid pasted-text attachment");
    }

    let root = codex_home.join("attachments");
    DirBuilder::new()
        .recursive(true)
        .mode(0o700)
        .create(&root)
        .with_context(|| format!("failed to create {}", root.display()))?;
    let mut paths = Vec::with_capacity(pasted.len());
    for (id, text) in pasted {
        let directory = root.join(format!("bridge-{id}"));
        match DirBuilder::new().mode(0o700).create(&directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                let metadata = fs::symlink_metadata(&directory)?;
                if !metadata.file_type().is_dir() {
                    bail!("pasted-text attachment directory is not a regular directory");
                }
            }
            Err(error) => return Err(error).context("failed to create pasted-text directory"),
        }
        let path = directory.join(FILE_NAME);
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
        {
            Ok(mut file) => file
                .write_all(text.as_bytes())
                .context("failed to write pasted-text attachment")?,
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                if !fs::symlink_metadata(&path)?.file_type().is_file() {
                    bail!("pasted-text attachment is not a regular file");
                }
                if fs::read(&path).context("failed to read existing pasted text")?
                    != text.as_bytes()
                {
                    bail!("pasted-text attachment id was reused with different content");
                }
            }
            Err(error) => return Err(error).context("failed to create pasted-text attachment"),
        }
        paths.push(path);
    }
    let mut uploaded_paths = Vec::with_capacity(uploaded.len());
    for (id, name) in uploaded {
        let path =
            upload_path(codex_home, id, name).context("uploaded attachment is unavailable")?;
        uploaded_paths.push((name, path));
    }

    let mut wrapped = String::from("# Files mentioned by the user:\n\n");
    let mut pasted_paths = paths.into_iter();
    let mut uploaded_paths = uploaded_paths.into_iter();
    for attachment in attachments {
        match attachment {
            ComposerAttachment::PastedText { .. } => {
                let path = pasted_paths.next().expect("materialized pasted text path");
                wrapped.push_str(FILE_HEADING);
                wrapped.push_str(&path.to_string_lossy());
                wrapped.push_str("\n\n");
            }
            ComposerAttachment::File { .. } => {
                let (name, path) = uploaded_paths.next().expect("resolved uploaded file path");
                wrapped.push_str("## ");
                wrapped.push_str(name);
                wrapped.push_str(": ");
                wrapped.push_str(&path.to_string_lossy());
                wrapped.push_str("\n\n");
            }
            _ => {}
        }
    }
    wrapped.push_str(REQUEST_HEADING);
    wrapped.push_str(prompt);
    Ok(wrapped)
}

pub(crate) fn parse_wrapper(text: &str) -> Option<PastedTextWrapper<'_>> {
    let (files, request) = text.split_once(REQUEST_HEADING)?;
    if !files.starts_with("# Files mentioned by the user:\n") {
        return None;
    }
    let paths = files
        .lines()
        .filter_map(|line| {
            line.strip_prefix("## ")?
                .rsplit_once(": ")
                .map(|(_, path)| path)
        })
        .filter(|path| {
            Path::new(path).is_absolute() && (is_upload_path(path) || is_pasted_path(path))
        })
        .collect::<Vec<_>>();
    (!paths.is_empty()).then_some(PastedTextWrapper { paths, request })
}

pub(crate) fn authorized_path(codex_home: &Path, requested: &str) -> Option<PathBuf> {
    let root = fs::canonicalize(codex_home.join("attachments")).ok()?;
    let path = fs::canonicalize(requested).ok()?;
    let relative = path.strip_prefix(&root).ok()?;
    let mut parts = relative.components();
    let directory = parts.next()?.as_os_str().to_str()?;
    let file = parts.next()?.as_os_str();
    if parts.next().is_some() {
        return None;
    }
    let pasted = directory.strip_prefix("bridge-").is_some_and(valid_id) && file == FILE_NAME;
    let upload = directory
        .strip_prefix("bridge-upload-")
        .is_some_and(valid_upload_id)
        && file
            .to_str()
            .is_some_and(|name| safe_filename(name) == name);
    if !pasted && !upload {
        return None;
    }
    Some(path)
}

pub(crate) fn is_upload_path(path: &str) -> bool {
    Path::new(path)
        .parent()
        .and_then(Path::file_name)
        .and_then(|part| part.to_str())
        .and_then(|part| part.strip_prefix("bridge-upload-"))
        .is_some_and(valid_upload_id)
}

fn is_pasted_path(path: &str) -> bool {
    let path = Path::new(path);
    path.file_name().is_some_and(|name| name == FILE_NAME)
        && path
            .parent()
            .and_then(Path::file_name)
            .and_then(|part| part.to_str())
            .and_then(|part| part.strip_prefix("bridge-"))
            .is_some_and(valid_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pasted_text_is_a_private_file_and_history_wrapper() {
        let root = std::env::temp_dir().join(format!("codex-bridge-paste-{}", std::process::id()));
        let attachment = ComposerAttachment::PastedText {
            id: "d28b8e6c-2ab3-4df6-8d3d-40cb925665d9".into(),
            text: "first line\nsecond line".into(),
        };
        let wrapped = materialize(&root, "Review this", &[attachment.clone()]).unwrap();
        let parsed = parse_wrapper(&wrapped).unwrap();
        assert_eq!(parsed.request, "Review this");
        assert_eq!(parsed.paths.len(), 1);
        let path = Path::new(parsed.paths[0]);
        assert_eq!(fs::read_to_string(path).unwrap(), "first line\nsecond line");
        assert!(!wrapped.contains("first line"));
        assert_eq!(
            authorized_path(&root, parsed.paths[0]),
            Some(fs::canonicalize(path).unwrap())
        );
        assert_eq!(
            materialize(&root, "Review this", &[attachment]).unwrap(),
            wrapped
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn binary_uploads_keep_bytes_and_are_authorized_only_inside_private_store() {
        let root = std::env::temp_dir().join(format!("codex-bridge-binary-{}", std::process::id()));
        let bin = [0u8, 255, 0, 42];
        let (bin_id, bin_name) = store_upload(&root, "firmware.bin", &bin).unwrap();
        let (zip_id, zip_name) = store_upload(&root, "design.zip", b"PK\x03\x04\0payload").unwrap();
        let attachments = [
            ComposerAttachment::File {
                id: bin_id,
                name: bin_name,
            },
            ComposerAttachment::File {
                id: zip_id,
                name: zip_name,
            },
        ];
        let wrapped = materialize(&root, "Inspect both", &attachments).unwrap();
        let parsed = parse_wrapper(&wrapped).unwrap();
        assert_eq!(parsed.paths.len(), 2);
        assert_eq!(parsed.request, "Inspect both");
        assert_eq!(fs::read(parsed.paths[0]).unwrap(), bin);
        assert_eq!(fs::read(parsed.paths[1]).unwrap(), b"PK\x03\x04\0payload");
        for path in parsed.paths {
            assert!(is_upload_path(path));
            assert!(authorized_path(&root, path).is_some());
        }
        assert!(materialize(
            &root,
            "x",
            &[ComposerAttachment::File {
                id: "0".repeat(32),
                name: "missing.bin".to_owned(),
            }]
        )
        .is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
