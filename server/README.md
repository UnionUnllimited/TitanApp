# TitanVPS — сервер входа

Небольшой сервис, через который приложение входит **через Telegram** или **по почте** и получает
ссылку на подписку из Remnawave. Токен Remnawave хранится только на сервере, в приложение он не попадает.

## Как работает вход

**Telegram** (код бота менять не нужно):
1. В приложении пользователь нажимает «Войти через Telegram», открывается страница `https://auth.../tg`
   с официальной кнопкой Telegram.
2. Пользователь подтверждает вход в Telegram, сервер проверяет подпись токеном бота и ищет пользователя
   в Remnawave по `telegramId`.
3. Приложение получает ссылку на подписку и подключается.

**Почта:**
1. Пользователь вводит email, и сервер ищет пользователя в Remnawave по полю `email`.
2. Если пользователь найден, на почту приходит 6-значный код. Код действует 10 минут, даётся 5 попыток.
3. После ввода кода приложение получает подписку.

Условие: у пользователей в Remnawave должны быть заполнены `Telegram ID` и/или `Email`.
Если ваш бот создаёт пользователей через API, он обычно заполняет `telegramId` сам.

## Установка (на любом сервере с Docker)

```bash
git clone -b claude/exciting-ptolemy-oiy9r6 https://github.com/UnionUnllimited/TitanApp
cd TitanApp/server
cp .env.example .env
nano .env        # заполнить значения, см. ниже
docker compose up -d --build
curl http://127.0.0.1:8080/health   # {"ok":true}
```

`.env`:

| Переменная | Что это |
|---|---|
| `REMNAWAVE_URL` | Адрес панели Remnawave, например `https://panel.titanvps.ru` |
| `REMNAWAVE_TOKEN` | API-токен: Remnawave → Settings → API Tokens |
| `BOT_USERNAME` | `TitanVPS_bot` |
| `BOT_TOKEN` | Токен бота из @BotFather. Нужен только для проверки подписи входа |
| `PUBLIC_URL` | Публичный HTTPS-адрес этого сервиса, например `https://auth.titanvps.ru` |
| `SMTP_*` | Почта для отправки кодов (Яндекс, Mail.ru, свой SMTP) |
| `BOT_SECRET` | Нужен только для входа через свой бот, см. ниже. Можно оставить пустым |

### HTTPS и домен

Сервис слушает `127.0.0.1:8080`, перед ним нужен nginx или Caddy с сертификатом. Пример для Caddy:

```
auth.titanvps.ru {
    reverse_proxy 127.0.0.1:8080
}
```

### Telegram: один раз в @BotFather

`/setdomain` → выбрать `@TitanVPS_bot` → указать `auth.titanvps.ru` (хост из `PUBLIC_URL`).
У бота может быть только один домен. Если вход через Telegram уже используется на сайте,
разместите сервис на том же домене, например `https://titanvps.ru/app-auth`, и укажите это в `PUBLIC_URL`.

### Приложение

В `gradle.properties` в корне репозитория пропишите `titan.authUrl=<PUBLIC_URL>`.

## Вход через свой бот (альтернатива)

Вместо виджета можно подтверждать вход из вашего бота. Приложение открывает
`https://t.me/TitanVPS_bot?start=app_<token>`, а бот в обработчике `/start app_<token>` вызывает:

```python
import httpx

async def on_start_app(token: str, telegram_id: int):
    await httpx.AsyncClient().post(
        "https://auth.titanvps.ru/api/tg/confirm",
        headers={"X-Bot-Secret": BOT_SECRET},
        json={"token": token, "telegramId": telegram_id},
        # можно сразу передать "subscriptionUrl", если бот его знает
    )
```

## Тесты

```bash
pip install -r requirements.txt pytest
pytest -q
```
