//! Create only a newly allocated, journal-owned project directory. Registration is injected
//! so retries and filesystem protections can be tested without a live app-server.
use std::ffi::CString;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::fs::MetadataExt;
use std::os::unix::process::CommandExt;
use std::path::{Component, Path, PathBuf};
use std::process::{Command, Output, Stdio};

use codex_bridge::BackendFailure;
use serde::{Deserialize, Serialize};
use serde_json::Value;

const EMPTY_TREE: &str = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";
const INITIAL_MESSAGE: &str = "Initialize empty project";
const MAX_JOURNAL_BYTES: u64 = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Identity {
    dev: u64,
    ino: u64,
}

impl Identity {
    fn of(metadata: &fs::Metadata) -> Self {
        Self {
            dev: metadata.dev(),
            ino: metadata.ino(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Phase {
    Planned,
    Allocated,
    InitializingGit,
    GitInitialized,
    GitReady,
    Complete,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Journal {
    version: u32,
    operation: String,
    request_id: String,
    parent_path: PathBuf,
    parent_identity: Identity,
    directory_name: String,
    name: String,
    directory_identity: Option<Identity>,
    git_identity: Option<Identity>,
    phase: Phase,
    git_head: Option<String>,
    project: Option<Value>,
}

#[derive(Debug, Serialize)]
pub(super) struct InitializedProject {
    pub project_path: PathBuf,
    pub name: String,
    pub git_head: String,
    pub request_id: String,
    pub project: Value,
}

fn failure(code: &'static str, message: impl Into<String>) -> BackendFailure {
    BackendFailure {
        code,
        message: message.into(),
    }
}

fn io_failure(operation: &str, error: std::io::Error) -> BackendFailure {
    failure("project_initialize_failed", format!("{operation}: {error}"))
}

fn c_name(name: &str) -> Result<CString, BackendFailure> {
    CString::new(name).map_err(|_| failure("invalid_request", "a path component contains NUL"))
}

fn open_at(directory: &File, name: &str, flags: i32, mode: u32) -> Result<File, BackendFailure> {
    let name = c_name(name)?;
    // All names are single components relative to a directory descriptor owned by this call.
    let fd = unsafe {
        libc::openat(
            directory.as_raw_fd(),
            name.as_ptr(),
            flags | libc::O_NOFOLLOW | libc::O_CLOEXEC,
            mode as libc::c_uint,
        )
    };
    if fd < 0 {
        return Err(io_failure(
            "cannot safely open project creation state",
            std::io::Error::last_os_error(),
        ));
    }
    Ok(unsafe { File::from_raw_fd(fd) })
}

fn open_directory(path: &Path) -> Result<File, BackendFailure> {
    use std::os::unix::fs::OpenOptionsExt;
    fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_DIRECTORY | libc::O_NOFOLLOW | libc::O_CLOEXEC)
        .open(path)
        .map_err(|error| io_failure("cannot safely open directory", error))
}

fn mkdir_at(directory: &File, name: &str, mode: libc::mode_t) -> Result<(), std::io::Error> {
    let name =
        CString::new(name).map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    if unsafe { libc::mkdirat(directory.as_raw_fd(), name.as_ptr(), mode) } == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

fn private_file(file: &File, directory: bool) -> Result<fs::Metadata, BackendFailure> {
    let metadata = file
        .metadata()
        .map_err(|error| io_failure("cannot inspect creation state", error))?;
    if (directory && !metadata.is_dir())
        || (!directory && (!metadata.is_file() || metadata.nlink() != 1))
        || metadata.uid() != unsafe { libc::geteuid() }
        || metadata.mode() & 0o077 != 0
    {
        return Err(failure(
            "project_initialize_failed",
            "project creation state must be private and owned by the daemon user",
        ));
    }
    Ok(metadata)
}

#[derive(Debug)]
struct CreationLock {
    file: File,
}

impl Drop for CreationLock {
    fn drop(&mut self) {
        // CLOEXEC closes child copies only after exec. Another thread may fork while this
        // operation holds a lock, so closing our descriptor alone can leave its shared open
        // file description locked briefly in that child. End ownership explicitly here.
        loop {
            if unsafe { libc::flock(self.file.as_raw_fd(), libc::LOCK_UN) } == 0
                || std::io::Error::last_os_error().kind() != std::io::ErrorKind::Interrupted
            {
                break;
            }
        }
    }
}

fn lock(directory: &File, name: &str) -> Result<CreationLock, BackendFailure> {
    let file = open_at(
        directory,
        name,
        libc::O_RDWR | libc::O_CREAT | libc::O_NONBLOCK,
        0o600,
    )?;
    private_file(&file, false)?;
    if unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } != 0 {
        return Err(failure(
            "project_initialize_busy",
            "this request or destination is already being initialized; retry the same request",
        ));
    }
    Ok(CreationLock { file })
}

fn state_directory(home: &Path) -> Result<File, BackendFailure> {
    if !home.is_absolute() || !fs::canonicalize(home).is_ok_and(|canonical| canonical == home) {
        return Err(failure(
            "project_initialize_failed",
            "Codex home must be an existing canonical directory without symlink components",
        ));
    }
    let home = open_directory(home)?;
    match mkdir_at(&home, "project-creation", 0o700) {
        Ok(()) => home
            .sync_all()
            .map_err(|error| io_failure("cannot sync creation state directory", error))?,
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => (),
        Err(error) => {
            return Err(io_failure(
                "cannot create private project creation state",
                error,
            ))
        }
    }
    let state = open_at(
        &home,
        "project-creation",
        libc::O_RDONLY | libc::O_DIRECTORY,
        0,
    )?;
    private_file(&state, true)?;
    Ok(state)
}

fn load(directory: &File, filename: &str) -> Result<Option<Journal>, BackendFailure> {
    let mut file = match open_at(directory, filename, libc::O_RDONLY | libc::O_NONBLOCK, 0) {
        Ok(file) => file,
        Err(error) => {
            // A missing entry is the only condition that permits allocating a new request.
            let name = c_name(filename)?;
            let mut metadata = std::mem::MaybeUninit::<libc::stat>::uninit();
            if unsafe {
                libc::fstatat(
                    directory.as_raw_fd(),
                    name.as_ptr(),
                    metadata.as_mut_ptr(),
                    libc::AT_SYMLINK_NOFOLLOW,
                )
            } != 0
                && std::io::Error::last_os_error().kind() == std::io::ErrorKind::NotFound
            {
                return Ok(None);
            }
            return Err(error);
        }
    };
    if private_file(&file, false)?.len() > MAX_JOURNAL_BYTES {
        return Err(failure(
            "project_initialize_failed",
            "project creation journal exceeds the size limit",
        ));
    }
    let mut bytes = Vec::new();
    (&mut file)
        .take(MAX_JOURNAL_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| io_failure("cannot read project creation journal", error))?;
    if bytes.len() as u64 > MAX_JOURNAL_BYTES {
        return Err(failure(
            "project_initialize_failed",
            "project creation journal exceeds the size limit",
        ));
    }
    serde_json::from_slice(&bytes).map(Some).map_err(|_| {
        failure(
            "project_initialize_failed",
            "invalid project creation journal; no existing directory was modified",
        )
    })
}

fn save(directory: &File, filename: &str, journal: &Journal) -> Result<(), BackendFailure> {
    let mut random = [0u8; 12];
    getrandom::fill(&mut random).map_err(|error| {
        failure(
            "project_initialize_failed",
            format!("cannot allocate journal temporary file: {error}"),
        )
    })?;
    let suffix = random
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    let temporary = format!(".{suffix}.tmp");
    let mut output = open_at(
        directory,
        &temporary,
        libc::O_WRONLY | libc::O_CREAT | libc::O_EXCL,
        0o600,
    )?;
    let result = (|| {
        let bytes = serde_json::to_vec(journal).map_err(|error| {
            failure(
                "project_initialize_failed",
                format!("cannot encode creation journal: {error}"),
            )
        })?;
        if bytes.len() as u64 > MAX_JOURNAL_BYTES {
            return Err(failure(
                "project_initialize_failed",
                "project registration response exceeds the journal size limit",
            ));
        }
        output
            .write_all(&bytes)
            .map_err(|error| io_failure("cannot write creation journal", error))?;
        output
            .sync_all()
            .map_err(|error| io_failure("cannot sync creation journal", error))?;
        let from = c_name(&temporary)?;
        let to = c_name(filename)?;
        if unsafe {
            libc::renameat(
                directory.as_raw_fd(),
                from.as_ptr(),
                directory.as_raw_fd(),
                to.as_ptr(),
            )
        } != 0
        {
            return Err(io_failure(
                "cannot replace creation journal",
                std::io::Error::last_os_error(),
            ));
        }
        directory
            .sync_all()
            .map_err(|error| io_failure("cannot sync creation journal directory", error))
    })();
    if result.is_err() {
        if let Ok(name) = c_name(&temporary) {
            unsafe {
                libc::unlinkat(directory.as_raw_fd(), name.as_ptr(), 0);
            }
        }
    }
    result
}

fn validate(
    directory_name: &str,
    name: Option<&str>,
    request_id: &str,
) -> Result<String, BackendFailure> {
    let mut components = Path::new(directory_name).components();
    if directory_name.is_empty()
        || directory_name.len() > 96
        || directory_name.starts_with('.')
        || directory_name.trim() != directory_name
        || directory_name
            .chars()
            .any(|ch| ch.is_control() || ch == '/' || ch == '\\')
        || !matches!(components.next(), Some(Component::Normal(_)))
        || components.next().is_some()
    {
        return Err(failure(
            "invalid_project_path",
            "directory name must be one non-hidden ordinary component of at most 96 bytes",
        ));
    }
    if !(16..=128).contains(&request_id.len())
        || !request_id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        return Err(failure(
            "invalid_request",
            "request_id must contain 16 to 128 ASCII letters, digits, hyphens or underscores",
        ));
    }
    let name = name.unwrap_or(directory_name).trim();
    if name.is_empty() || name.len() > 256 || name.chars().any(char::is_control) {
        return Err(failure(
            "invalid_project_name",
            "project name must contain 1 to 256 bytes without control characters",
        ));
    }
    Ok(name.to_owned())
}

fn matches_path(path: &Path, identity: &Identity) -> bool {
    fs::symlink_metadata(path)
        .is_ok_and(|metadata| metadata.is_dir() && Identity::of(&metadata) == *identity)
        && fs::canonicalize(path).is_ok_and(|canonical| canonical == path)
}

fn valid_journal(journal: &Journal) -> bool {
    let directory = journal.directory_identity.is_some();
    let git = journal.git_identity.is_some();
    let head = journal
        .git_head
        .as_ref()
        .is_some_and(|head| head.len() == 40 && head.bytes().all(|byte| byte.is_ascii_hexdigit()));
    let project = journal.project.is_some();
    match journal.phase {
        Phase::Planned => !directory && !git && journal.git_head.is_none() && !project,
        Phase::Allocated => directory && !git && journal.git_head.is_none() && !project,
        Phase::InitializingGit | Phase::GitInitialized => {
            directory && git && journal.git_head.is_none() && !project
        }
        Phase::GitReady => directory && git && head && !project,
        Phase::Complete => directory && git && head && project,
    }
}

fn ensure_owned(parent: &Path, journal: &Journal) -> Result<(), BackendFailure> {
    if !matches_path(parent, &journal.parent_identity)
        || !journal
            .directory_identity
            .as_ref()
            .is_some_and(|identity| matches_path(&parent.join(&journal.directory_name), identity))
    {
        return Err(failure("project_initialize_conflict", "the recorded parent or project directory was replaced or moved; no replacement directory will be used"));
    }
    if let Some(identity) = &journal.git_identity {
        if !matches_path(&parent.join(&journal.directory_name).join(".git"), identity) {
            return Err(failure(
                "project_initialize_conflict",
                "the recorded Git directory was replaced; no replacement repository will be used",
            ));
        }
    }
    Ok(())
}

fn safe_git(directory: &File) -> Command {
    let mut command = Command::new("git");
    command
        .env_clear()
        .env("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
        .env("GIT_CONFIG_NOSYSTEM", "1")
        .env("GIT_CONFIG_SYSTEM", "/dev/null")
        .env("GIT_CONFIG_GLOBAL", "/dev/null")
        .env("GIT_ATTR_NOSYSTEM", "1")
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_AUTHOR_NAME", "Codex Bridge")
        .env("GIT_COMMITTER_NAME", "Codex Bridge")
        .env("GIT_AUTHOR_EMAIL", "codex-bridge@example.invalid")
        .env("GIT_COMMITTER_EMAIL", "codex-bridge@example.invalid")
        .args([
            "--git-dir=.",
            "--work-tree=..",
            "-c",
            "core.hooksPath=/dev/null",
            "-c",
            "core.fsmonitor=false",
            "-c",
            "core.attributesFile=/dev/null",
            "-c",
            "commit.gpgSign=false",
            "-c",
            "tag.gpgSign=false",
        ])
        .stdin(Stdio::null());
    let fd = directory.as_raw_fd();
    // fchdir is async-signal-safe. The descriptor remains owned by the caller until output()
    // completes; this anchors every Git invocation to the owned Git directory, including init,
    // even if a project or .git path is concurrently renamed. No Git directory is discovered.
    unsafe {
        command.pre_exec(move || {
            if libc::fchdir(fd) == 0 {
                Ok(())
            } else {
                Err(std::io::Error::last_os_error())
            }
        });
    }
    command
}

fn git(directory: &File, args: &[&str]) -> Result<String, BackendFailure> {
    let output: Output = safe_git(directory)
        .args(args)
        .output()
        .map_err(|error| io_failure("cannot run Git for the new project", error))?;
    if !output.status.success() {
        // Do not echo configuration or arbitrary subprocess output into logs/UI.
        return Err(failure(
            "project_initialize_failed",
            format!(
                "Git {} failed with {}; the new directory was retained",
                args[0], output.status
            ),
        ));
    }
    String::from_utf8(output.stdout)
        .map(|value| value.trim().to_owned())
        .map_err(|_| failure("project_initialize_failed", "Git returned invalid text"))
}

fn only_git_directory(path: &Path, allow_git: bool) -> Result<(), BackendFailure> {
    for entry in fs::read_dir(path)
        .map_err(|error| io_failure("cannot inspect newly allocated directory", error))?
    {
        let entry =
            entry.map_err(|error| io_failure("cannot inspect newly allocated directory", error))?;
        if !allow_git || entry.file_name() != ".git" {
            return Err(failure("project_initialize_conflict", "files appeared in the new project before initialization completed; no files were staged or overwritten"));
        }
    }
    Ok(())
}

fn initial_head(directory: &File) -> Result<String, BackendFailure> {
    if git(directory, &["symbolic-ref", "HEAD"])? != "refs/heads/main" {
        return Err(failure(
            "project_initialize_conflict",
            "new repository HEAD changed before initialization completed",
        ));
    }
    let head = match git(directory, &["rev-parse", "--verify", "HEAD"]) {
        Ok(head) => head,
        Err(_) => {
            let tree = git(directory, &["mktree"])?;
            if tree != EMPTY_TREE {
                return Err(failure(
                    "project_initialize_failed",
                    "Git did not produce the expected empty tree",
                ));
            }
            // commit-tree does not stage worktree files or run commit hooks. The expected old
            // value prevents replacing an existing branch if a concurrent actor commits first.
            let head = git(directory, &["commit-tree", &tree, "-m", INITIAL_MESSAGE])?;
            git(
                directory,
                &[
                    "update-ref",
                    "refs/heads/main",
                    &head,
                    "0000000000000000000000000000000000000000",
                ],
            )?;
            head
        }
    };
    if head.len() != 40
        || !head.bytes().all(|byte| byte.is_ascii_hexdigit())
        || git(
            directory,
            &["rev-list", "--parents", "-n", "1", "HEAD", "--"],
        )? != head
        || git(directory, &["rev-parse", "HEAD^{tree}"])? != EMPTY_TREE
        || git(directory, &["log", "-1", "--format=%B"])? != INITIAL_MESSAGE
    {
        return Err(failure(
            "project_initialize_conflict",
            "repository no longer matches the empty initial commit; no existing commit was changed",
        ));
    }
    Ok(head)
}

pub(super) fn initialize(
    home: &Path,
    parent_path: &Path,
    directory_name: &str,
    name: Option<&str>,
    request_id: &str,
    register: impl FnOnce(&Path, &str, &str) -> Result<Value, BackendFailure>,
) -> Result<InitializedProject, BackendFailure> {
    let name = validate(directory_name, name, request_id)?;
    if !parent_path.is_absolute() {
        return Err(failure(
            "invalid_project_path",
            "the existing parent directory must be absolute",
        ));
    }
    let parent_path = fs::canonicalize(parent_path)
        .map_err(|error| io_failure("cannot resolve existing parent directory", error))?;
    let parent = open_directory(&parent_path)?;
    let parent_identity = Identity::of(
        &parent
            .metadata()
            .map_err(|error| io_failure("cannot inspect parent directory", error))?,
    );
    let state = state_directory(home)?;
    let _request_lock = lock(&state, &format!("request-{request_id}.lock"))?;
    let leaf_key = directory_name
        .as_bytes()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect::<String>();
    let _root_lock = lock(
        &state,
        &format!(
            "root-{:x}-{:x}-{leaf_key}.lock",
            parent_identity.dev, parent_identity.ino
        ),
    )?;
    let filename = format!("{request_id}.json");
    let mut journal = match load(&state, &filename)? {
        Some(journal) => journal,
        None => {
            let journal = Journal {
                version: 1,
                operation: "project_initialize".to_owned(),
                request_id: request_id.to_owned(),
                parent_path: parent_path.clone(),
                parent_identity: parent_identity.clone(),
                directory_name: directory_name.to_owned(),
                name: name.clone(),
                directory_identity: None,
                git_identity: None,
                phase: Phase::Planned,
                git_head: None,
                project: None,
            };
            save(&state, &filename, &journal)?;
            journal
        }
    };
    if journal.version != 1
        || journal.operation != "project_initialize"
        || journal.request_id != request_id
        || journal.parent_path != parent_path
        || journal.parent_identity != parent_identity
        || journal.directory_name != directory_name
        || journal.name != name
        || !valid_journal(&journal)
    {
        return Err(failure(
            "project_initialize_conflict",
            "request_id is already bound to a different project request",
        ));
    }
    let project_path = parent_path.join(directory_name);
    let outcome = (|| {
        if journal.phase == Phase::Planned {
            if !matches_path(&parent_path, &parent_identity) {
                return Err(failure(
                    "project_initialize_conflict",
                    "parent directory changed before allocation",
                ));
            }
            mkdir_at(&parent, directory_name, 0o755).map_err(|error| {
                if error.kind() == std::io::ErrorKind::AlreadyExists {
                    failure(
                        "project_path_exists",
                        "destination already exists; no existing directory was modified",
                    )
                } else {
                    io_failure("cannot allocate new project directory", error)
                }
            })?;
            let directory = open_at(
                &parent,
                directory_name,
                libc::O_RDONLY | libc::O_DIRECTORY,
                0,
            )?;
            let metadata = directory
                .metadata()
                .map_err(|error| io_failure("cannot inspect allocated project directory", error))?;
            if metadata.uid() != unsafe { libc::geteuid() } {
                return Err(failure(
                    "project_initialize_conflict",
                    "new project directory ownership changed during allocation",
                ));
            }
            journal.directory_identity = Some(Identity::of(&metadata));
            journal.phase = Phase::Allocated;
            save(&state, &filename, &journal)?;
            parent.sync_all().map_err(|error| {
                io_failure("cannot sync newly allocated project directory", error)
            })?;
        }
        ensure_owned(&parent_path, &journal)?;
        let directory = open_at(
            &parent,
            directory_name,
            libc::O_RDONLY | libc::O_DIRECTORY,
            0,
        )?;
        if Identity::of(
            &directory
                .metadata()
                .map_err(|error| io_failure("cannot inspect project directory", error))?,
        ) != *journal.directory_identity.as_ref().unwrap()
        {
            return Err(failure(
                "project_initialize_conflict",
                "project directory changed while opening it",
            ));
        }
        if journal.phase == Phase::Allocated {
            only_git_directory(&project_path, false)?;
            mkdir_at(&directory, ".git", 0o700).map_err(|error| {
                if error.kind() == std::io::ErrorKind::AlreadyExists {
                    failure("project_initialize_conflict", "a Git directory appeared before allocation; no existing Git directory was initialized")
                } else { io_failure("cannot exclusively allocate new Git directory", error) }
            })?;
            let git_directory = open_at(&directory, ".git", libc::O_RDONLY | libc::O_DIRECTORY, 0)?;
            let metadata = git_directory
                .metadata()
                .map_err(|error| io_failure("cannot inspect new Git directory", error))?;
            if metadata.uid() != unsafe { libc::geteuid() } {
                return Err(failure(
                    "project_initialize_conflict",
                    "new Git directory ownership changed during initialization",
                ));
            }
            journal.git_identity = Some(Identity::of(&metadata));
            journal.phase = Phase::InitializingGit;
            save(&state, &filename, &journal)?;
        }
        ensure_owned(&parent_path, &journal)?;
        let git_directory = open_at(&directory, ".git", libc::O_RDONLY | libc::O_DIRECTORY, 0)?;
        if Identity::of(
            &git_directory
                .metadata()
                .map_err(|error| io_failure("cannot inspect recorded Git directory", error))?,
        ) != *journal.git_identity.as_ref().ok_or_else(|| {
            failure(
                "project_initialize_failed",
                "creation journal is missing Git ownership",
            )
        })? {
            return Err(failure(
                "project_initialize_conflict",
                "Git directory changed while opening it",
            ));
        }
        if journal.phase == Phase::InitializingGit {
            only_git_directory(&project_path, true)?;
            git(
                &git_directory,
                &[
                    "init",
                    "--quiet",
                    "--template=",
                    "--initial-branch=main",
                    "--object-format=sha1",
                ],
            )?;
            ensure_owned(&parent_path, &journal)?;
            journal.phase = Phase::GitInitialized;
            save(&state, &filename, &journal)?;
        }
        if journal.phase == Phase::GitInitialized {
            only_git_directory(&project_path, true)?;
            journal.git_head = Some(initial_head(&git_directory)?);
            journal.phase = Phase::GitReady;
            save(&state, &filename, &journal)?;
        }
        let head = journal
            .git_head
            .clone()
            .filter(|head| head.len() == 40 && head.bytes().all(|byte| byte.is_ascii_hexdigit()))
            .ok_or_else(|| {
                failure(
                    "project_initialize_failed",
                    "creation journal is missing its initial commit",
                )
            })?;
        ensure_owned(&parent_path, &journal)?;
        let project = register(&project_path, &name, request_id)?;
        ensure_owned(&parent_path, &journal)?;
        if project
            .get("id")
            .and_then(Value::as_str)
            .is_none_or(str::is_empty)
            || !project
                .get("roots")
                .and_then(Value::as_array)
                .is_some_and(|roots| {
                    roots.iter().any(|root| {
                        root.get("path")
                            .and_then(Value::as_str)
                            .is_some_and(|path| {
                                fs::canonicalize(path).is_ok_and(|path| path == project_path)
                            })
                    })
                })
        {
            return Err(failure(
                "app_server_protocol_error",
                "project registration returned no matching project root",
            ));
        }
        journal.project = Some(project.clone());
        journal.phase = Phase::Complete;
        save(&state, &filename, &journal)?;
        Ok(InitializedProject {
            project_path: project_path.clone(),
            name,
            git_head: head,
            request_id: request_id.to_owned(),
            project,
        })
    })();
    outcome.map_err(|mut error: BackendFailure| {
        if journal.directory_identity.is_some() {
            let guidance = if matches!(journal.phase, Phase::Allocated) && journal.git_identity.is_none() {
                "Retry this exact request only if no files or Git directory were added; inspect the retained directory if initialization cannot resume"
            } else {
                "Retry this exact request to resume the recorded initialization or registration"
            };
            error.message = format!("{}; project directory retained at {} (stage {:?}, request_id {}). {}; existing data will not be deleted", error.message, project_path.display(), journal.phase, request_id, guidance);
        }
        error
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::env;
    use std::os::unix::fs::{symlink, PermissionsExt};
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(1);
    const ID: &str = "fixture-request-00000001";

    struct Fixture {
        root: PathBuf,
        home: PathBuf,
        parent: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let root = env::temp_dir().join(format!(
                "codex-project-create-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let home = root.join("home");
            let parent = root.join("projects");
            fs::create_dir_all(&home).unwrap();
            fs::create_dir_all(&parent).unwrap();
            Self { root, home, parent }
        }
        fn create(
            &self,
            id: &str,
            register: impl FnOnce(&Path, &str, &str) -> Result<Value, BackendFailure>,
        ) -> Result<InitializedProject, BackendFailure> {
            initialize(
                &self.home,
                &self.parent,
                "new-project",
                Some("New project"),
                id,
                register,
            )
        }
        fn journal(&self) -> Journal {
            serde_json::from_slice(
                &fs::read(
                    self.home
                        .join("project-creation")
                        .join(format!("{ID}.json")),
                )
                .unwrap(),
            )
            .unwrap()
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }
    fn registered(path: &Path, _: &str, _: &str) -> Result<Value, BackendFailure> {
        Ok(json!({"id":"native-fixture-project", "roots":[{"path":path}]}))
    }

    #[test]
    fn new_project_has_an_empty_initial_commit_and_private_journal() {
        let fixture = Fixture::new();
        let result = fixture.create(ID, registered).unwrap();
        let directory = open_directory(&result.project_path.join(".git")).unwrap();
        assert_eq!(
            git(&directory, &["rev-parse", "HEAD^{tree}"]).unwrap(),
            EMPTY_TREE
        );
        assert_eq!(
            git(&directory, &["rev-list", "--count", "HEAD", "--"]).unwrap(),
            "1"
        );
        assert_eq!(git(&directory, &["status", "--porcelain"]).unwrap(), "");
        assert_eq!(fixture.journal().phase, Phase::Complete);
        assert_eq!(
            fs::metadata(fixture.home.join("project-creation"))
                .unwrap()
                .mode()
                & 0o777,
            0o700
        );
        assert_eq!(
            fs::metadata(
                fixture
                    .home
                    .join("project-creation")
                    .join(format!("{ID}.json"))
            )
            .unwrap()
            .mode()
                & 0o777,
            0o600
        );
    }

    #[test]
    fn retries_reuse_the_same_commit_and_immutable_request() {
        let fixture = Fixture::new();
        let first = fixture.create(ID, registered).unwrap();
        fs::write(first.project_path.join("user-file"), "preserve me").unwrap();
        let repeated = fixture.create(ID, registered).unwrap();
        assert_eq!(repeated.git_head, first.git_head);
        assert_eq!(
            fs::read_to_string(first.project_path.join("user-file")).unwrap(),
            "preserve me"
        );
        let error = initialize(
            &fixture.home,
            &fixture.parent,
            "different",
            None,
            ID,
            registered,
        )
        .unwrap_err();
        assert_eq!(error.code, "project_initialize_conflict");
        assert!(!fixture.parent.join("different").exists());
    }

    #[test]
    fn registration_failure_can_resume_without_reinitializing_git() {
        let fixture = Fixture::new();
        let error = fixture
            .create(ID, |_, _, key| {
                assert_eq!(key, ID);
                Err(failure(
                    "app_server_timeout",
                    "synthetic registration timeout",
                ))
            })
            .unwrap_err();
        assert_eq!(error.code, "app_server_timeout");
        assert!(error.message.contains("retained"));
        assert!(error.message.contains(ID));
        let head = fixture.journal().git_head.unwrap();
        assert_eq!(fixture.journal().phase, Phase::GitReady);
        let result = fixture.create(ID, registered).unwrap();
        assert_eq!(result.git_head, head);
    }

    #[test]
    fn existing_destinations_and_symlinks_are_never_claimed() {
        for kind in ["directory", "file", "symlink"] {
            let fixture = Fixture::new();
            let target = fixture.parent.join("new-project");
            match kind {
                "directory" => {
                    fs::create_dir(&target).unwrap();
                    fs::write(target.join("keep"), "content").unwrap();
                }
                "file" => fs::write(&target, "content").unwrap(),
                _ => symlink(&fixture.home, &target).unwrap(),
            }
            let error = fixture
                .create(ID, |_, _, _| panic!("registration must not run"))
                .unwrap_err();
            assert_eq!(error.code, "project_path_exists");
            assert!(!target.join(".git").exists());
            assert!(fixture.journal().directory_identity.is_none());
        }
    }

    #[test]
    fn invalid_components_and_missing_or_relative_parents_have_no_side_effects() {
        let fixture = Fixture::new();
        for leaf in [
            "",
            ".",
            "..",
            ".git",
            "../escape",
            "a/b",
            "a\\b",
            " space",
            "a\nb",
        ] {
            assert!(
                initialize(&fixture.home, &fixture.parent, leaf, None, ID, registered).is_err()
            );
        }
        assert!(initialize(
            &fixture.home,
            Path::new("relative"),
            "new-project",
            None,
            ID,
            registered
        )
        .is_err());
        assert!(initialize(
            &fixture.home,
            &fixture.parent.join("missing"),
            "new-project",
            None,
            ID,
            registered
        )
        .is_err());
        assert!(!fixture.home.join("project-creation").exists());
    }

    #[test]
    fn changed_directory_inode_is_rejected_on_resume() {
        let fixture = Fixture::new();
        let first = fixture.create(ID, registered).unwrap();
        fs::rename(
            &first.project_path,
            fixture.parent.join("retained-original"),
        )
        .unwrap();
        fs::create_dir(&first.project_path).unwrap();
        fs::write(first.project_path.join("keep"), "replacement content").unwrap();
        let error = fixture
            .create(ID, |_, _, _| panic!("replacement must not be registered"))
            .unwrap_err();
        assert_eq!(error.code, "project_initialize_conflict");
        assert_eq!(
            fs::read_to_string(first.project_path.join("keep")).unwrap(),
            "replacement content"
        );
        assert!(!first.project_path.join(".git").exists());
    }

    #[test]
    fn journal_and_state_symlinks_fail_closed() {
        let fixture = Fixture::new();
        let elsewhere = fixture.root.join("elsewhere");
        fs::create_dir(&elsewhere).unwrap();
        fs::set_permissions(&elsewhere, fs::Permissions::from_mode(0o700)).unwrap();
        symlink(&elsewhere, fixture.home.join("project-creation")).unwrap();
        assert!(fixture.create(ID, registered).is_err());
        assert!(fs::read_dir(&elsewhere).unwrap().next().is_none());
        fs::remove_file(fixture.home.join("project-creation")).unwrap();
        let state = state_directory(&fixture.home).unwrap();
        let external = fixture.root.join("external.json");
        fs::write(&external, "external content").unwrap();
        symlink(
            &external,
            fixture
                .home
                .join("project-creation")
                .join(format!("{ID}.json")),
        )
        .unwrap();
        assert!(load(&state, &format!("{ID}.json")).is_err());
        assert_eq!(fs::read_to_string(external).unwrap(), "external content");
    }

    #[test]
    fn request_and_destination_locks_reject_concurrent_initializers() {
        let fixture = Fixture::new();
        let state = state_directory(&fixture.home).unwrap();
        let held = lock(&state, &format!("request-{ID}.lock")).unwrap();
        assert_eq!(
            fixture.create(ID, registered).unwrap_err().code,
            "project_initialize_busy"
        );
        drop(held);
        let parent = Identity::of(&fs::metadata(&fixture.parent).unwrap());
        let leaf = b"new-project"
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>();
        let _held = lock(
            &state,
            &format!("root-{:x}-{:x}-{leaf}.lock", parent.dev, parent.ino),
        )
        .unwrap();
        assert_eq!(
            fixture.create(ID, registered).unwrap_err().code,
            "project_initialize_busy"
        );
        assert!(!fixture.parent.join("new-project").exists());
    }

    #[test]
    fn finished_operation_unlocks_even_if_a_child_retains_the_open_file_description() {
        let fixture = Fixture::new();
        let state = state_directory(&fixture.home).unwrap();
        let filename = format!("request-{ID}.lock");
        let held = lock(&state, &filename).unwrap();
        let inherited_description = held.file.try_clone().unwrap();
        assert_eq!(
            lock(&state, &filename).unwrap_err().code,
            "project_initialize_busy"
        );
        drop(held);
        let next = lock(&state, &filename).unwrap();
        // A live owner still excludes another caller, including after the inherited copy closes.
        drop(inherited_description);
        assert_eq!(
            lock(&state, &filename).unwrap_err().code,
            "project_initialize_busy"
        );
        drop(next);
        assert!(lock(&state, &filename).is_ok());
    }

    #[test]
    fn malformed_registration_result_is_not_marked_complete() {
        let fixture = Fixture::new();
        let error = fixture
            .create(ID, |_, _, _| Ok(json!({"id":"wrong", "roots":[]})))
            .unwrap_err();
        assert_eq!(error.code, "app_server_protocol_error");
        assert_eq!(fixture.journal().phase, Phase::GitReady);
        assert!(fixture.parent.join("new-project/.git").is_dir());
    }

    #[test]
    fn commit_written_before_journal_update_is_recovered_without_a_second_commit() {
        let fixture = Fixture::new();
        let first = fixture.create(ID, registered).unwrap();
        let mut journal = fixture.journal();
        journal.phase = Phase::GitInitialized;
        journal.git_head = None;
        journal.project = None;
        let state = state_directory(&fixture.home).unwrap();
        save(&state, &format!("{ID}.json"), &journal).unwrap();
        let resumed = fixture.create(ID, registered).unwrap();
        assert_eq!(resumed.git_head, first.git_head);
        assert_eq!(
            git(
                &open_directory(&first.project_path.join(".git")).unwrap(),
                &["rev-list", "--count", "HEAD", "--"]
            )
            .unwrap(),
            "1"
        );
    }

    #[test]
    fn unsafe_journal_phase_or_home_ancestor_symlink_is_rejected() {
        let fixture = Fixture::new();
        fixture.create(ID, registered).unwrap();
        let mut journal = fixture.journal();
        journal.phase = Phase::Allocated;
        let state = state_directory(&fixture.home).unwrap();
        save(&state, &format!("{ID}.json"), &journal).unwrap();
        assert_eq!(
            fixture.create(ID, registered).unwrap_err().code,
            "project_initialize_conflict"
        );
        let alias = fixture.root.join("alias");
        symlink(&fixture.root, &alias).unwrap();
        assert!(state_directory(&alias.join("home")).is_err());
    }

    #[test]
    fn replaced_directory_during_registration_is_not_marked_complete() {
        let fixture = Fixture::new();
        let error = fixture
            .create(ID, |path, _, _| {
                fs::rename(path, fixture.parent.join("retained-original")).unwrap();
                fs::create_dir(path).unwrap();
                registered(path, "", "")
            })
            .unwrap_err();
        assert_eq!(error.code, "project_initialize_conflict");
        assert_eq!(fixture.journal().phase, Phase::GitReady);
        assert!(!fixture.parent.join("new-project/.git").exists());
    }

    #[test]
    fn recorded_git_allocation_can_resume_before_init_writes_any_files() {
        let fixture = Fixture::new();
        fixture.create(ID, registered).unwrap();
        let mut journal = fixture.journal();
        journal.phase = Phase::InitializingGit;
        journal.git_head = None;
        journal.project = None;
        let git_path = fixture.parent.join("new-project/.git");
        fs::remove_dir_all(&git_path).unwrap();
        fs::create_dir(&git_path).unwrap();
        journal.git_identity = Some(Identity::of(&fs::metadata(&git_path).unwrap()));
        let state = state_directory(&fixture.home).unwrap();
        save(&state, &format!("{ID}.json"), &journal).unwrap();
        let resumed = fixture.create(ID, registered).unwrap();
        assert_eq!(
            git(&open_directory(&git_path).unwrap(), &["rev-parse", "HEAD"]).unwrap(),
            resumed.git_head
        );
    }

    #[test]
    fn git_directory_replacement_and_request_operation_mismatch_are_rejected() {
        let fixture = Fixture::new();
        fixture.create(ID, registered).unwrap();
        let git_path = fixture.parent.join("new-project/.git");
        fs::rename(&git_path, fixture.parent.join("retained-git")).unwrap();
        symlink(fixture.parent.join("retained-git"), &git_path).unwrap();
        assert_eq!(
            fixture.create(ID, registered).unwrap_err().code,
            "project_initialize_conflict"
        );
        let mut journal = fixture.journal();
        journal.operation = "different_operation".to_owned();
        let state = state_directory(&fixture.home).unwrap();
        save(&state, &format!("{ID}.json"), &journal).unwrap();
        assert_eq!(
            fixture.create(ID, registered).unwrap_err().code,
            "project_initialize_conflict"
        );
    }

    #[test]
    fn git_command_disables_external_configuration_hooks_and_signing() {
        let fixture = Fixture::new();
        let parent = open_directory(&fixture.parent).unwrap();
        let command = safe_git(&parent);
        let environment = command
            .get_envs()
            .filter_map(|(key, value)| {
                value.map(|value| {
                    (
                        key.to_string_lossy().into_owned(),
                        value.to_string_lossy().into_owned(),
                    )
                })
            })
            .collect::<std::collections::HashMap<_, _>>();
        assert_eq!(environment["GIT_CONFIG_NOSYSTEM"], "1");
        assert_eq!(environment["GIT_CONFIG_GLOBAL"], "/dev/null");
        assert_eq!(environment["GIT_CONFIG_SYSTEM"], "/dev/null");
        let args = command
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect::<Vec<_>>();
        assert!(args.contains(&"core.hooksPath=/dev/null".to_owned()));
        assert!(args.contains(&"commit.gpgSign=false".to_owned()));
        assert!(args.contains(&"core.fsmonitor=false".to_owned()));
    }
}
