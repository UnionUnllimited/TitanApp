"""
TitanVPS auth server: lets the Android app log in via Telegram (your bot) or e-mail
and returns the user's Remnawave subscription URL. The Remnawave API token never
leaves this server.

Endpoints used by the app:
  POST /api/tg/start                     -> {token, botUrl}
  GET  /api/tg/poll?token=...            -> {status: "pending"|"ok"|"not_found"|"expired", subscriptionUrl?}
  POST /api/email/start   {email}        -> {ok: true}          (always, no user enumeration)
  POST /api/email/verify  {email, code}  -> {subscriptionUrl}

Telegram login (no bot code changes): the app opens GET /tg?token=... which shows the
official Telegram Login Widget for your bot. Telegram redirects to /tg/callback with
signed user data (verified with BOT_TOKEN), we find the Remnawave user by telegramId
and hand the subscription back to the app. Requires @BotFather → /setdomain = PUBLIC_URL host.

Optional alternative, if you'd rather confirm from your own bot (header X-Bot-Secret):
  POST /api/tg/confirm {token, telegramId, subscriptionUrl?}
"""
from __future__ import annotations

import hashlib
import html
import hmac
import json
import logging
import os
import re
import secrets
import smtplib
import ssl
import time
from dataclasses import dataclass, field
from email.message import EmailMessage

import httpx
from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

log = logging.getLogger("titan-auth")
logging.basicConfig(level=logging.INFO)

REMNAWAVE_URL = os.environ.get("REMNAWAVE_URL", "").rstrip("/")
REMNAWAVE_TOKEN = os.environ.get("REMNAWAVE_TOKEN", "")
BOT_USERNAME = os.environ.get("BOT_USERNAME", "TitanVPS_bot")
BOT_TOKEN = os.environ.get("BOT_TOKEN", "")
BOT_SECRET = os.environ.get("BOT_SECRET", "")
PUBLIC_URL = os.environ.get("PUBLIC_URL", "").rstrip("/")
SMTP_HOST = os.environ.get("SMTP_HOST", "")
SMTP_PORT = int(os.environ.get("SMTP_PORT", "465"))
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("SMTP_FROM", SMTP_USER)
SMTP_STARTTLS = os.environ.get("SMTP_STARTTLS", "false").lower() == "true"

TG_TOKEN_TTL = 10 * 60
EMAIL_CODE_TTL = 10 * 60
EMAIL_MAX_ATTEMPTS = 5
EMAIL_RESEND_SECONDS = 60
IP_LIMIT_PER_HOUR = 30

EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")

app = FastAPI(title="TitanVPS auth", docs_url=None, redoc_url=None)


# ---------------------------------------------------------------- storage
# In-memory: fine for a single instance. Restart = pending logins are lost.

@dataclass
class TgLogin:
    created: float
    status: str = "pending"  # pending | ok | not_found
    subscription_url: str | None = None


@dataclass
class EmailCode:
    code_hash: str
    created: float
    attempts: int = 0


tg_logins: dict[str, TgLogin] = {}
email_codes: dict[str, EmailCode] = {}
ip_hits: dict[str, list[float]] = {}


def _cleanup() -> None:
    now = time.time()
    for k in [k for k, v in tg_logins.items() if now - v.created > TG_TOKEN_TTL]:
        tg_logins.pop(k, None)
    for k in [k for k, v in email_codes.items() if now - v.created > EMAIL_CODE_TTL]:
        email_codes.pop(k, None)


def _rate_limit(request: Request) -> None:
    ip = request.headers.get("x-real-ip") or (request.client.host if request.client else "?")
    now = time.time()
    hits = [t for t in ip_hits.get(ip, []) if now - t < 3600]
    if len(hits) >= IP_LIMIT_PER_HOUR:
        raise HTTPException(429, "Слишком много попыток, попробуйте позже")
    hits.append(now)
    ip_hits[ip] = hits


def _hash(code: str) -> str:
    return hashlib.sha256(code.encode()).hexdigest()


# ---------------------------------------------------------------- remnawave

