#!/usr/bin/env python3
"""Merge upstream into a disposable checkout, then request CI-gated integration."""
import argparse
import json
import os
import subprocess
from pathlib import Path
from privacy import git, require_clean
from sync_source import run


def gh(*args):
    return run(["gh", *args])


def refresh_pending(repository, dispatch=False):
    prs = json.loads(gh("pr", "list", "--repo", repository, "--state", "open", "--json", "number,headRefName,mergeStateStatus"))
    for pr in prs:
        if pr["headRefName"] not in {"automation/upstream", "sync/bridge", "sync/android"}:
            continue
        if pr["mergeStateStatus"] == "BEHIND":
            gh("pr", "update-branch", str(pr["number"]), "--repo", repository)
            if dispatch:
                gh("workflow", "run", "validate-fork.yml", "--repo", repository, "--ref", pr["headRefName"])


def update(repo, repository, dispatch=False):
    if git(repo, "status", "--porcelain").strip():
        raise RuntimeError("Upstream maintenance requires a clean disposable checkout")
    refresh_pending(repository, dispatch)
    git(repo, "fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main")
    git(repo, "fetch", "--quiet", "https://github.com/hitsmaxft/codex-bridge.git", "+refs/heads/main:refs/remotes/upstream/main")
    base = git(repo, "rev-parse", "origin/main").decode().strip()
    upstream = git(repo, "rev-parse", "upstream/main").decode().strip()
    if subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", upstream, base], stdout=subprocess.DEVNULL).returncode == 0:
        return {"status": "up-to-date", "upstream": upstream}
    branch = "automation/upstream"
    old = git(repo, "ls-remote", "origin", "refs/heads/" + branch).decode().split()
    git(repo, "checkout", "--quiet", "-B", branch, base)
    git(repo, "config", "user.name", "github-actions[bot]")
    git(repo, "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    result = subprocess.run(["git", "-C", str(repo), "merge", "--no-ff", "--no-commit", upstream], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        names = git(repo, "diff", "--name-only", "--diff-filter=U").decode().splitlines()
        git(repo, "merge", "--abort")
        raise RuntimeError("Upstream merge stopped; resolve these files in a review branch: " + ", ".join(names))
    require_clean(repo, base)
    git(repo, "diff", "--cached", "--check")
    git(repo, "commit", "--quiet", "-m", "Merge upstream Codex Bridge updates")
    require_clean(repo, base)
    git(repo, "push", "--quiet", "--force-with-lease=refs/heads/" + branch + ":" + (old[0] if old else ""), "origin", "HEAD:refs/heads/" + branch)
    prs = json.loads(gh("pr", "list", "--repo", repository, "--head", branch, "--state", "open", "--json", "number,url"))
    if not prs:
        gh("pr", "create", "--repo", repository, "--base", "main", "--head", branch, "--title", "maintenance: merge upstream Bridge updates", "--body", "Merge upstream history while retaining this fork's Bridge customizations and native Android source. Conflicts stop the workflow. Privacy, Bridge and Android checks must pass before merging. No service deployment is performed.")
    pr = json.loads(gh("pr", "view", branch, "--repo", repository, "--json", "number,url"))
    gh("pr", "merge", str(pr["number"]), "--repo", repository, "--auto", "--merge")
    if dispatch:
        # GITHUB_TOKEN writes do not normally trigger another workflow. Dispatch explicitly.
        gh("workflow", "run", "validate-fork.yml", "--repo", repository, "--ref", branch)
    return {"status": "waiting-for-checks", "upstream": upstream, "pr": pr["number"], "url": pr["url"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--repository", required=True)
    parser.add_argument("--dispatch-validation", action="store_true")
    parser.add_argument("--refresh-only", action="store_true")
    args = parser.parse_args()
    if args.refresh_only:
        refresh_pending(args.repository, args.dispatch_validation)
    else:
        result = update(Path(args.repo), args.repository, args.dispatch_validation)
        print(json.dumps(result))
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
                summary.write("Upstream maintenance: `" + result["status"] + "`\n")
