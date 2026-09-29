# TitanVPS — Android

Брендированный VPN-клиент на ядре **Xray** для подписок Remnawave. Работает только с вашими
подписками, пользователь не видит ни ссылок, ни настроек: одна кнопка «Подключить».

**Стек:** Kotlin, Jetpack Compose (Material 3), [XTLS/libXray](https://github.com/XTLS/libXray)
(Xray-core через gomobile), `VpnService` плюс встроенный TUN Xray (без tun2socks), OkHttp.

## Как это работает

```
Бот / сайт ──кнопка──► titanvps://import/https://sub.ваш-домен/<shortUuid>
                                  │  (или App Link https://sub.ваш-домен/<shortUuid>)
                                  ▼
            приложение проверяет домен (whitelist) → качает подписку
            User-Agent: Xray (временно) + x-hwid, x-device-os, … + x-hwid, x-device-os, …
                                  ▼
          Remnawave отдаёт Xray JSON → список локаций + заголовки
          (profile-title, subscription-userinfo, support-url, announce, …)
                                  ▼
   VpnService (TUN fd) → Xray inbound "tun" → маршрутизация из вашего шаблона → сервер
```

- **Подписка только ваша.** Ссылки на чужие домены отклоняются, поля для вставки нет. Список доменов
  задаётся в `titan.subHosts`.
- **Все настройки на сервере.** Маршрутизацию, DNS, fragment и прочее задаёт JSON-шаблон Xray
  в Remnawave. Приложение подставляет только свой TUN-inbound и перехват DNS.
- **Лимит устройств (HWID).** Приложение отправляет заголовки `x-hwid` (ANDROID_ID),
  `x-device-os`, `x-ver-os` и `x-device-model`.
- **Вход прямо в приложении** через страницу сайта `titan.loginUrl` (код на почту или Telegram). Когда в кабинете появляется ссылка подписки с разрешённых доменов, приложение подхватывает её автоматически. Сайт менять не нужно.
- **Пинг** серверов — настоящий запрос через каждый сервер (любой протокол, включая Hysteria2), в отдельном процессе, поэтому работает и при включённом VPN.
- **Экран подписки** показывает трафик, дату окончания, объявление (`announce`), кнопки
  «Продлить» и «Поддержка».

## Настройка под себя

`gradle.properties`:

```properties
titan.subHosts=sub.titanvps.ru          # домены подписки, через запятую
titan.appLinkHost=sub.titanvps.ru       # домен для App Links
titan.telegramUrl=https://t.me/titanvps_bot
titan.websiteUrl=https://titanvps.ru
```

Иконка лежит в `app/src/main/res/drawable/ic_launcher_foreground.xml`, цвета в `ui/theme/Theme.kt`.

## Настройка Remnawave

1. **Формат ответа.** В правилах ответа подписки (Subscription → Response Rules) добавьте правило:
   если `User-Agent` содержит `TitanVPS` (пока приложение временно отправляет `Xray`), отдавать **Xray JSON**. Тогда в приложение придут
   полные конфиги с вашей маршрутизацией. Если правила нет, приложение разберёт и обычные
   `vless://` ссылки в base64, но маршрутизация тогда будет стандартная: локальные сети напрямую,
   всё остальное через VPN.
2. **Кнопка на странице подписки и в боте.** Добавьте приложение со ссылкой
   `titanvps://import/<URL подписки>`. Поддерживаются также `titanvps://import?url=<urlencoded>`
   и `titanvps://add/<URL>`.
   Telegram не открывает кастомные схемы из inline-кнопок, поэтому в боте давайте кнопку
   на https-страницу подписки. Можно настроить App Link (пункт 3), тогда
   `https://sub.домен/<shortUuid>` сразу откроет приложение.
3. **App Links (необязательно).** Разместите на `https://<appLinkHost>/.well-known/assetlinks.json`:
   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "com.titanvps.app",
       "sha256_cert_fingerprints": ["<SHA-256 вашего ключа подписи>"]
     }
   }]
   ```
4. **Гео-файлы.** В APK вшиты `geoip.dat` и `geosite.dat` из
   [runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat),
   поэтому в шаблоне можно использовать `geosite:category-ru`, `geoip:ru` и т. п.

## Сборка

Нужны JDK 17, Android SDK и NDK, Go 1.24+ и Python 3.

```bash
./scripts/build-libxray.sh      # → app/libs/libXray.aar  (LIBXRAY_REF=<tag> для фиксации версии)
./scripts/download-geo.sh       # → app/src/main/assets/geo*.dat
./gradlew testDebugUnitTest assembleDebug
```

**В Android Studio без Go и NDK.** Скачайте из последнего запуска GitHub Actions архив
**TitanVPS-deps** и распакуйте его в корень проекта. Он положит `app/libs/libXray.aar`
и `app/src/main/assets/*.dat`. После этого достаточно нажать Run ▶.

Без локального окружения APK собирает GitHub Actions (`.github/workflows/android.yml`),
результат лежит в артефакте **TitanVPS-debug**.

## Структура

```
app/src/main/java/com/titanvps/app/
├── TitanApp.kt                 Application, копирование geo-файлов
├── MainActivity.kt             диплинки, запрос разрешения VPN
├── core/
│   ├── XrayCore.kt             обёртка libXray (runXray / stopXray / pingBatch / convert)
│   └── XrayConfigs.kt          разбор Xray JSON / ссылок, сборка итогового конфига с TUN
├── data/
│   ├── DeepLinks.kt            titanvps:// и App Links + whitelist доменов
│   ├── SubscriptionHeaders.kt  profile-title, subscription-userinfo, announce …
│   ├── SubscriptionRepository.kt  загрузка подписки, HWID-заголовки, кеш
│   └── SubscriptionStore.kt    хранение
├── vpn/
│   ├── TitanVpnService.kt      VpnService + Xray, foreground-уведомление
│   ├── VpnTileService.kt       плитка в шторке
│   └── VpnState.kt
└── ui/                         Compose: приветствие, главный экран, выбор локации
```

## Что дальше

- Release-подпись и сборка AAB для Google Play или RuStore.
- Скорость и трафик в реальном времени (Xray stats API).
- Раздельное туннелирование по приложениям с управлением с сервера.
- Windows-клиент на той же схеме (libXray `xray.exe` + Wintun).
