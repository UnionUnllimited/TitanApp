#!/usr/bin/env python3
"""
Titan VPS: Naive + Mieru users from Remnawave.

Runs on a node with Caddy (forwardproxy@naive) and/or mita (Mieru). Every run it reads
the active users from the Remnawave API and writes them as logins for both protocols:

    login = password = the user's VLESS UUID (what the Titan VPS app already has)

so only active subscriptions can connect; expired/disabled ones drop out on the next run.
Files are rewritten and the services reloaded only when the user list changed.

Settings: /etc/titan-sync.env (never in this repo)
    REMNAWAVE_URL=https://panel.example.com      # Remnawave panel (API) address
    REMNAWAVE_TOKEN=...                          # Remnawave → API Tokens
    NAIVE_USERS_FILE=/etc/caddy/naive-users.conf # empty to skip Naive
    MITA_CONFIG=/etc/mita-server.json            # empty to skip Mieru
    EXTRA_NAIVE_USERS=                           # "name:pass name2:pass2", e.g. for tests
    EXTRA_MIERU_USERS=
    ONLY_SQUADS=                                 # optional: internal squad UUIDs, comma-separated

Run: python3 titan-node-sync.py  (a systemd timer runs it every minute)
"""

import hashlib
import json
import os
import subprocess
import sys
import urllib.parse
import urllib.request

ENV_FILE = "/etc/titan-sync.env"
STATE_FILE = "/var/lib/titan-sync/state"
PAGE = 500


def load_env(path):
    env = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        sys.exit(f"no {path}")
    return env


def api_get(base, token, path):
    req = urllib.request.Request(
        base.rstrip("/") + path,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/json", "User-Agent": "titan-node-sync"},
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def active_users(base, token, only_squads):
    """VLESS UUIDs of users with status ACTIVE (optionally only in the given squads)."""
    out, start = [], 0
    while True:
        data = api_get(base, token, "/api/users?" + urllib.parse.urlencode({"start": start, "size": PAGE}))
        resp = data.get("response", data)
        users = resp.get("users", [])
        for u in users:
            if str(u.get("status", "")).upper() != "ACTIVE":
                continue
            if only_squads:
                squads = {s.get("uuid") if isinstance(s, dict) else s for s in (u.get("activeInternalSquads") or [])}
                if not squads & only_squads:
                    continue
            vless = u.get("vlessUuid") or u.get("vless_uuid")
            if vless:
                out.append(str(vless).lower())
        start += len(users)
        total = resp.get("total", start)
        if not users or start >= total:
            break
    return sorted(set(out))


def extra(spec):
    pairs = []
    for item in (spec or "").split():
        if ":" in item:
            name, pw = item.split(":", 1)
            pairs.append((name, pw))
    return pairs


def write_if_changed(path, content):
    try:
        with open(path, encoding="utf-8") as f:
            if f.read() == content:
                return False
    except FileNotFoundError:
        pass
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(content)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)
    return True


def sync_naive(path, uuids, extras):
    lines = [f"basic_auth {u} {u}" for u in uuids] + [f"basic_auth {n} {p}" for n, p in extras]
    if not lines:
        # Caddy needs at least one credential, or forward_proxy becomes open to everyone.
        lines = [f"basic_auth disabled-{os.urandom(8).hex()} {os.urandom(16).hex()}"]
    if write_if_changed(path, "\n".join(lines) + "\n"):
        subprocess.run(["systemctl", "reload", "caddy-naive"], check=False)
        return True
    return False


def sync_mieru(path, uuids, extras):
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        print(f"mieru: no {path}, skipped")
        return False
    users = [{"name": u, "password": u} for u in uuids] + [{"name": n, "password": p} for n, p in extras]
    if not users:
        users = [{"name": "disabled-" + os.urandom(8).hex(), "password": os.urandom(16).hex()}]
    if cfg.get("users") == users:
        return False
    cfg["users"] = users
    write_if_changed(path, json.dumps(cfg, indent=2) + "\n")
    subprocess.run(["mita", "apply", "config", path], check=False)
    # Picks up the new users without dropping the server.
    if subprocess.run(["mita", "reload"], check=False).returncode != 0:
        subprocess.run(["mita", "stop"], check=False)
        subprocess.run(["mita", "start"], check=False)
    return True


def main():
    env = load_env(ENV_FILE)
    base, token = env.get("REMNAWAVE_URL", ""), env.get("REMNAWAVE_TOKEN", "")
    if not base or not token:
        sys.exit("REMNAWAVE_URL and REMNAWAVE_TOKEN are required")
    only = {s.strip() for s in env.get("ONLY_SQUADS", "").split(",") if s.strip()}

    try:
        uuids = active_users(base, token, only)
    except Exception as e:  # never wipe users because the panel is briefly unreachable
        sys.exit(f"remnawave api: {e}")

    digest = hashlib.sha256("\n".join(uuids).encode()).hexdigest()[:12]
    changed = []
    naive_file = env.get("NAIVE_USERS_FILE", "/etc/caddy/naive-users.conf")
    if naive_file and sync_naive(naive_file, uuids, extra(env.get("EXTRA_NAIVE_USERS"))):
        changed.append("naive")
    mita_cfg = env.get("MITA_CONFIG", "/etc/mita-server.json")
    if mita_cfg and sync_mieru(mita_cfg, uuids, extra(env.get("EXTRA_MIERU_USERS"))):
        changed.append("mieru")

    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, "w") as f:
        f.write(digest)
    print(f"active users: {len(uuids)} [{digest}]" + (f", updated: {', '.join(changed)}" if changed else ", no changes"))


if __name__ == "__main__":
    main()
