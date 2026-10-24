# Remote Control (Android + Jetpack Compose)

Remote administration app: a **foreground service** keeps one OkHttp WebSocket
connected to your control server and executes JSON commands. The companion
**control page** (plain HTML/JS) can live on GitHub Pages.

> Use this only on devices you own or administer, with a session you started.
> The service shows a persistent notification while running and every sensitive
> capability (taps, screen capture, location) requires an explicit OS grant.

## Important: GitHub Pages cannot host the WebSocket

GitHub Pages serves **static files only** — it has no process that can speak the
WebSocket protocol. The usual split:

| Piece | Where |
|---|---|
| Control web page (`index.html` + JS) | GitHub Pages (static, fine) |
| `ws://` / `wss://` endpoint | Anywhere that runs a process: a small Cloudflare Worker (Durable Objects), a $5 VPS with `ws`, Fly.io, Railway, an ESP/Raspberry Pi… |

The relay itself is ~30 lines: upgrade HTTP → WebSocket, keep a `Set<WebSocket>`
per session id, forward every text frame to the connected device and every
device frame back to that session's browser socket.

## Live relay (deployed)

A working relay + control page is deployed on this account:

- Control page: https://remote-control-relay.adhiphub-rc.workers.dev/
- Device endpoint: `wss://remote-control-relay.adhiphub-rc.workers.dev/ws`
  (the app's `DEFAULT_URL` already points here; the Session ID shown in the
  app UI is the room both sides join — set the same id on the web page)
- Source + redeploy: `relay/` — `cd relay && npx wrangler deploy`
- Verify: `node relay/e2e-check.mjs` (checks forwarding both directions)
- Optional auth: `cd relay && npx wrangler secret put AUTH_TOKEN`, then the
  browser passes `?token=…` and the app would need an `X-Relay-Token` header

## App download

**Remote Control v1.3 (versionCode 4)** — the debug APK lives in
[`download app/`](download%20app/) (`remote-control-v1.0-debug.apk`) and is
rebuilt automatically by GitHub Actions (`.github/workflows/build-apk.yml`)
whenever app sources change. See [`download app/README.md`](download%20app/README.md)
for install steps.

## Build

Open in Android Studio (Hedgehog+), or:

```bash
gradle assembleDebug        # AGP 8.7, Kotlin 2.0, compileSdk 35, minSdk 26
```

Before running, replace `YOUR_DOMAIN` in two places:

1. `app/src/main/res/xml/network_security_config.xml` — allows **cleartext** `ws://`
   for that host only (Android blocks cleartext by default). Use `wss://` and
   drop the cleartext allowance for anything real.
2. `RemoteControlService.DEFAULT_URL` (or just edit the URL in the app UI — it is
   persisted per install).

## First run

1. Launch → accept location + notification permissions.
2. Flip **Session running** on → the persistent notification appears and the
   socket connects. The UI shows *Connected* + the **Session ID**.
3. Tap **Grant screen capture** once (Android requires a consent dialog per
   projection). `screenshot` works from then on.
4. Tap **Enable accessibility for taps** → Settings → Accessibility → turn on
   *Remote Control taps*. `click` works from then on.

## Wire protocol (JSON text frames)

Device → server (on connect):

```json
{"type":"hello","sessionId":"…","device":"Google Pixel 8","sdk":34,"app":"remote-control/1.0"}
```

Server → device:

| Message | Behaviour |
|---|---|
| `{"action":"click","x":120,"y":800,"id":"r1"}` | Tap at x,y (omit x/y → random on-screen point). Sent through the accessibility gesture API. |
| `{"action":"screenshot","id":"r2"}` | JPEG (long edge capped at 1280 px, quality 70) returned base64-encoded. |
| `{"action":"location","id":"r3"}` | Last known fix, or a fresh one (10 s cap) if last-known is missing. |
| `{"action":"kill","id":"r4"}` | Acks, closes the socket, stops the service, exits the process. |
| `{"action":"ping","id":"r5"}` | `{"type":"pong"}` |

Every response echoes `id` when supplied, so the page can match request/response:

```json
{"type":"result","action":"click","x":120.0,"y":800.0,"ok":true,"id":"r1"}
{"type":"result","action":"location","lat":52.52,"lon":13.405,"accuracy":8.0,"provider":"gps","time":1718…}
{"type":"result","action":"screenshot","format":"jpeg","width":720,"height":1600,"data":"/9j/4AAQ…"}
{"type":"error","action":"click","message":"tap service disabled – …"}
```

On the browser side: `const ws = new WebSocket("wss://…/ws?sessionId=" + id)`,
then `ws.send(JSON.stringify({action:"screenshot", id: crypto.randomUUID()}))`
and `img.src = "data:image/jpeg;base64," + msg.data` on the reply.

## Connection robustness

- OkHttp `pingInterval` 20 s keeps NATs/relays from silently dropping the link.
- Exponential backoff 1 s → 30 s with ±50 % jitter, reset on every successful
  open; a generation counter makes stale socket callbacks harmless.
- `ConnectivityManager.registerDefaultNetworkCallback` triggers a fast retry the
  moment the network returns (backoff is pointless then).
- `START_STICKY` + `stopWithTask=false`: Android restarts the service (and the
  socket) after the process is killed or the task is swiped away.
- Screen-capture consent is adopted by the service (`TYPE_MEDIA_PROJECTION`
  foreground type is set *before* `getMediaProjection`, as Android 14+ requires)
  and re-requested from the app if the projection stops.

## Files

- `app/src/main/java/com/example/remotecontrol/MainActivity.kt` — Compose UI
- `app/src/main/java/com/example/remotecontrol/RemoteControlService.kt` —
  foreground service, WebSocket, command handlers, shared `RemoteControlState`
- `app/src/main/java/com/example/remotecontrol/TapAccessibilityService.kt` —
  gesture dispatch (`click` cannot work without it: Android reserves raw touch
  injection for system apps)
- `app/src/main/AndroidManifest.xml` — permissions + service declarations
- `app/build.gradle.kts` — okhttp3, coroutines, Compose BOM
