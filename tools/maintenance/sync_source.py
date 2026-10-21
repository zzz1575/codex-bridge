#!/usr/bin/env python3
"""Publish committed component diffs through CI-gated PRs; never deploy services."""
import argparse
import fcntl
import json
import os
import re
import subprocess
import time
from pathlib import Path
from privacy import git, require_clean


def run(args, cwd=None, data=None):
    result = subprocess.run(args, cwd=cwd, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Tool output may contain source text. Keep private details in the private log.
        raise RuntimeError("Command failed: " + args[0] + " (exit " + str(result.returncode) + ")")
    return result.stdout


def atomic_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, indent=2) + "\n")
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def load(path, default):
    return json.loads(Path(path).read_text()) if Path(path).exists() else default


def gh(config, *args):
    return run(["gh", *args])


def patch(repo, old, new):
    # Includes deletes, renames and binaries; untracked files and work in progress never enter.
    run(["git", "-C", str(repo), "merge-base", "--is-ancestor", old, new])
    return git(repo, "diff", "--binary", "--full-index", old, new, "--", ".")


def apply(repo, diff, prefix=""):
    if not diff:
        return
    command = ["git", "-C", str(repo), "apply", "--3way", "--index"]
    if prefix:
        command.append("--directory=" + prefix)
    run(command, data=diff)


def secret_values(config):
    values = [s.encode() for s in config.get("deny_values", []) if s]
    for filename in config.get("private_json_files", []):
        data = load(filename, {})
        for key in ("url", "password", "token", "api_key"):
            value = data.get(key)
            if isinstance(value, str) and len(value) >= 8:
                values.append(value.encode())
    return values


def validate_config(config):
    repository = Path(config["publication_repo"]).resolve()
    for source in config["components"].values():
        if repository == Path(source["repo"]).resolve():
            raise RuntimeError("Source and publication repositories must be different")
    marker = repository / ".git" / "source-sync-owned"
    if not marker.is_file() or marker.read_text().strip() != "dedicated-publication-scratch":
        raise RuntimeError("Publication clone ownership marker is missing")


def sync(config, component):
    validate_config(config)
    root = Path(config["state_dir"])
    statefile = root / "state.json"
    state = load(statefile, {})
    entry = state[component]
    source = config["components"][component]
    source_repo = Path(source["repo"])
    pending = entry.get("pending")
    if pending:
        status = json.loads(gh(config, "pr", "view", str(pending["pr"]), "--repo", config["repository"], "--json", "state,mergedAt,mergeStateStatus,autoMergeRequest"))
        if status["state"] == "MERGED":
            entry["synced_source"] = pending["source"]
            entry.pop("pending")
            atomic_json(statefile, state)
            pending = None
        elif status["state"] == "CLOSED":
            raise RuntimeError("Previous source PR was closed without merging; manual reconciliation is required")
    target = git(source_repo, "rev-parse", source.get("ref", "HEAD")).decode().strip()
    if target == entry["synced_source"]:
        return {"component": component, "status": "up-to-date"}
    if pending and pending["source"] == target:
        if status.get("mergeStateStatus") == "BEHIND" and status.get("autoMergeRequest"):
            gh(config, "pr", "update-branch", str(pending["pr"]), "--repo", config["repository"])
        return {"component": component, "status": "waiting-for-checks", "pr": pending["pr"]}
    delta = patch(source_repo, entry["synced_source"], target)
    repository = Path(config["publication_repo"])
    git(repository, "fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main")
    base = git(repository, "rev-parse", "origin/main").decode().strip()
    branch = "sync/" + component
    remote_ref = git(repository, "ls-remote", "origin", "refs/heads/" + branch).decode().split()
    expected_remote = remote_ref[0] if remote_ref else ""
    # The publication clone is dedicated to this worker. It must never be a deployed checkout.
    if repository.resolve() == source_repo.resolve():
        raise RuntimeError("Source and publication repositories must be different")
    git(repository, "checkout", "--quiet", "-B", branch, base)
    apply(repository, delta, source.get("prefix", ""))
    require_clean(repository, base, secret_values(config))
    git(repository, "diff", "--cached", "--check")
    if not git(repository, "diff", "--cached", "--name-only").strip():
        # Same patch was already integrated by another path. No overwrite or duplicate commit.
        entry["synced_source"] = target
        entry.pop("pending", None)
        atomic_json(statefile, state)
        return {"component": component, "status": "already-integrated"}
    git(repository, "-c", "user.name=" + config["author_name"], "-c", "user.email=" + config["author_email"], "commit", "--quiet", "-m", "sync: update " + component + " source")
    require_clean(repository, base, secret_values(config))
    lease = "--force-with-lease=refs/heads/" + branch + ":" + expected_remote
    git(repository, "push", "--quiet", lease, "origin", "HEAD:refs/heads/" + branch)
    prs = json.loads(gh(config, "pr", "list", "--repo", config["repository"], "--head", branch, "--base", "main", "--state", "open", "--json", "number,url"))
    if prs:
        pr = prs[0]
    else:
        body = "Synchronize committed " + component + " changes through an isolated publication copy. Three-way application preserves fork-only files; conflicts stop publication. Privacy, Bridge and Android checks are required before merging. This PR does not deploy or restart any service."
        gh(config, "pr", "create", "--repo", config["repository"], "--head", branch, "--base", "main", "--title", "sync: update " + component + " source", "--body", body)
        pr = json.loads(gh(config, "pr", "view", branch, "--repo", config["repository"], "--json", "number,url"))
    # Save before enabling auto-merge; a transient API failure must not lose the source checkpoint.
    entry["pending"] = {"source": target, "pr": pr["number"], "branch": branch}
    atomic_json(statefile, state)
    gh(config, "pr", "merge", str(pr["number"]), "--repo", config["repository"], "--auto", "--merge")
    return {"component": component, "status": "waiting-for-checks", "pr": pr["number"], "url": pr["url"]}


