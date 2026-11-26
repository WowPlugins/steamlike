# AGENTS.md

## What this is

Kotlin Android app + Rust Windows companion program. The Android app captures gamepad input via a transparent overlay window, maps it through an "action set + public layer + 10 operation layers" system (inspired by Steam Input), and sends mapped events over TCP (port 27015) to a Windows client (`inputbridge_client.exe`, Rust + egui) that injects them via `SendInput()`. Designed for WoW Turtle 1.18.1 running in Winlator.

Both ends also keep a local key-mapping table: the Rust client installs low-level keyboard/mouse hooks so local keys can be remapped without round-tripping through the phone.

## Build

```bash
# Full APK build (compiles the Rust exe automatically when cargo is available)
./gradlew assembleDebug

# APK output (also copied to apk/app-debug.apk by copyDebugApkToRepo)
app/build/outputs/apk/debug/app-debug.apk

# Tests (pure JVM)
./gradlew test
```

### Windows exe (auto-compiled by Gradle)

The `compileWindowsExe` task runs before `preBuild`. It searches for cargo in order:
1. `M:/msys64/ucrt64/bin/cargo.exe`
2. `C:/msys64/ucrt64/bin/cargo.exe`
3. `~/.cargo/bin/cargo.exe`
4. System PATH

It runs `cargo build --release --manifest-path windows/rust_client/Cargo.toml`, then copies the artifact to both `windows/inputbridge_client.exe` and `app/src/main/assets/inputbridge_client.exe`. The task is incremental: `windows/rust_client/src`, `windows/rust_client/assets`, `Cargo.toml`, `Cargo.lock` are inputs.

If cargo is not found (or the build fails), the task only prints a warning and the APK keeps using the exe already inside `assets/` — verify that exe matches `Cargo.toml` before shipping.

To compile manually outside Gradle:
```bash
cd windows/rust_client
cargo build --release   # output: target/release/inputbridge_client.exe
```

The APK export bundles **only** `inputbridge_client.exe` + `version.txt` into `Download/AControler` (see `MainActivity.exportFilesToDownload`). Shell scripts are not shipped.

## Tests

- Kotlin: JUnit 4 unit tests under `app/src/test/`, pure JVM (no device). They use integer constants instead of Android `KeyEvent` constants for portability.
- Rust: `cargo test --manifest-path windows/rust_client/Cargo.toml` (a few protocol/pure-function tests live inside `bridge.rs`).

## Project structure

- `app/src/main/java/com/steamlike/controller/` — all Kotlin source
  - `core/` — `SteamInput` (main controller), `MappingTypes` (data structures), `ControllerTypes` (enums), `ControllerDevice`
  - `injection/` — `GamepadInputView` (overlay), `InputBridgeServer` (TCP), `BridgeInputInjector` (VK mapping)
  - `mapping/` — `KeyboardMouseMapper`, `WoWActionSets` (layer presets, `LAYER_NAMES`)
  - `config/` — `ConfigManager`, `ControllerConfig` (JSON serialization), `AppConfig`
  - `service/` — `ControllerOverlayService` (foreground service, the main orchestrator)
  - `ui/` — `UiKit`
  - `MainActivity.kt`, `LayerEditActivity.kt`, `AdvancedSettingsActivity.kt`, `GamepadTestActivity.kt`, `HelpActivity.kt`
- `windows/rust_client/` — the Windows client (Rust):
  - `main.rs` — entry point, single-instance mutex, auto-launch, window setup
  - `bridge.rs` — TCP client (port 27015) and packet dispatch
  - `hooks.rs` — low-level keyboard/mouse hooks + action thread (WASD right-lock, interact sequence)
  - `win.rs` — `SendInput` injection, cursor handling, own-window lookup/resize/rounding, time sync
  - `ui.rs` — egui control panel (basic settings / key mappings / logs)
  - `config.rs` — `rust_client_config.json` (exe directory), key mappings, conditions
  - `runtime.rs` — shared runtime state (atomics + mutexes), logging, version reporting
  - `clipboard.rs` — clipboard polling → `clipboard_log.txt` + APK
  - `wowmem.rs` — reads character state from the `SLState` WoW addon's Lua table
- `docs/protocol.md` — single source of truth for the TCP packet types
- `SLState.lua` / `SLState.toc` — WoW addon that publishes character state into a Lua array

## Key architecture facts

