import hashlib
import hmac
import time

import pytest
from fastapi.testclient import TestClient

import app as auth

USERS = [
    {"telegramId": 111, "email": "user@example.com", "status": "ACTIVE",
     "subscriptionUrl": "https://sub.example.com/api/sub/abc"},
]


@pytest.fixture(autouse=True)
def setup(monkeypatch):
    async def fake_find(field, value):
        for u in USERS:
            if str(u.get(field)).lower() == value.lower():
                return u
        return None
    monkeypatch.setattr(auth, "_find_user", fake_find)
    monkeypatch.setattr(auth, "BOT_TOKEN", "123:TEST")
    monkeypatch.setattr(auth, "BOT_SECRET", "s3cret")
    monkeypatch.setattr(auth, "PUBLIC_URL", "https://auth.example.com")
    sent = {}
    monkeypatch.setattr(auth, "_send_mail", lambda to, code: sent.__setitem__(to, code))
    auth.tg_logins.clear(); auth.email_codes.clear(); auth.ip_hits.clear()
    yield sent


client = TestClient(auth.app)


def signed(params):
    check = "\n".join(f"{k}={v}" for k, v in sorted(params.items()))
    secret = hashlib.sha256(b"123:TEST").digest()
    return {**params, "hash": hmac.new(secret, check.encode(), hashlib.sha256).hexdigest()}


def test_telegram_widget_flow():
    r = client.post("/api/tg/start").json()
    token = r["token"]
    assert r["url"] == f"https://auth.example.com/tg?token={token}"
    assert "telegram-widget.js" in client.get(f"/tg?token={token}").text
    assert client.get(f"/api/tg/poll?token={token}").json() == {"status": "pending"}

    params = signed({"id": "111", "first_name": "A", "auth_date": str(int(time.time()))})
    page = client.get("/tg/callback", params={"token": token, **params})
    assert "Готово" in page.text
    assert client.get(f"/api/tg/poll?token={token}").json() == {
        "status": "ok", "subscriptionUrl": "https://sub.example.com/api/sub/abc"}
    # single use
    assert client.get(f"/api/tg/poll?token={token}").json()["status"] == "expired"


def test_telegram_bad_signature_and_unknown_user():
    token = client.post("/api/tg/start").json()["token"]
    params = signed({"id": "111", "auth_date": str(int(time.time()))})
    params["id"] = "222"  # tampered
    assert "Ошибка проверки" in client.get("/tg/callback", params={"token": token, **params}).text

    params = signed({"id": "999", "auth_date": str(int(time.time()))})
    assert "не найдена" in client.get("/tg/callback", params={"token": token, **params}).text
    assert client.get(f"/api/tg/poll?token={token}").json()["status"] == "not_found"


def test_bot_confirm_requires_secret():
    token = client.post("/api/tg/start").json()["token"]
    assert client.post("/api/tg/confirm", json={"token": token, "telegramId": 111}).status_code == 403
    r = client.post("/api/tg/confirm", json={"token": token, "telegramId": 111}, headers={"X-Bot-Secret": "s3cret"})
    assert r.json() == {"status": "ok"}


def test_email_flow(setup):
    sent = setup
    assert client.post("/api/email/start", json={"email": "User@Example.com"}).json() == {"ok": True}
    code = sent["user@example.com"]
    assert client.post("/api/email/verify", json={"email": "user@example.com", "code": "000000" if code != "000000" else "111111"}).status_code == 400
    r = client.post("/api/email/verify", json={"email": "user@example.com", "code": code})
    assert r.json() == {"subscriptionUrl": "https://sub.example.com/api/sub/abc"}


def test_email_unknown_is_silent(setup):
    assert client.post("/api/email/start", json={"email": "nobody@example.com"}).json() == {"ok": True}
    assert "nobody@example.com" not in setup
    assert client.post("/api/email/verify", json={"email": "nobody@example.com", "code": "123456"}).status_code == 400


def test_email_attempt_limit(setup):
    client.post("/api/email/start", json={"email": "user@example.com"})
    for _ in range(auth.EMAIL_MAX_ATTEMPTS):
        client.post("/api/email/verify", json={"email": "user@example.com", "code": "xxxxxx"})
    code = setup["user@example.com"]
    assert client.post("/api/email/verify", json={"email": "user@example.com", "code": code}).status_code == 400
