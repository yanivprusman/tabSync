# tabSync

Move Chrome tabs between the desktop and the phone.

- **Desktop → phone**: the **TabSync** Android app (`mobile/`). It lists the desktop
  Chrome's open tabs (from `GET /api/tabs` on this backend, `10.7.0.2:3185` over
  WireGuard) and opens them all — or one — in the phone's Chrome, each in its own tab.
- **Phone → desktop**: `phoneTabsToDesktop [--dry-run]` in any terminal
  (`scripts/phone-to-desktop.mjs`, via adb + phone Chrome's DevTools socket).

The app also shows where the computer's sound is going and has a switch to move it, both
through `desktop/phoneAudio.sh`: `GET /api/sound` reads its status, polled every 3 s while the
app is on screen, and `POST /api/sound {"on": true|false}` runs `on` or `off`.

Two buttons copy photos to the desktop's `~/Pictures/Screenshots` through
`POST /api/photo?name=`: one sends the newest image in `DCIM` (camera and screenshots), the other
opens the system photo picker for one or several. A copy never replaces a file.

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
