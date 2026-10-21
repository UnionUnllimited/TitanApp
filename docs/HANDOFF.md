# Titan VPS: передача проекта

Обзор репозитория для разработчика, который продолжит работу. Ветка с последними
изменениями: `claude/exciting-ptolemy-oiy9r6`.

## Безопасность

Репозиторий публичный. В код, коммиты и issues не должны попадать домен и IP
админки и бота, адрес и токен панели, ключи подписки, UUID, ключи шифрования.
Секреты сборок (подпись iOS, App Store Connect) лежат в GitHub Secrets.

## Структура

| Путь | Что это |
|---|---|
| `app/` | Android: Kotlin, Jetpack Compose, ядро libXray (`.aar`), клиенты протоколов как исполняемые файлы |
| `desktop/` | Windows: Compose Desktop, `xray.exe`, `sing-box.exe` (TUN), клиенты naive и mieru |
| `ios/TitanVPS/` | iOS-приложение (SwiftUI) |
| `ios/TitanTunnel/` | iOS Packet Tunnel Extension: запускает Xray и клиент протокола |
| `ios/project.yml` | Проект Xcode (XcodeGen) |
| `scripts/build-libxray.sh` | Сборка libXray для Android |
| `scripts/build-libxray-ios.sh` | Сборка `LibXray.xcframework` для iOS (libXray v26.9.30 в режиме `local` с изменённым Xray-core) |
| `scripts/ios-patches/xray-tfo/` | Патч Xray-core для TCP Fast Open на iOS и Windows (`apply.py` и Go-файлы) |
| `scripts/ios-patches/titan/` | Go-пакет `titan` в xcframework: клиенты sing-box (TUIC, AnyTLS, ShadowTLS, SSH, Naive) и Mieru для iOS |
| `server/` | Скрипты установки и удаления ноды (`install-node.sh`, `remove-node.sh`) |
| `.github/workflows/` | CI: `android.yml`, `windows.yml`, `ios.yml` |

## Сборки

Каждый пуш в `claude/**` или `main` с изменениями в нужной папке запускает CI.
Результат выкладывается в GitHub Releases:

- Android: тег `android-N`, файлы `titan-vps-arm64.apk`, `titan-vps-armv7.apk`, `titan-vps-universal.apk`
- Windows: тег `windows-N`, файлы `titan-vps-setup.exe`, `titan-vps.msi`. `xray.exe` собирается в CI из Xray-core v26.9.30 с патчем `scripts/ios-patches/xray-tfo` и тегом `tfogo_checklinkname0`
- iOS: тег `ios-N`, файл `TitanVPS-unsigned.ipa` (без подписи, для сервисов подписи и Sideloadly)
- iOS в TestFlight: workflow `testflight.yml`, запускается вручную (Actions → TestFlight → Run workflow).
  Подпись автоматическая через ключ App Store Connect API; нужные секреты перечислены в начале файла.
  Bundle ID: `com.titanvps.ios` (туннель `com.titanvps.ios.tunnel`, виджеты `com.titanvps.ios.widgets`);
  прежний `su.titanvps.app` остался за сервисом подписи. Манифесты приватности: `PrivacyInfo.xcprivacy`
  в `ios/TitanVPS` и `ios/TitanTunnel`.

Ядро iOS долго собирается без кэша: кэш сбрасывается при изменении `scripts/ios-patches/**`.

## Где что в коде

- Разбор подписки и правка конфигов на iOS: `ios/TitanVPS/Subscription.swift`
  (`applyTfo` включает TFO по имени сервера или полю `titan`; `fitXhttpInSyn` правит XHTTP для TFO-серверов).
- То же на Android: `app/src/main/java/com/titanvps/app/core/XrayConfigs.kt`; на Windows: `desktop/src/main/kotlin/com/titanvps/desktop/XrayConfigs.kt`.
- Пинг: iOS `ios/TitanVPS/XrayPing.swift`; Android `app/.../core/PingService.kt`; Windows `desktop/.../Repository.kt` (`PingEngine`).
- VPN: iOS `ios/TitanVPS/VPNController.swift` и `ios/TitanTunnel/PacketTunnelProvider.swift`;
  Android `app/.../vpn/TitanVpnService.kt`; Windows `desktop/.../AppState.kt`, `Tun.kt`.
- Интерфейс iOS: общие кнопки, переключатели, строки настроек, тосты и хаптика — `ios/TitanVPS/Components.swift`;
  ползунок «Выкл | Вкл» (перетаскивание, блокировка на время пинга) — `PowerSlider.swift`;
  «Проверить соединение» (запрос через работающий VPN: задержка, страна и IP выхода) — `ConnectionCheck.swift`.
- Интерфейс Windows повторяет iOS: цвета и тема — `desktop/.../Theme.kt`; кнопки, переключатели, строки
  настроек, тосты, диалоги — `Components.kt`; экраны и ползунок «Выкл | Вкл» — `Ui.kt`; личный кабинет —
  `CabinetPage.kt`. Скорость на Главной берётся из счётчиков Xray (страница metrics ядра, `Traffic.kt`),
  там же «Проверить соединение». Меню в трее своё, как у Happ (`TrayMenu.kt`): статус с флагом, серверы и
  режим подменю, импорт ключа из буфера; напоминания о конце подписки и трафика приходят уведомлением трея.
