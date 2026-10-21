#!/usr/bin/env python3
"""Finish trusted synchronization PRs after all required hosted jobs pass."""
import argparse
import json
from update_upstream import gh, refresh_pending

OWNED_BRANCHES = {"sync/bridge", "sync/android", "automation/upstream"}


def finalize(repository, branch, tested_head):
    if branch not in OWNED_BRANCHES:
        raise RuntimeError("Only managed synchronization branches may be finalized")
    prs = json.loads(gh("pr", "list", "--repo", repository, "--head", branch, "--base", "main", "--state", "open", "--json", "number"))
    if not prs:
        return {"status": "no-open-pr"}
    number = str(prs[0]["number"])
    pr = json.loads(gh("pr", "view", number, "--repo", repository, "--json", "headRefOid,mergeStateStatus,autoMergeRequest"))
    if pr["headRefOid"] != tested_head:
        return {"status": "stale-checks"}
    if not pr["autoMergeRequest"]:
        return {"status": "auto-merge-not-requested"}
    if pr["mergeStateStatus"] == "BEHIND":
        gh("pr", "update-branch", number, "--repo", repository)
        gh("workflow", "run", "validate-fork.yml", "--repo", repository, "--ref", branch)
        return {"status": "retesting-updated-branch"}
    # No admin bypass: repository protection still enforces checks and exact tested HEAD.
    gh("pr", "merge", number, "--repo", repository, "--merge", "--match-head-commit", tested_head)
    refresh_pending(repository, dispatch=True)
    return {"status": "merged", "pr": int(number)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--tested-head", required=True)
    args = parser.parse_args()
    print(json.dumps(finalize(args.repository, args.branch, args.tested_head)))
