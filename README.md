# DSH Mobile Gateway

**English** | [简体中文](README.zh-CN.md)

> Access the **DeepSeek Harness desktop app** from your Android phone — same instance, same sessions, same running tasks.

DSH is an Electron desktop application with no official mobile client. This project bridges that gap:

```
phone --HTTPS--> Cloudflare edge <--outbound QUIC-- cloudflared --> 127.0.0.1:19399 (gateway)
      --> 127.0.0.1:19387 (the DSH desktop instance already running) --> your model provider
```

The phone and the desktop talk to the **same process**, so the session list, running tasks and workspace files are naturally shared.

---

## What's in the box

| Component | Description |
|---|---|
| `gateway/` | Node.js gateway: access-code gate, HTTP/WS proxy, narrow-screen layout injection, QR login code generation |
| `qr.mjs` | Zero-dependency QR code **generator** (pure JS, ~14 KB) |
| `android/` | Android shell app: WebView container + **pure-Java QR decoder** + Camera2 scanner |
| `docs/` | Implementation notes and post-mortems |

---

## Requirements

- Windows 10/11 (the launcher scripts are `.cmd`)
- Node.js 18+
- `cloudflared.exe` in `bin/` — download from [Cloudflare](https://github.com/cloudflare/cloudflared/releases) and rename to `bin\cloudflared.exe`
- DSH desktop app running (the gateway proxies it, it does not start its own instance)

---

## Quick start

### 1. Start the DSH desktop app first

The gateway **attaches to** the running instance rather than spawning a new one.

### 2. Run the gateway

Double-click `start.cmd`, wait 20–40 s. You'll see:

```
============================================================
  DSH mobile gateway is ready
============================================================
  Phone URL : https://xxxx-xxxx-xxxx.trycloudflare.com
  Access code: xxxxxxxxxx
============================================================
  Attached to running DSH at 127.0.0.1:19387
```

The same info is written to `gateway/access.txt`, and a scannable QR code is saved to `gateway/login-qr.png`.

**Closing the console window immediately closes all external access.** The desktop app keeps running normally.

### 3. Connect from the phone

**Option A — browser.** Open the URL, enter the access code, and optionally "Add to Home Screen". Zero installation.

**Option B — the Android app.** Install `DSH-mobile.apk`, then either scan the QR code shown by the gateway or paste the URL. The app remembers the address.

---

## Build the Android app

No Android Studio, no Gradle. Requirements: JDK 17 and Android SDK (build-tools 34.0.0, platform android-34).

```powershell
# One-time: create your own signing key
keytool -genkeypair -v -keystore dsh-release.keystore -alias dsh `
        -keyalg RSA -keysize 2048 -validity 10000

# Build
$env:JAVA_HOME        = 'C:\path\to\jdk-17'
$env:ANDROID_SDK_ROOT = 'C:\path\to\android-sdk'
$env:DSH_KS_PASS      = 'your-keystore-password'

powershell -ExecutionPolicy Bypass -File android\build.ps1
```

Pipeline: `aapt2 compile → aapt2 link → javac → d8 → inject dex → zipalign → apksigner`.

**Keep the same signing key for upgrades** — changing it forces users to uninstall first.

| Source | Role |
|---|---|
| `MainActivity.java` | Config page, WebView container, back-key handling, scan entry |
| `ScannerView.java` | Camera2 capture, per-pixel rotation, preview rendering, decode scheduling |
| `QrDecoder.java` | Pure-Java QR decoding (finder patterns, format info, masks, RS blocks, perspective correction) |
| `BuildTag.java` | Version watermark shown in the UI |

---

## Design decisions

| Question you might have | Why it is the way it is |
|---|---|
| Why not make DSH listen on the LAN? | This DSH build rejects `0.0.0.0` at startup (it would expose remote code execution to the network), and binding a specific LAN IP fails config validation. |
| Why a tunnel instead of opening a port? | The gateway binds **only** to `127.0.0.1:19399`; `cloudflared` makes an **outbound** connection. No inbound port is opened locally, and after `stop` nothing is reachable. |
| Why does the phone layout get scaled down? | The DSH web frontend has **no responsive breakpoints** (only a `prefers-reduced-motion` query). Measured minimum layout width is ~500–520 px, so a 390 px portrait phone overflows. The gateway injects a snippet that pins the layout to 520 px and lets the browser scale it (~0.75×). |
| Why not start a second `dsh web` for the phone? | Tried it. Result: **the phone and the desktop chat separately** — two independent processes with separate session lists. Proxying the existing instance is what makes them truly shared. |
| How does it pass desktop authentication? | The desktop signs its own auth cookie with a persistent secret stored locally (`client-connection:browser-session`). The gateway reads that secret and signs a cookie the same way, then rewrites `Host`/`Origin` back to loopback. **It does not modify the desktop process or its config.** |
| How does the phone find the session list? | On narrow screens DSH collapses the session drawer. The gateway injects a snippet that expands it once after boot. |

---

## Security boundaries (read this)

- **The public URL is equivalent to a shell on your computer.** Anyone with the URL *and* the access code can drive an agent that reads/writes files and runs commands in your profile.
- The access code is 10 random characters, transported only over HTTPS, and **only valid while the gateway or `cloudflared` is running**.
- **Close the window when you're done.** That is the default and intended behaviour.
- Tunnel hostnames are randomly assigned by Cloudflare and hard to guess — but that is not a security boundary. **The access code is the boundary.**
- Traffic is terminated at Cloudflare's edge (they can see plaintext). If that is unacceptable, swap `startTunnel()` for a tunnel you control (e.g. Tailscale).

---

## Known limitations

1. **The URL changes on every start.** Quick tunnels are ephemeral. For a stable hostname you need a Cloudflare account plus your own domain (named tunnel), or a different tunnel provider.
2. **Portrait mode is uniformly scaled**, not a native mobile layout. Landscape gets the full layout.
3. No right-click, no keyboard shortcuts on mobile; complex panel dragging and split terminals are worse than on desktop.

---

## Verified / not verified

**QR generator verification** — run `node qr-selftest.mjs` (structural checks across versions 1–6):

- Symbol size matches version for every sample
- All three finder patterns present and correct
- Timing patterns alternate correctly; dark module present

Beyond the structural self-test, the generator was cross-validated against **independent implementations**:

- Pixel-identical output vs the Python `qrcode` reference library (0 differing modules)
- Successful decode by **pyzbar** (an independent decoder), 6/6 samples

**Verified on real hardware:**

- Tunnel established, public URL reachable, access-code gate blocks and allows correctly
- WebSocket (`/api/remote.mux`) traverses the gateway and stays open
- Mobile 390×844 emulation: layout complete, no horizontal overflow
- A real message sent from the phone and answered by the model
- `stop.cmd` leaves no listeners, no `cloudflared` residue, desktop unaffected
- **Two physical devices, end to end**: a phone (Android 16) and a tablet (Android 17), portrait and landscape, including QR scan login and 30 fps camera preview
- Decoder regression: 2/2 real phone-camera photos decoded; low-resolution legacy frames fail as expected (see `docs/`)

**Not verified:**

- Long-term stability (quick tunnels are occasionally rate-limited)
- Android 7–11 (minSdk is 24, but only Android 16/17 were tested)
- Older sensors that report `pixelStride == 2` (there is a fallback branch, never exercised on hardware)

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `cloudflared.exe is missing` | Download it into `bin\cloudflared.exe` |
| `tunnel not established within 90 s` | Outbound network blocked; check Cloudflare reachability, configure a proxy for cloudflared if needed |
| Phone shows a blank page or spins forever | Check the tail of `logs/requests.log` and `logs/cloudflared.log` |
| Correct code but still redirected to login | Phone clock/cookie issue; use the `url/?k=code` one-click link |
| Want a new access code | Stop, delete `gateway/state.json`, restart |
| Port already in use | The gateway cleans up stale processes on 19399/19388 at startup |

---

## Licence

MIT — see [LICENSE](LICENSE).

The QR generator and decoder are original implementations. No third-party QR library is bundled.