- Обновление Windows: `desktop/.../Updater.kt` (окно обновления на PowerShell).
- Технический режим (диагностика, скрыт за кодом): iOS `ios/TitanVPS/TechModeView.swift`,
  Android `app/.../ui/TechModeScreen.kt`, Windows `desktop/.../TechMode.kt`.
  Открывается семью нажатиями на версию и вводом кода. Код в репозитории не хранится, только его SHA-256.

## TCP Fast Open на iOS

- Транспорт туннеля — Xray-core (Go, BSD-сокеты). Опции Network.framework
  (`NWProtocolTCP.Options.enableFastOpen`, `NWParameters.allowFastOpen`) действуют только
  на `NWConnection` и к сокетам Xray не относятся. Поэтому TFO включён в самом Xray-core
  (`scripts/ios-patches/xray-tfo`): дайлер tfo-go открывает соединение на первой записи
  через `connectx()` с `CONNECT_DATA_IDEMPOTENT` (то же делает Network.framework) и ставит
  `TCP_FASTOPEN_FORCE_ENABLE`.
- Что выяснено по исходникам XNU (`bsd/netinet/tcp_output.c`, `tcp_timer.c`, `tcp_cache.c`):
  - в SYN идёт не больше `t_maxopd − 40` байт, а до SYN-ACK `t_maxopd` — MSS по умолчанию:
    472 Б для IPv4, 984 Б для IPv6. Если в очереди больше, данных в SYN нет вовсе. Поэтому
    первая запись режется (`tfoSynData4/6`), остальное уходит после рукопожатия;
  - без `TCP_FASTOPEN_FORCE_ENABLE` ядро после двух потерь отключает TFO для всей сети
    (ключ — сигнатура сети, не сервер) на 5 мин, 10, 20… и повторяет потерянный SYN без
    данных. Опция это отключает, tfo-go ставит её на каждый сокет.
- В SYN кладётся только то, что безопасно получить дважды (`tfo_safe.go`): TLS ClientHello,
  заголовки HTTP, тело GET/HEAD/OPTIONS и загрузок XHTTP packet-up (сервер принимает каждую
  один раз по номеру). Прочее (заголовок прокси-протокола с данными) — без TFO.
- Каждое TFO-соединение сверяется с записью ядра (`TCP_CONNECTION_INFO`): ушли ли данные в
  SYN и принял ли их сервер. Это видно:
  - в «Техническом режиме», карточка «TCP Fast Open»: туннель (через `handleAppMessage`) и
    пинг в приложении; «данные в SYN приняты сервером» — TFO действительно сработал;
  - в ошибке пинга, в квадратных скобках (`tfo http 438+0 B in 71 ms: data in SYN, acked`).

## Проверка TFO

1. Телефон: «Технический режим» → «TCP Fast Open» → «Обновить» при включённом VPN на
   TFO-сервере. Ждём «TFO работает», растущее «данные в SYN приняты сервером» и нули в
   «не приняты» и «ядро отправило обычный SYN». «cookie ещё не было» — первое соединение
   после смены сети, это нормально.
2. Сервер: в SYN от клиента должны быть `tfo cookie` и `length > 0`, сразу за SYN-ACK —
   ответ сервера, до третьего ACK (значит, данные из SYN приняты):

```
tcpdump -nr /root/t.pcap -vv 'src host <IP клиента> and tcp[tcpflags] & tcp-syn != 0' | paste - - | head
nstat -az TcpExtTCPFastOpenPassive TcpExtTCPFastOpenPassiveFail TcpExtTCPFastOpenCookieReqd
```

## Состояние на момент передачи

- Android: серверы «Обход» с TFO работают.
- iOS (`ios-57`) и Windows (`windows-78`): серверы «Обход» с TFO по LTE не работают (таймаут пинга).
  - TFO на iOS отправляет данные через `connectx`. По захватам с сервера в первом пакете при этом не больше ~470 байт.
  - На Windows TFO в `xray.exe` до сборки `windows-76` фактически не работал: tfo-go был собран без тега `tfogo_checklinkname0`.
  - Режим XHTTP берётся из настроек сервера. Сервер «Обход 6» работает в режиме stream-up и на packet-up отвечает `400 Bad Request`.
    Дамп 20.10 13:43 UTC (сборка ios-56 и раньше, packet-up принудительно): TFO по TCP работал
    (cookie и данные в каждом SYN, сервер отвечал сразу), а все загрузки получили 400. С ios-57
    режим берётся с сервера.
- Остальные серверы и протоколы (VLESS/Reality, XHTTP без TFO, Hysteria, TUIC, AnyTLS, ShadowTLS, SSH, Mieru, MASQUE, Naive) работают на всех трёх платформах.

## Диагностика на сервере

Захват трафика на ноде для одного клиента:

```
timeout 90 tcpdump -Z root -ni any -w /root/t.pcap 'port <порт>'
tcpdump -nr /root/t.pcap -vv 'src host <IP клиента> and tcp[tcpflags] & tcp-syn != 0' | paste - - | head
tcpdump -nr /root/t.pcap -q 'host <IP клиента>' | head -100
```

Внешний IP устройства виден в техническом режиме, раздел «Сеть».
