#!/usr/bin/env python3
"""Audit publication content without printing matching secret values (Python 3.10+)."""
import argparse
import hashlib
import io
import ipaddress
import json
import re
import subprocess
import zipfile
from pathlib import Path

FORBIDDEN = re.compile(r"(^|/)(?:\.private|\.gradle|target|build|outputs|node_modules|local\.properties|auth\.json|sessions|rollouts)(?:/|$)|\.(?:apk|aab|jks|keystore|log|sqlite|sqlite3|db|bundle)$")
PATTERNS = {
    "private-key": re.compile(rb"-----BEGIN (?:OPENSSH |RSA |EC |DSA )?PRIVATE KEY-----"),
    "access-token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-(?:proj-)?[A-Za-z0-9_-]{24,})"),
    "private-home": re.compile(rb"/root/(?:y2yy-codex|\.codex-bridge/maintenance)|/www/" + rb"wwwroot/"),
}
EMAIL = re.compile(rb"[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\.[A-Za-z]{2,})")
NUMERIC_URL = re.compile(rb"https?://((?:\d{1,3}\.){3}\d{1,3})(?=[:/\s'\"]|$)")
HOME = re.compile(rb"(?:/" + rb"Users/|/" + rb"home/|[A-Z]:[\\/]Users[\\/])([^/\\\s'\"<>]+)")
EXAMPLE_USERS = {b"yourname", b"user", b"username", b"example", b"alice", b"bob", b"fixture", b"test", b"tester", b"runner", b"linuxbrew", b"demo"}
# Reviewed synthetic fixture inherited from upstream; changes require another review.
REVIEWED_FIXTURES = {
    "fixtures/codex-home/sessions/2026/08/30/rollout-2026-08-30T09-00-00-00000000-0000-7000-8000-000000000001.jsonl":
        "97242208997d26087c34b7c3a948198429d0f40e7b1428762f98df8d42478bcf"
}


def git(repo, *args, data=None):
    return subprocess.check_output(["git", "-C", str(repo), *args], input=data)


def inspect(data, label, deny_values=()):
    findings = set()
    for kind, pattern in PATTERNS.items():
        if pattern.search(data):
            findings.add((kind, label))
    if any(value and value in data for value in deny_values):
        findings.add(("private-config-value", label))
    for match in EMAIL.finditer(data):
        domain = match[1].lower()
        if domain not in {b"example.com", b"example.invalid", b"example.test", b"users.noreply.github.com"} and not domain.endswith(b".example.com"):
            findings.add(("email-review", label))
    for match in HOME.finditer(data):
        if match[1].lower() not in EXAMPLE_USERS and not match[1].startswith((b"$", b"{", b"your")):
            findings.add(("home-path-review", label))
    for match in NUMERIC_URL.finditer(data):
        try:
            address = ipaddress.ip_address(match[1].decode())
        except ValueError:
            continue
        if not address.is_loopback and not address.is_unspecified and not any(address in ipaddress.ip_network(n) for n in ("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")):
            findings.add(("server-address-review", label))
    return findings


def audit(repo, base=None, deny_values=()):
    findings = set()
    for raw in git(repo, "ls-files", "-z").split(b"\0"):
        if not raw:
            continue
        name = raw.decode()
        data = git(repo, "show", ":" + name)
        if FORBIDDEN.search(name) and REVIEWED_FIXTURES.get(name) != hashlib.sha256(data).hexdigest():
            findings.add(("private-or-generated-file", name))
        findings.update(inspect(data, name, deny_values))
        if name.endswith(".jar"):
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                for item in archive.namelist():
                    findings.update(inspect(archive.read(item), name + "!" + item, deny_values))
    if base:
        # A clean final tree is insufficient: scan deleted or superseded blobs too.
        for line in git(repo, "rev-list", "--objects", base + "..HEAD").splitlines():
            oid = line.split(b" ", 1)[0].decode()
            if git(repo, "cat-file", "-t", oid).strip() == b"blob":
                findings.update(inspect(git(repo, "cat-file", "blob", oid), "blob:" + oid, deny_values))
    return [{"kind": kind, "location": label} for kind, label in sorted(findings)]


def require_clean(repo, base=None, deny_values=()):
    findings = audit(repo, base, deny_values)
    if findings:
        raise RuntimeError("Publication audit rejected locations: " + json.dumps(findings))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--base")
    args = parser.parse_args()
    result = audit(Path(args.repo), args.base)
    print(json.dumps({"findings": result}, indent=2))
    raise SystemExit(bool(result))
