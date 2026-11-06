# tabSync

Move Chrome tabs between the desktop and the phone.

- **Desktop → phone**: the **TabSync** Android app (`mobile/`). It lists the desktop
  Chrome's open tabs (from `GET /api/tabs` on this backend, `10.7.0.2:3185` over
  WireGuard) and opens them all — or one — in the phone's Chrome, each in its own tab.
- **Phone → desktop**: `phoneTabsToDesktop [--dry-run]` in any terminal
  (`scripts/phone-to-desktop.mjs`, via adb + phone Chrome's DevTools socket).

The app is one live screen, polling the desktop only while it is visible:

- **Computer sound**: a two-position control, through `desktop/phoneAudio.sh` (`GET /api/sound`
  reads its status, `POST /api/sound {"on": true|false}` runs `on` or `off`).
- **Photos**: a strip of the 12 newest images in `DCIM` (camera and screenshots). The button sends
  the latest in one tap, or whichever were selected; *All photos* opens the system picker for
  older ones. They land in the desktop's `~/Pictures/Screenshots` through
  `POST /api/photo?name=`, which never replaces a file.
- **Tabs**: `GET /api/tabs`, with an ETag so an unchanged list costs almost nothing to poll.

Both directions share one filter, `lib/tabs.mjs`: **solitaire, dashboard and WhatsApp
tabs stay put**, as do non-web pages; `localhost` URLs become `10.7.0.2` for the phone.
Dashboard ports come from the daemon (`getPort dashboard-dev|prod`).

Tabs are read with DevTools `/json/list` and opened with `PUT /json/new` — never
`setDownloadBehavior` (it breaks Chrome's download buttons).

## Config (gitignored)
- `.env.local`: `TABSYNC_API_TOKEN=<secret>`
- `mobile/.env`: `API_BASE_URL=http://10.7.0.2:3185/` and `API_TOKEN=<same secret>`

Build/install: `cd mobile && ./gradlew assembleDevDebug` then
`adb -s 10.7.0.3:5555 install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk`.
