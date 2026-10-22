# Xbox (Play Anywhere / PC Game Pass) on GameNative

GameNative runs Microsoft Store PC games — Xbox Play Anywhere titles, PC Game
Pass and other Store PC games the account owns — by embedding the
[Xodus Nexus](https://github.com/domsvoboda/xodus-nexus) backend, a patched
build of [Xodus](https://github.com/xodus-gaming/xodus). A valid purchase or
subscription is required; the backend obtains genuine licenses from Microsoft
for the signed-in account.

## Using it

Xbox is one of the library's stores, like Steam, Epic, GOG and Amazon:

- **Sign in** from the Xbox tab ("Sign in to Xbox to see your library"), the
  platform list on the login screen, or the library menu's "Sign in to Xbox".
  Sign out from the same menu.
- The **Xbox tab** lists the account's Play Anywhere and PC Game Pass titles
  that have a PC package; it can be hidden in the library tab settings.
- A game's page installs, pauses, resumes, cancels and uninstalls it; downloads
  appear in the Downloads screen with the other stores'.
- **Play** prepares the license and launch view and starts the game in its own
  container. Saves sync with Xbox cloud saves.

Devices without the backend binaries hide Xbox everywhere.

## Why this shape

Xodus Nexus is three parts: an Electron launcher (UI only), a Rust backend
(`xodus-cli`, `xodus-service`: sign-in, entitlements, package download,
licensing, launch preparation) and a Wine runtime (`xgameruntime.dll` plus a few
patched Wine DLLs). GameNative already has everything an Electron port would
have to rebuild — Wine/Proton for Android, FEX/Box64, Turnip, input, containers,
store integrations — and already ships a Rust service the same way (Comet,
`libcomet.so`). So GameNative gets an `XBOX` game source backed by the
unchanged Nexus backend, cross-compiled for Android, and the Nexus runtime DLLs
installed per container.

```
GameNative (Kotlin)                          xodus-cli / xodus-service (Rust, aarch64-linux-android)
─────────────────────────────────────────    ────────────────────────────────────────────────────────
XboxOAuthActivity (WebView)   ◄── NEXUS_AUTH_V1 ──►  login --external-webview-stdio
XboxService.refreshLibrary    ◄── JSON ─────────────  library --json / resolve-package --json
XboxDownload                  ◄── NEXUS_PROGRESS ──►  streaming --progress-json --control-stdin
XboxLaunch                    ◄── NEXUS_LAUNCH_V1 ─►  prepare-launch (new: decrypted launch view)
XodusServiceSession + bridge  ◄── unix socket ─────  xodus-service
        ▲ TCP 127.0.0.1 + 64-char key
Wine prefix: xgameruntime.dll, winhttp.dll, windows.web.dll, twinapi.appcore.dll, wintypes.dll (native, per container)
```

## Pieces

| Path | What |
| --- | --- |
| `tools/build-xodus.sh` | Builds `libxodus_cli.so` / `libxodus_service.so`: upstream Xodus at the Nexus-pinned commit + the 24 Nexus backend patches (checksum-verified) + `tools/xodus-patches/0001-android-port.patch`. |
| `tools/xodus-patches/0001-android-port.patch` | Android port of the backend (below). |
| `tools/build-xodus-runtime.sh`, `tools/xodus-runtime-overlay.py` | Builds the runtime DLLs with Nexus's own clean-build recipe and packages them as `assets/xbox/xodus-runtime-overlay.tzst` (builtin marker cleared so they load as native DLLs per prefix). |
| `app/.../service/xbox/` | Protocol parsers, process plumbing, sign-in, library (Room `xbox_games`), downloads, launch handoff, service bridge, runtime overlay. |
| `app/.../ui/screen/auth/XboxOAuthActivity.kt`, `ui/screen/library/appscreen/XboxAppScreen.kt` | Sign-in screen and game page, built like the other stores'. |
| `app/src/main/jniLibs/arm64-v8a/libxodus_*.so`, `app/src/main/assets/xbox/xodus-runtime-overlay.tzst` | Prebuilt outputs of the two scripts above, committed like `libcomet.so`. Rebuild with the scripts when the pins or patches change. |

### Backend Android port (`0001-android-port.patch`)

All Android code paths are behind `--cfg xodus_android` (set by the build
script; Android builds without it fail to compile on purpose). Desktop builds are unchanged.

- **Credentials**: one file per keyring entry in `XODUS_SECRETS_DIR` (0700/0600,
  atomic writes, read-through) instead of the D-Bus Secret Service. Safe for
  the CLI and the service running at once.
- **Device identity**: Android has no SMBIOS. A Type 1 record (random UUID and
  serial) is synthesized once and persisted next to the secrets; the device
  license is bound to it, so it is never silently replaced.
- **TLS**: reqwest's rustls-platform-verifier needs a JVM on Android and
  panics in a standalone executable. All clients go through `xodus::http`,
  which trusts exactly the PEM bundle in `SSL_CERT_FILE` (GameNative exports
  `AndroidCAStore`) or the system cacerts directories. OpenSSL is vendored for
  xal's older reqwest.
- **`prepare-launch`**: the launch handoff. Instead of spawning Wine, the CLI
  fetches the license, decrypts *every* encrypted image (DLLs too, since stock
  Wine has no `WINE_DLL_FILE_MAP`) into a private launch view under
  `--view-root`, reports it on stdout and holds it until told to stop, then
  deletes it. `--sweep-stale-views` removes views left by a killed session.
- `memfd` decryption buffers on Android as on Linux; service runtime dir from
  `XDG_RUNTIME_DIR`.

### Launch flow (XServerScreen → `XboxService.prepareLaunch`)

1. Overlay DLLs copied into the container's `system32` (hash-verified), WinRT
   `Windows.Data.Json.*` classes registered in `system.reg`, and
   `WINEDLLOVERRIDES` merged (`xgameruntime=n;winhttp=n,b;…`).
2. `xodus-service` started with a private `XDG_RUNTIME_DIR`; Kotlin loopback
   bridge with a random key → `XODUS_BRIDGE_PORT` / `XODUS_BRIDGE_KEY`.
3. `prepare-launch` → launch view; `A:` is the view root, the game runs as
   `winhandler.exe "A:\xodus-view-…\<exe>"`; `XODUS_XSTS_PLAYFAB` passed through.
4. On exit: view removed, service stopped after a short grace period (save upload).

## What was verified (Oct 2026)

- Nexus patch set applies to the pinned Xodus; `build-xodus.sh` reproduces the
  exact tree that was tested.
- Backend with `--cfg xodus_android` (host-compiled): 140 tests pass (41 xodus,
  59 cli, 26 msixvc, 14 service), including new tests for the file store,
  synthetic identity, Android TLS roots, the handoff view and sweeping.
  The desktop build still compiles unchanged.
- Kotlin module compiles against android-34 (no warnings); 26 JVM tests cover
  the protocols and drive the real process plumbing against test doubles
  (sign-in relay, download pause/resume, launch view lifecycle, service bridge
  key check).
- Runtime overlay built from Nexus's recipe; the DLLs import only standard
  Windows exports. Loaded as **native** DLLs in a **stock Wine 9.0** prefix,
  Nexus's ABI probes pass (runtime init, task queues, async, app state, token
  dispatch, device ID). The JSON classes need `Windows.Foundation.Collections.PropertySet`,
  which Proton 10's wintypes lacks, so the overlay ships Wine 11's `wintypes.dll`.
- Every exhaustive `when` over `GameSource` handles `XBOX`; compiling the
  touched files introduces no new compiler errors.
- On an AYN Thor (Proton 10 arm64ec): Microsoft sign-in and sign-out, the
  library tab, installs, Balatro (Game Pass) and
  Brotato (D3D12, VKD3D picked automatically) reaching their menus signed in to
  Xbox Live, cloud saves, and returning to GameNative when a game quits.

## Next steps

1. Update detection, file verification and playtime, as the other stores have.
2. D3D12 detection for Unity games (the renderer is chosen inside
   `UnityPlayer.dll`, not imported by the executable).
3. Proper runtime: rebase Xodus's Wine patches into GameNative/proton-wine so
   the DLLs are builtins of GameNative's own Proton (arm64ec), enabling
   `WINE_DLL_FILE_MAP` and dropping the decrypted-DLL launch view.
4. Upstream: offer the Android port patch to Xodus Nexus.

## Licences

The bundled backend (`libxodus_cli.so`, `libxodus_service.so`) is built from
[Xodus](https://github.com/xodus-gaming/xodus) (GPL-3.0) with the Xodus Nexus
patches (MIT) and `tools/xodus-patches`; `tools/xodus-patches/nexus-backend-source.lock.json`
pins the exact sources and `tools/build-xodus.sh` rebuilds them. The runtime
overlay's DLLs come from Wine and xgameruntime (LGPL-2.1) and nghttp2 (MIT);
their licences ship inside the overlay under `licenses/`.
