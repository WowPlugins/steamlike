# AGENTS.md

## What this is

Kotlin Android app + C Windows companion program. The Android app captures gamepad input via a transparent overlay window, maps it through an "action set + public layer + 10 operation layers" system (inspired by Steam Input), and sends mapped events over TCP (port 27015) to a Windows client that injects them via `SendInput()`. Designed for WoW Turtle 1.18.1 running in Winlator.

## Build

```bash
# Full APK build (compiles Windows exe automatically if gcc available)
./gradlew assembleDebug

# APK output
app/build/outputs/apk/debug/app-debug.apk
```

### Windows exe (auto-compiled by Gradle)

The `compileWindowsExe` task runs before `preBuild`. It searches for gcc in order:
1. `M:/msys64/ucrt64/bin/gcc.exe`
2. `C:/msys64/ucrt64/bin/gcc.exe`
3. `C:/MinGW/bin/gcc.exe`
4. System PATH

If gcc is not found, compilation is skipped silently (uses existing `app/src/main/assets/inputbridge_client.exe`). The compiled exe is always copied to `app/src/main/assets/inputbridge_client.exe`. The APK export only bundles the exe plus `version.txt`; `control.bat` / `control.ps1` are no longer exported.

To compile manually outside Gradle:
```bash
cd windows
gcc -O2 -o inputbridge_client.exe inputbridge_client.c -lws2_32 -luser32
```

## Tests

```bash
./gradlew test
```

Unit tests are pure JVM (no device needed). Test files are under `app/src/test/`. They use integer constants instead of Android `KeyEvent` constants for portability.

## Project structure

- `app/src/main/java/com/steamlike/controller/` — all Kotlin source
  - `core/` — `SteamInput` (main controller), `MappingTypes` (data structures), `ControllerTypes` (enums), `ControllerDevice`
  - `injection/` — `GamepadInputView` (overlay), `InputBridgeServer` (TCP), `BridgeInputInjector` (VK mapping)
  - `mapping/` — `KeyboardMouseMapper`, `WoWActionSets` (layer presets, `LAYER_NAMES`)
  - `config/` — `ConfigManager`, `ControllerConfig` (JSON serialization), `AppConfig`
  - `service/` — `ControllerOverlayService` (foreground service, the main orchestrator)
  - `ui/` — `UiKit`
  - `MainActivity.kt`, `LayerEditActivity.kt`, `GamepadTestActivity.kt`
- `windows/` — C source and build scripts for the Windows companion
- `windows/inputbridge_client.c` — the Windows client (TCP recv → SendInput)

## Key architecture facts

- **Config is layered: Action Set → layers**. `ControllerProfile` holds a list of `ActionSet`s plus `activeActionSetName`. Each `ActionSet` owns its own public layer + operation layers. Switching action sets swaps all layers underneath as a whole. Backwards-compat accessors (`commonLayer`/`layers`/`allLayers`/`findLayer`) delegate to `activeActionSet`, so old call sites work unchanged.
- **Layer switching is driven by `SwitchLayer` mappings in the public layer**. `OperationLayer` has no `triggerButton` field; the layer-edit page's「切入按键」button reads/writes the public layer's `SwitchLayer` mapping for that layer.
- **Key resolution order**: active layers (in activation order) → public layer fallback.
- **Action set management lives in `LayerEditActivity`**: top spinner to switch, plus 添加/拷贝/改名/删除 dialogs. Copy deep-copies every layer's mapping table into a new `ActionSet` (fresh `LinkedHashMap`), so sets never share mutable maps. `createEmptyActionSet` builds a blank Common + 10 layers for new sets.
- **`ControllerOverlayService`** is the central orchestrator: creates `SteamInput`, `BridgeInputInjector`, `InputBridgeServer`, `KeyboardMouseMapper`, and the overlay views. The expanded overlay panel shows the current action set name (`操作集: X`); switching action sets refreshes the panel/mapping view/collapsed pill via `onActionSetSwitched`.
- **`LayerEditActivity.steamInputRef`** is a static reference set by the service — the settings UI depends on the service running.
- **LayerEditActivity pauses/resumes the overlay** on create/destroy (via Intent actions) to avoid blocking Android back gestures.
- **Pause capture removes the focus window**: `pauseCapturing()` truly removes the 1x1 focus window (restoring the Android predictive-back swipe gesture). Consequence: while paused, gamepad events can't reach the app, so the `ToggleCapture` gamepad key cannot resume capture — resume via the overlay "恢复捕获" button or MainActivity's "手柄捕获" switch.
- **Accessibility key-filtering is unavailable/deprecated**: `GamepadAccessibilityService` attempted `FLAG_REQUEST_FILTER_KEY_EVENTS` to receive gamepad keys while paused, but this MIUI device grants no key-filtering capability (capabilities=0, no "按键过滤" toggle in settings), so `onKeyEvent` never fires. The class is kept only for capability checks; new code must not rely on it.
- **IME keyboard keeps capture active**: `ToggleKeyboard` shows the soft keyboard bound to the 1x1 focus window (IME can only bind to this process's focused window). Typed text/keys are forwarded over TCP as Windows VK codes and injected via SendInput. Showing the keyboard does NOT pause capture.
- Config is persisted as version=3 JSON at `{internal storage}/files/steamlike_config.json`. `ControllerConfig.fromJson` dispatches by version: v3 = action-set format; v2 = legacy flat format, auto-migrated into a single "默认" action set. The exported `config.json` (`{"wowPath":...}`) tells the Windows client which game to launch.

## Gotchas

- **Proxy in `gradle.properties`**: HTTP/HTTPS proxy is hardcoded to `127.0.0.1:7897`. Remove or comment out if building without a local proxy.
- **Aliyun + Tencent mirrors**: `settings.gradle.kts` uses Aliyun mirrors; `gradle-wrapper.properties` uses Tencent mirror for Gradle 9.5.0. These may need changing outside China.
- **No instrumented tests**: only JUnit 4 unit tests exist. There are no `androidTest/` files.
- **GCC is optional**: the APK builds without GCC. The bundled exe in assets is used as fallback.
- **`val` immutability on `OperationLayer`**: `name` is `val`. Use `copy()` to modify it (see `LayerEditActivity`). Same applies to `ActionSet.name` — rebuild the `actionSets` list with `copy(name=...)` when renaming.
- **Single-process Windows client**: uses named mutex `Global\SteamLikeInputBridgeClient`.
- **IME double-input guard**: only `CONTROL_KEY_CODES` (ENTER/TAB/DPAD/ESCAPE/PAGE_UP/PAGE_DOWN/MOVE_HOME/MOVE_END) go through the key-event channel (`onImeKey`); printable chars are injected only via the text channel (`commitText`/`setComposingText`). Forwarding printables in `sendKeyEvent` too would double-inject because `BaseInputConnection(view, false)`'s fallback also dispatches characters to the view.