async def _find_user(field_name: str, value: str) -> dict | None:
    """Finds a Remnawave user by telegramId or email. Prefers ACTIVE users."""
    if not REMNAWAVE_URL or not REMNAWAVE_TOKEN:
        raise HTTPException(500, "Remnawave is not configured")
    headers = {"Authorization": f"Bearer {REMNAWAVE_TOKEN}"}
    users: list[dict] = []
    async with httpx.AsyncClient(timeout=15) as client:
        # Older Remnawave: dedicated endpoints.
        legacy = {"telegramId": f"by-telegram-id/{value}", "email": f"by-email/{value}"}[field_name]
        r = await client.get(f"{REMNAWAVE_URL}/api/users/{legacy}", headers=headers)
        if r.status_code == 200:
            data = r.json().get("response")
            users = data if isinstance(data, list) else ([data] if data else [])
        else:
            # Newer Remnawave: filtered list.
            params = {
                "size": 50,
                "filters": json.dumps([{"id": field_name, "value": value}]),
                "filterModes": json.dumps({field_name: "equals"}),
            }
            r = await client.get(f"{REMNAWAVE_URL}/api/users", headers=headers, params=params)
            if r.status_code != 200:
                log.warning("Remnawave lookup failed: %s %s", r.status_code, r.text[:200])
                raise HTTPException(502, "Сервер подписок недоступен")
            users = r.json().get("response", {}).get("users", [])

    def matches(u: dict) -> bool:
        v = u.get(field_name)
        return v is not None and str(v).lower() == value.lower()

    users = [u for u in users if matches(u) and u.get("subscriptionUrl")]
    if not users:
        return None
    users.sort(key=lambda u: u.get("status") != "ACTIVE")
    return users[0]


# ---------------------------------------------------------------- telegram

class TgConfirm(BaseModel):
    token: str
    telegramId: int
    subscriptionUrl: str | None = None


@app.post("/api/tg/start")
async def tg_start(request: Request):
    _rate_limit(request)
    _cleanup()
    token = secrets.token_urlsafe(24)
    tg_logins[token] = TgLogin(created=time.time())
    return {
        "token": token,
        # Telegram Login Widget page (default flow).
        "url": f"{PUBLIC_URL}/tg?token={token}",
        # Deep link into the bot, for the optional /api/tg/confirm flow.
        "botUrl": f"https://t.me/{BOT_USERNAME}?start=app_{token}",
    }


def _check_widget_signature(data: dict[str, str]) -> bool:
    """https://core.telegram.org/widgets/login#checking-authorization"""
    received = data.get("hash", "")
    check = "\n".join(f"{k}={v}" for k, v in sorted(data.items()) if k != "hash")
    secret = hashlib.sha256(BOT_TOKEN.encode()).digest()
    expected = hmac.new(secret, check.encode(), hashlib.sha256).hexdigest()
    fresh = time.time() - int(data.get("auth_date", "0")) < 24 * 3600
    return bool(BOT_TOKEN) and fresh and hmac.compare_digest(expected, received)


def _page(title: str, body: str) -> HTMLResponse:
    return HTMLResponse(f"""<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{html.escape(title)}</title>
<style>body{{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0b0f1a;color:#e6e9f2;font-family:system-ui,sans-serif;text-align:center;padding:24px}}
a.btn{{display:inline-block;margin-top:20px;padding:14px 28px;border-radius:28px;background:#3d7bff;
color:#fff;text-decoration:none;font-weight:600}}p{{color:#9aa3b8}}</style></head>
<body><div>{body}</div></body></html>""")


@app.get("/tg", response_class=HTMLResponse)
async def tg_page(token: str):
    if token not in tg_logins:
        return _page("TitanVPS", "<h2>Ссылка устарела</h2><p>Вернитесь в приложение и нажмите «Войти» ещё раз.</p>")
    auth_url = html.escape(f"{PUBLIC_URL}/tg/callback?token={token}", quote=True)
    return _page("Вход в TitanVPS", f"""<h2>Вход в TitanVPS</h2>
<p>Подтвердите вход через Telegram</p>
<script async src="https://telegram.org/js/telegram-widget.js?22"
 data-telegram-login="{html.escape(BOT_USERNAME, quote=True)}" data-size="large" data-radius="20"
 data-auth-url="{auth_url}"></script>""")


