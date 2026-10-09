#!/usr/bin/env python3
"""
Titan VPS: Naive + Mieru + MASQUE users from Remnawave.

Runs on a node with Caddy (forwardproxy@naive), mita (Mieru) and/or sing-box (MASQUE). Every run it reads
the active users from the Remnawave API and writes them as logins for both protocols:

    secret = the user's credential from Remnawave: the VLESS UUID, or the Shadowsocks
    password when the Naive/Mieru hosts sit on Shadowsocks placeholder inbounds
    (CREDENTIAL=ss) — whatever the app finds in that host's outbound;
    h = sha256("titan:" + secret) as hex; login = h[0:16], password = h[16:48]
    (URL-safe for naive links; the app derives the same, see app core/Plugins.kt)

so only active subscriptions can connect; expired/disabled ones drop out on the next run.
Files are rewritten and the services reloaded only when the user list changed.

Settings: /etc/titan-sync.env (never in this repo)
    REMNAWAVE_URL=https://panel.example.com      # Remnawave panel (API) address
    REMNAWAVE_TOKEN=...                          # Remnawave → API Tokens
    NAIVE_USERS_FILE=/etc/caddy/naive-users.conf # empty to skip Naive
    MITA_CONFIG=/etc/mita-server.json            # empty to skip Mieru
    EXTRA_NAIVE_USERS=                           # "name:pass name2:pass2", e.g. for tests
    EXTRA_MIERU_USERS=
    MASQUE_CONFIG=                               # e.g. /etc/masque/config.json; empty to skip MASQUE
    EXTRA_MASQUE_USERS=
    GATEWAY_TEMPLATE=                            # sing-box gateway for Naive (accounted), see below
    GATEWAY_CONFIG=/etc/titan-gateway/config.json
    EXTRA_GATEWAY_USERS=                         # "name:pass", not accounted (go out directly)
    MASQUE_GATEWAY_TEMPLATE=                     # Xray gateway for MASQUE (accounted), see below
    MASQUE_GATEWAY_CONFIG=/etc/titan-masque/config.json
    MIERU_GATEWAY_TEMPLATE=                      # titan-mieru-gw (server/titan-mieru-gw), see below
    MIERU_GATEWAY_CONFIG=/etc/titan-mieru/config.json

Gateways (accounting): config templates whose server entries get every user, plus a
"titan" block mapping each entry's tag to the node's Xray placeholder (Shadowsocks on
127.0.0.1, managed by Remnawave):
    "titan": {"method": "chacha20-ietf-poly1305", "xray": {"naive-in": "127.0.0.1:61001"}}
Each user's traffic leaves through that placeholder with the user's own Shadowsocks
password, so Remnawave counts it, shows the connections and applies the node's rules.
Needs CREDENTIAL=ss (the secret is that password).
  - GATEWAY_TEMPLATE: sing-box; its naive inbounds (and masque-server endpoints, though
    sing-box doesn't tell MASQUE users apart, so those can't be accounted).
  - MASQUE_GATEWAY_TEMPLATE: Xray 26.9.30+; its masque inbounds (Xray knows the user).
  - MIERU_GATEWAY_TEMPLATE: titan-mieru-gw settings (listen, portRange, xray, method,
    ipsFile); the users are added here. The gateway re-reads the file by itself.
    ONLY_SQUADS=                                 # optional: internal squad UUIDs, comma-separated
    CREDENTIAL=vless                             # vless (vlessUuid) or ss (ssPassword)

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


CREDENTIAL_FIELDS = {"vless": ("vlessUuid", "vless_uuid"), "ss": ("ssPassword", "ss_password")}


def active_users(base, token, only_squads, credential):
    """Credentials of users with status ACTIVE (optionally only in the given squads)."""
    fields = CREDENTIAL_FIELDS[credential]
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
            value = next((u.get(f) for f in fields if u.get(f)), None)
            if value:
                # UUIDs are compared lowercase; passwords as they are.
                out.append(str(value).lower() if credential == "vless" else str(value))
        start += len(users)
        total = resp.get("total", start)
        if not users or start >= total:
            break
    return sorted(set(out))


def derive(secret):
    h = hashlib.sha256(("titan:" + secret).encode()).hexdigest()
    return h[:16], h[16:48]


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
    lines = [f"basic_auth {u} {p}" for u, p in map(derive, uuids)] + [f"basic_auth {n} {p}" for n, p in extras]
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
    users = [{"name": u, "password": p} for u, p in map(derive, uuids)] + [{"name": n, "password": p} for n, p in extras]
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


def sync_masque(path, uuids, extras):
    """users of every masque-server endpoint in the sing-box config; reloads it on change."""
    try:
        with open(path, encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        print(f"masque: no {path}, skipped")
        return False
    users = [{"username": u, "password": p} for u, p in map(derive, uuids)] + [
        {"username": n, "password": p} for n, p in extras
    ]
    if not users:
        # No users means no authentication in sing-box: never leave it open.
        users = [{"username": "disabled-" + os.urandom(8).hex(), "password": os.urandom(16).hex()}]
    changed = False
    for endpoint in cfg.get("endpoints", []):
        if endpoint.get("type") == "masque-server" and endpoint.get("users") != users:
            endpoint["users"] = users
            changed = True
    if not changed:
        return False
    write_if_changed(path, json.dumps(cfg, indent=2) + "\n")
    # SIGHUP: sing-box reloads the config (ExecReload in masque.service).
    if subprocess.run(["systemctl", "reload", "masque"], check=False).returncode != 0:
        subprocess.run(["systemctl", "restart", "masque"], check=False)
    return True


def build_gateway(template, secrets, extras):
    """The sing-box config: users on every naive/masque entry, per-user Shadowsocks out to Xray."""
    cfg = json.loads(json.dumps(template))
    titan = cfg.pop("titan", {})
    method = titan.get("method", "chacha20-ietf-poly1305")
    xray = titan.get("xray", {})
    pairs = [(derive(s), s) for s in secrets]
    users = [{"username": u, "password": p} for (u, p), _ in pairs] + [
        {"username": n, "password": p} for n, p in extras
    ]
    if not users:
        users = [{"username": "disabled-" + os.urandom(8).hex(), "password": os.urandom(16).hex()}]
    entries = [i for i in cfg.get("inbounds", []) if i.get("type") == "naive"] + [
        e for e in cfg.get("endpoints", []) if e.get("type") == "masque-server"
    ]
    outbounds = [o for o in cfg.get("outbounds", []) if not o.get("tag", "").startswith("u-")]
    tags = {o.get("tag") for o in outbounds}
    if "direct" not in tags:
        outbounds.append({"type": "direct", "tag": "direct"})
    if "block" not in tags:
        outbounds.append({"type": "block", "tag": "block"})
    rules = []
    for entry in entries:
        entry["users"] = users
        tag = entry["tag"]
        target = xray.get(tag)
        if not target:
            continue  # not accounted: everyone goes out directly
        host, port = target.rsplit(":", 1)
        for (login, _), secret in pairs:
            out = f"u-{tag}-{login}"
            outbounds.append({"type": "shadowsocks", "tag": out, "server": host, "server_port": int(port),
                              "method": method, "password": secret})
            rules.append({"inbound": [tag], "auth_user": [login], "outbound": out})
    # Test users (EXTRA_GATEWAY_USERS) have no Remnawave account: straight out.
    if extras:
        rules.append({"auth_user": [n for n, _ in extras], "outbound": "direct"})
    route = cfg.setdefault("route", {})
    route["rules"] = rules + [r for r in route.get("rules", []) if "auth_user" not in r]
    # Unknown users never reach the internet unaccounted.
    route["final"] = "block" if xray else route.get("final", "direct")
    cfg["outbounds"] = outbounds
    return cfg


def build_xray_masque(template, secrets):
    """The Xray MASQUE gateway: users on every masque inbound, per-user Shadowsocks out."""
    cfg = json.loads(json.dumps(template))
    titan = cfg.pop("titan", {})
    method = titan.get("method", "chacha20-ietf-poly1305")
    xray = titan.get("xray", {})
    pairs = [(derive(s), s) for s in secrets]
    clients = [{"email": u, "pass": p} for (u, p), _ in pairs]
    if not clients:
        clients = [{"email": "disabled-" + os.urandom(8).hex(), "pass": os.urandom(16).hex()}]
    outbounds = [o for o in cfg.get("outbounds", []) if not o.get("tag", "").startswith("u-")]
    if not any(o.get("tag") == "block" for o in outbounds):
        outbounds.append({"tag": "block", "protocol": "blackhole"})
    rules = []
    for inbound in cfg.get("inbounds", []):
        if inbound.get("protocol") != "masque":
            continue
        inbound.setdefault("settings", {})["clients"] = clients
        tag = inbound["tag"]
        target = xray.get(tag)
        if not target:
            continue
        host, port = target.rsplit(":", 1)
        for (login, _), secret in pairs:
            out = f"u-{tag}-{login}"
            outbounds.append({"tag": out, "protocol": "shadowsocks", "settings": {"servers": [
                {"address": host, "port": int(port), "method": method, "password": secret}]}})
            rules.append({"inboundTag": [tag], "user": [login], "outboundTag": out})
    routing = cfg.setdefault("routing", {})
    # Unknown users never reach the internet unaccounted.
    routing["rules"] = rules + [{"network": "tcp,udp", "outboundTag": "block"}]
    cfg["outbounds"] = outbounds
    return cfg


def sync_xray_masque(template_path, path, secrets):
    try:
        with open(template_path, encoding="utf-8") as f:
            template = json.load(f)
    except FileNotFoundError:
        print(f"masque gateway: no {template_path}, skipped")
        return False
    content = json.dumps(build_xray_masque(template, secrets), indent=1) + "\n"
    if not write_if_changed(path, content):
        return False
    # Xray has no config reload: a restart (tunnels reconnect within seconds).
    subprocess.run(["systemctl", "restart", "titan-masque"], check=False)
    return True


def sync_mieru_gateway(template_path, path, secrets):
    try:
        with open(template_path, encoding="utf-8") as f:
            cfg = json.load(f)
    except FileNotFoundError:
        print(f"mieru gateway: no {template_path}, skipped")
        return False
    cfg["users"] = [{"name": u, "password": p, "secret": s} for (u, p), s in ((derive(s), s) for s in secrets)]
    if not cfg["users"]:
        return False  # the gateway keeps its last users rather than open up
    return write_if_changed(path, json.dumps(cfg, indent=1) + "\n")


def sync_gateway(template_path, path, secrets, extras):
    try:
        with open(template_path, encoding="utf-8") as f:
            template = json.load(f)
    except FileNotFoundError:
        print(f"gateway: no {template_path}, skipped")
        return False
    content = json.dumps(build_gateway(template, secrets, extras), indent=1) + "\n"
    if not write_if_changed(path, content):
        return False
    if subprocess.run(["systemctl", "reload", "titan-gateway"], check=False).returncode != 0:
        subprocess.run(["systemctl", "restart", "titan-gateway"], check=False)
    return True


def main():
    env = load_env(ENV_FILE)
    base, token = env.get("REMNAWAVE_URL", ""), env.get("REMNAWAVE_TOKEN", "")
    if not base or not token:
        sys.exit("REMNAWAVE_URL and REMNAWAVE_TOKEN are required")
    only = {s.strip() for s in env.get("ONLY_SQUADS", "").split(",") if s.strip()}
    credential = env.get("CREDENTIAL", "vless").lower()
    if credential not in CREDENTIAL_FIELDS:
        sys.exit("CREDENTIAL must be vless or ss")

    try:
        uuids = active_users(base, token, only, credential)
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

    masque_cfg = env.get("MASQUE_CONFIG", "")
    if masque_cfg and sync_masque(masque_cfg, uuids, extra(env.get("EXTRA_MASQUE_USERS"))):
        changed.append("masque")

    gateway_template = env.get("GATEWAY_TEMPLATE", "")
    if gateway_template:
        gateway_config = env.get("GATEWAY_CONFIG", "/etc/titan-gateway/config.json")
        if sync_gateway(gateway_template, gateway_config, uuids, extra(env.get("EXTRA_GATEWAY_USERS"))):
            changed.append("gateway")
    masque_template = env.get("MASQUE_GATEWAY_TEMPLATE", "")
    if masque_template:
        masque_config = env.get("MASQUE_GATEWAY_CONFIG", "/etc/titan-masque/config.json")
        if sync_xray_masque(masque_template, masque_config, uuids):
            changed.append("masque-gateway")
    mieru_template = env.get("MIERU_GATEWAY_TEMPLATE", "")
    if mieru_template:
        mieru_config = env.get("MIERU_GATEWAY_CONFIG", "/etc/titan-mieru/config.json")
        if sync_mieru_gateway(mieru_template, mieru_config, uuids):
            changed.append("mieru-gateway")

    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    with open(STATE_FILE, "w") as f:
        f.write(digest)
    print(f"active users: {len(uuids)} [{digest}]" + (f", updated: {', '.join(changed)}" if changed else ", no changes"))


if __name__ == "__main__":
    main()
