# tabSync

Move Chrome tabs between the desktop and the phone.

- **Desktop → phone**: the **TabSync** Android app (`mobile/`). It lists the desktop
  Chrome's open tabs (from `GET /api/tabs` on this backend, `10.7.0.2:3185` over
  WireGuard) and opens them all — or one — in the phone's Chrome, each in its own tab.
- **Phone → desktop**: `phoneTabsToDesktop [--dry-run]` in any terminal
  (`scripts/phone-to-desktop.mjs`, via adb + phone Chrome's DevTools socket).

The app also shows where the computer's sound is going (`GET /api/sound`, which asks
`desktop/phoneAudio.sh status`). It reloads with the tabs, on opening and on pull-to-refresh.

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