@app.get("/tg/callback", response_class=HTMLResponse)
async def tg_callback(request: Request):
    params = dict(request.query_params)
    token = params.pop("token", "")
    _cleanup()
    login = tg_logins.get(token)
    if login is None:
        return _page("TitanVPS", "<h2>Ссылка устарела</h2><p>Вернитесь в приложение и нажмите «Войти» ещё раз.</p>")
    if not _check_widget_signature(params):
        return _page("TitanVPS", "<h2>Ошибка проверки Telegram</h2><p>Попробуйте ещё раз.</p>")
    user = await _find_user("telegramId", params["id"])
    if not user:
        login.status = "not_found"
        return _page("TitanVPS", f"""<h2>Подписка не найдена</h2>
<p>К этому Telegram-аккаунту не привязана подписка.</p>
<a class="btn" href="https://t.me/{html.escape(BOT_USERNAME)}">Оформить в боте</a>""")
    login.status = "ok"
    login.subscription_url = user["subscriptionUrl"]
    # The app also polls /api/tg/poll, so returning to it manually works too.
    return _page("TitanVPS", """<h2>Готово ✅</h2><p>Вход выполнен, вернитесь в приложение.</p>
<a class="btn" href="titanvps://auth-done">Открыть TitanVPS</a>
<script>setTimeout(function(){location.href="titanvps://auth-done"},300)</script>""")


@app.post("/api/tg/confirm")
async def tg_confirm(body: TgConfirm, x_bot_secret: str = Header(default="")):
    if not BOT_SECRET or not hmac.compare_digest(x_bot_secret, BOT_SECRET):
        raise HTTPException(403, "forbidden")
    _cleanup()
    login = tg_logins.get(body.token)
    if login is None:
        raise HTTPException(404, "expired")
    url = body.subscriptionUrl
    if not url:
        user = await _find_user("telegramId", str(body.telegramId))
        url = user["subscriptionUrl"] if user else None
    login.status = "ok" if url else "not_found"
    login.subscription_url = url
    return {"status": login.status}


@app.get("/api/tg/poll")
async def tg_poll(token: str):
    _cleanup()
    login = tg_logins.get(token)
    if login is None:
        return {"status": "expired"}
    if login.status == "ok":
        tg_logins.pop(token, None)  # single use
        return {"status": "ok", "subscriptionUrl": login.subscription_url}
    return {"status": login.status}


# ---------------------------------------------------------------- e-mail

class EmailStart(BaseModel):
    email: str


class EmailVerify(BaseModel):
    email: str
    code: str


def _send_mail(to: str, code: str) -> None:
    msg = EmailMessage()
    msg["Subject"] = f"Код входа TitanVPS: {code}"
    msg["From"] = SMTP_FROM
    msg["To"] = to
    msg.set_content(f"Ваш код для входа в приложение TitanVPS: {code}\n\nКод действует 10 минут. "
                    f"Если вы не запрашивали код, просто проигнорируйте это письмо.")
    if SMTP_STARTTLS:
        with smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=20) as s:
            s.starttls(context=ssl.create_default_context())
            s.login(SMTP_USER, SMTP_PASSWORD)
            s.send_message(msg)
    else:
        with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=20, context=ssl.create_default_context()) as s:
            s.login(SMTP_USER, SMTP_PASSWORD)
            s.send_message(msg)


@app.post("/api/email/start")
async def email_start(body: EmailStart, request: Request):
    _rate_limit(request)
    _cleanup()
    email = body.email.strip().lower()
    if not EMAIL_RE.match(email):
        raise HTTPException(400, "Некорректный email")
    prev = email_codes.get(email)
    if prev and time.time() - prev.created < EMAIL_RESEND_SECONDS:
        raise HTTPException(429, "Код уже отправлен, подождите минуту")
    # Always answer ok so nobody can probe which e-mails are customers.
    if await _find_user("email", email):
        code = f"{secrets.randbelow(1_000_000):06d}"
        email_codes[email] = EmailCode(code_hash=_hash(code), created=time.time())
        try:
            _send_mail(email, code)
        except Exception:
            log.exception("SMTP send failed")
            email_codes.pop(email, None)
            raise HTTPException(502, "Не удалось отправить письмо")
    return {"ok": True}


@app.post("/api/email/verify")
async def email_verify(body: EmailVerify, request: Request):
    _rate_limit(request)
    _cleanup()
    email = body.email.strip().lower()
    entry = email_codes.get(email)
    if entry is None:
        raise HTTPException(400, "Код истёк, запросите новый")
    entry.attempts += 1
    if entry.attempts > EMAIL_MAX_ATTEMPTS:
        email_codes.pop(email, None)
        raise HTTPException(400, "Слишком много попыток, запросите новый код")
    if not hmac.compare_digest(entry.code_hash, _hash(body.code.strip())):
        raise HTTPException(400, "Неверный код")
    email_codes.pop(email, None)
    user = await _find_user("email", email)
    if not user:
        raise HTTPException(404, "Подписка не найдена")
    return {"subscriptionUrl": user["subscriptionUrl"]}


@app.get("/health")
async def health():
    return {"ok": True}