- **Config is layered: Action Set → layers**. `ControllerProfile` holds a list of `ActionSet`s plus `activeActionSetName`. Each `ActionSet` owns its own public layer + operation layers. Switching action sets swaps all layers underneath as a whole. Backwards-compat accessors (`commonLayer`/`layers`/`allLayers`/`findLayer`) delegate to `activeActionSet`, so old call sites work unchanged.
- **Layer switching is driven by `SwitchLayer` mappings in the public layer**. `OperationLayer` has no `triggerButton` field; the layer-edit page's「切入按键」button reads/writes the public layer's `SwitchLayer` mapping for that layer.
- **Key resolution order**: active layers (in activation order) → public layer fallback.
- **Action set management lives in `LayerEditActivity`**: top spinner to switch, plus 添加/拷贝/改名/删除 dialogs. Copy deep-copies every layer's mapping table into a new `ActionSet` (fresh `LinkedHashMap`), so sets never share mutable maps. `createEmptyActionSet` builds a blank Common + 10 layers for new sets.
- **`ControllerOverlayService`** is the central orchestrator: creates `SteamInput`, `BridgeInputInjector`, `InputBridgeServer`, `KeyboardMouseMapper`, and the overlay views. The expanded overlay panel shows the current action set name (`操作集: X`); switching action sets refreshes the panel/mapping view/collapsed pill via `onActionSetSwitched`.
- **`SteamInputHolder.current`** (`core/SteamInputHolder.kt`) is the app-wide handle to the running `SteamInput`. The service sets it on start and clears it on destroy; settings UI reads `SteamInputHolder.current` and falls back to the saved config file when the service is not running. Never store the instance in another static field.
- **LayerEditActivity pauses/resumes the overlay** on create/destroy (via Intent actions) to avoid blocking Android back gestures.
- **Pause capture removes the focus window**: `pauseCapturing()` truly removes the 1x1 focus window (restoring the Android predictive-back swipe gesture). Consequence: while paused, gamepad events can't reach the app, so the `ToggleCapture` gamepad key cannot resume capture — resume via the overlay "恢复捕获" button or MainActivity's "手柄捕获" switch.
- **Accessibility key-filtering is unavailable/deprecated**: `GamepadAccessibilityService` attempted `FLAG_REQUEST_FILTER_KEY_EVENTS` to receive gamepad keys while paused, but this MIUI device grants no key-filtering capability (capabilities=0, no "按键过滤" toggle in settings), so `onKeyEvent` never fires. The class is kept only for capability checks; new code must not rely on it.
- **IME keyboard keeps capture active**: `ToggleKeyboard` shows the soft keyboard bound to the 1x1 focus window (IME can only bind to this process's focused window). Typed text/keys are forwarded over TCP as Windows VK codes and injected via SendInput. Showing the keyboard does NOT pause capture.
- **Config persistence**: APK side is version=3 JSON at `{internal storage}/files/steamlike_config.json` (`ControllerConfig.fromJson` dispatches by version: v3 = action-set format, v2 = legacy flat format auto-migrated into a single "默认" action set). Windows side is `rust_client_config.json` next to the exe, which also holds `wow_path` for launching the game.
- **Rust client threading**: UI thread (egui), `bridge` thread (TCP, 50 ms read timeout), `hooks` thread (message loop + `action_thread` for delayed actions), `clipboard` thread (300 ms poll), `wowmem` thread (100 ms frame read). Hook callbacks must stay lock-free — they only read atomics in `runtime.rs`; anything that may block is posted to `action_thread`.
- **Injected vs real input** is distinguished by `win::MAGIC` (`"SLKR"` in `dwExtraInfo`): the hooks pass through self-injected events without updating lock state.

## Gotchas

- **Proxy in `gradle.properties`**: HTTP/HTTPS proxy is hardcoded to `127.0.0.1:7897`. Remove or comment out if building without a local proxy.
- **Aliyun + Tencent mirrors**: `settings.gradle.kts` uses Aliyun mirrors; `gradle-wrapper.properties` uses Tencent mirror for Gradle 9.5.0. These may need changing outside China.
- **No instrumented tests**: only JUnit 4 unit tests exist. There are no `androidTest/` files.
- **cargo is optional**: the APK builds without it. The bundled exe in `assets/` is used as fallback, so a stale exe can silently ship — rebuild or re-export when Rust code changed.
- **Version numbers are three separate counters** (see「工程约束」rule): APK `versionName` (only APK changes), `Cargo.toml` version (only Rust changes), and `version.txt` written at export. The exe displays the first line of `version.txt` and logs a warning when its second line disagrees with its own compile-time version. `0x0D`/`0x20` do the same check over TCP.
- **`val` immutability on `OperationLayer`**: `name` is `val`. Use `copy()` to modify it (see `LayerEditActivity`). Same applies to `ActionSet.name` — rebuild the `actionSets` list with `copy(name=...)` when renaming.
- **Single-process Windows client**: uses named mutex `Global\SteamLikeInputBridgeClient`.
- **IME double-input guard**: only `CONTROL_KEY_CODES` (ENTER/TAB/DPAD/ESCAPE/PAGE_UP/PAGE_DOWN/MOVE_HOME/MOVE_END) go through the key-event channel (`onImeKey`); printable chars are injected only via the text channel (`commitText`/`setComposingText`). Forwarding printables in `sendKeyEvent` too would double-inject because `BaseInputConnection(view, false)`'s fallback also dispatches characters to the view.
- **Winlator/Wine quirks are load-bearing**: layered windows ignore `SetWindowRgn` (dot mode uses a normal window + region clipping), low-level hooks never see mouse movement (auto-launch cancellation polls the cursor), and the shared clipboard loops back (clipboard sync filters by sequence number, content, and an 8 s suppression window).
