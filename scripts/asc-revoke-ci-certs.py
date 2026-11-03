#!/usr/bin/env python3
"""Revokes the Apple Development certificates that TestFlight runs make for themselves.

Automatic signing with an App Store Connect API key (testflight.yml) makes a new development
certificate on each run: the runner is new, its private key dies with it, and after a few runs
Apple refuses the next one ("maximum number of certificates"). Those certificates are named
"Created via API"; this revokes them (and only them: a person's own, made in Xcode, is named
after them). Run before the archive, to free the slot, and after the upload.

Environment: KEY_ID, ISSUER_ID and KEY_PATH (the .p8). Needs only python3 and openssl.
Prints no names but the matched ones (the logs of a public repository are public).
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.appstoreconnect.apple.com/v1"
DEVELOPMENT = {"DEVELOPMENT", "IOS_DEVELOPMENT", "MAC_APP_DEVELOPMENT"}
MARK = "created via api"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def raw_signature(der: bytes) -> bytes:
    """ECDSA's DER SEQUENCE { r, s } as ES256's r || s, 32 bytes each."""
    if der[0] != 0x30:
        raise ValueError("not a DER sequence")
    i = 2 if der[1] < 0x80 else 2 + (der[1] & 0x7F)
    out = b""
    for _ in range(2):
        if der[i] != 0x02:
            raise ValueError("not a DER integer")
        n = der[i + 1]
        out += der[i + 2:i + 2 + n].lstrip(b"\x00").rjust(32, b"\x00")
        i += 2 + n
    return out


def token(key_id: str, issuer: str, key_path: str) -> str:
    now = int(time.time())
    head = b64(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    body = b64(json.dumps({"iss": issuer, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"}).encode())
    signing = f"{head}.{body}".encode()
    der = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_path], input=signing, capture_output=True, check=True
    ).stdout
    return f"{head}.{body}.{b64(raw_signature(der))}"


def call(method: str, url: str, jwt: str):
    req = urllib.request.Request(url, method=method, headers={"Authorization": f"Bearer {jwt}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
        return json.loads(data) if data else None


def main() -> int:
    try:
        jwt = token(os.environ["KEY_ID"], os.environ["ISSUER_ID"], os.environ["KEY_PATH"])
    except (KeyError, subprocess.CalledProcessError, ValueError) as e:
        print(f"::warning::Не удалось подготовить ключ App Store Connect: {type(e).__name__}")
        return 0
    url = f"{API}/certificates?limit=200&fields[certificates]=name,displayName,certificateType,expirationDate"
    certs = []
    try:
        while url:
            page = call("GET", url, jwt)
            certs += page.get("data", [])
            url = page.get("links", {}).get("next")
    except urllib.error.HTTPError as e:
        print(f"::warning::Список сертификатов недоступен: HTTP {e.code}")
        return 0
    dev = [c for c in certs if c.get("attributes", {}).get("certificateType") in DEVELOPMENT]
    ours = [
        c for c in dev
        if MARK in (c["attributes"].get("name") or "").lower() or MARK in (c["attributes"].get("displayName") or "").lower()
    ]
    print(f"Сертификатов разработки: {len(dev)}, созданных сборками (Created via API): {len(ours)}")
    failed = 0
    for c in ours:
        a = c["attributes"]
        try:
            call("DELETE", f"{API}/certificates/{c['id']}", jwt)
            print(f"Отозван: {a.get('name')} (до {a.get('expirationDate', '?')[:10]})")
        except urllib.error.HTTPError as e:
            failed += 1
            print(f"::warning::Не удалось отозвать {a.get('name')}: HTTP {e.code}")
    # Never fails the build: a certificate left behind is revoked by the next run.
    if failed:
        print(f"::warning::Не отозвано: {failed}; следующая сборка попробует снова")
    return 0


if __name__ == "__main__":
    sys.exit(main())