def receive(config, filename):
    if not re.fullmatch(r"android-[0-9a-f]{32}\.bundle", filename):
        raise RuntimeError("Invalid incoming bundle name")
    bundle = Path(config["incoming_dir"]) / filename
    if bundle.is_symlink() or not bundle.is_file() or bundle.stat().st_size > 64 * 1024 * 1024:
        raise RuntimeError("Invalid incoming bundle")
    source = Path(config["components"]["android"]["repo"])
    git(source, "bundle", "verify", str(bundle))
    git(source, "fetch", "--quiet", str(bundle), "+refs/source-sync/export:refs/heads/received")
    target = git(source, "rev-parse", "refs/heads/received").decode().strip()
    baseline = load(Path(config["state_dir"]) / "state.json", {})["android"]["synced_source"]
    git(source, "merge-base", "--is-ancestor", baseline, target)
    git(source, "update-ref", "refs/heads/source", target)
    bundle.unlink()
    request(config, "android")
    return {"component": "android", "status": "queued"}


def request(config, component):
    if component not in config["components"]:
        raise RuntimeError("Unknown component")
    directory = Path(config["state_dir"]) / "requests"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / (component + ".request")).touch()


def drain(config):
    validate_config(config)
    root = Path(config["state_dir"])
    root.mkdir(parents=True, exist_ok=True)
    with (root / "worker.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # A lightweight timer may invoke drain to reconcile already-open PRs. Do not
        # repeatedly retry a blocked publication or scan/fetch idle components.
        for component, entry in load(root / "state.json", {}).items():
            status = load(root / (component + "-status.json"), {})
            if entry.get("pending") and status.get("status") != "blocked":
                request(config, component)
        for _ in range(10):
            requests = sorted((root / "requests").glob("*.request"))
            if not requests:
                break
            for path in requests:
                component = path.stem
                path.unlink()
                try:
                    result = sync(config, component)
                except Exception as error:
                    result = {"component": component, "status": "blocked", "error": str(error)}
                    # Discard only the dedicated scratch clone's conflicted work, never the source.
                    repo = Path(config["publication_repo"])
                    git(repo, "reset", "--hard", "--quiet", "origin/main")
                result["updated_at"] = int(time.time())
                atomic_json(root / (component + "-status.json"), result)
                print(json.dumps(result), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    parser.add_argument("command", choices=["request", "receive-android", "drain"])
    parser.add_argument("value", nargs="?")
    args = parser.parse_args()
    configuration = load(args.config, {})
    if args.command == "drain":
        drain(configuration)
    elif args.command == "receive-android":
        print(json.dumps(receive(configuration, args.value)))
    else:
        request(configuration, args.value)
