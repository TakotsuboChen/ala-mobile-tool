# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Cross-session handoff

- On session start: read [HANDOFF.md](HANDOFF.md) fully, then summarize the previous session's goal, current state, and next step before proceeding.

## Project Overview

`ala-mobile-tool` is an free, open-source LSPosed module for the Unity IL2CPP mobile F1 racing game **Ala Mobile** (package `com.Vince.AlamobileFormula`). It targets the modern **libxposed API 102** and uses native inline hooks to extend the game's input controls and DRS/aero behavior.

Source repository: `https://github.com/TakotsuboChen/ala-mobile-tool`
License: Apache-2.0

Supported game version: **Ala Mobile 8.0.0 (versionCode 200142)**. IL2CPP method offsets are version-specific; the module gates all native hooks behind `VersionGate`.

## Development Decisions (already approved)

- Language: Kotlin for Android/Xposed code, C for native hooks.
- UI framework: Jetpack Compose with `top.yukonga.miuix.kmp:miuix-ui`.
- Hook strategy:
  - Java layer (libxposed API 102): module entry, overlay injection, configuration reading.
  - Native layer: ByteDance ShadowHook inline hooks on `libil2cpp.so` for gameplay logic.
- Auto DRS: prefer hooking the game's own DRS input check; currently swallows unwanted toggles while a user DRS request is active.
- Multiplayer: do not detect Photon; show a warning and a master toggle, leave responsibility to the user.

## High-level Architecture

```
AlaMobileTool (LSPosed module APK)
├── ConfigActivity            # Compose + miuix configuration UI (module process)
├── AlaMobileModule           # XposedModule entry point (game process)
├── NativeBridge              # JNI bridge: pass IL2CPP offsets, control throttle/brake/gear/DRS
├── PedalOverlayView          # Dual-zone vertical throttle/brake pedal (raw Android Canvas View)
├── GearShiftView             # Upshift/downshift buttons (raw Android Canvas View)
├── OverlayManager            # Adds overlay Views via WindowManager above UnityPlayerActivity
└── libala-core.so            # ShadowHook + IL2CPP inline hooks, built for arm64-v8a only
```

The module has three runtime contexts:

1. **ConfigActivity** runs in the module's own process. It reads/writes settings to a JSON file in external storage and presents a miuix-themed UI.
2. **AlaMobileModule** runs inside the target game process. It initializes the native bridge, installs the overlay, and reads configuration.
3. **libala-core.so** also runs in the target game process. It receives method offsets from Java and installs inline hooks on the IL2CPP runtime via ByteDance ShadowHook (`com.bytedance.android:shadowhook`).

## Common Commands

> Note: the Gradle project builds successfully with `./gradlew :app:assembleDebug`. Build environment: AGP 8.9.1, Kotlin 2.4.0, compileSdk 37, NDK 26.1.10909125. Maven mirrors (Aliyun) are configured in `settings.gradle.kts` to bypass Clash TUN TLS failures on `dl.google.com`.

Build the module APK:
```bash
./gradlew :app:assembleDebug
```

Build release:
```bash
./gradlew :app:assembleRelease
```

Install debug APK to a connected device:
```bash
./gradlew :app:installDebug
```

Run lint:
```bash
./gradlew :app:lint
```

Clean build outputs:
```bash
./gradlew clean
```

## Version Naming Convention

The project uses a 6-digit `versionCode` encoding the semantic version, release stage, stage sequence, and a reserved digit. All version-bearing files (`app/build.gradle.kts`, `module.prop`) must stay in sync, and CI renames the built APK to match.

### versionCode encoding (6 digits: `A B C 阶段 D 0`)

| Digit | Meaning |
|---|---|
| `A.B.C` | Semantic version (major.minor.patch) |
| 阶段 | Release stage: `1`=Alpha, `2`=Beta, `3`=Stable |
| `D` | Stage sequence number (Alpha/Beta only); `0` for Stable |
| last `0` | Reserved (currently always `0`) |

### Examples

| Release | versionName | versionCode | APK filename |
|---|---|---|---|
| Alpha 3 of 1.5.9 | `1.5.9 Alpha 3` | `159130` | `Ala Mobile Tool v1.5.9 Alpha 3.apk` |
| Beta 1 of 1.0.0 | `1.0.0 Beta 1` | `100210` | `Ala Mobile Tool v1.0.0 Beta 1.apk` |
| Stable 1.5.9 | `1.5.9` | `159300` | `Ala Mobile Tool v1.5.9.apk` |

### Rules

- **versionName style**: space-separated, no `v` prefix — `1.0.0 Beta 1`, `1.5.9 Alpha 3`, `1.5.9` (stable has no stage label).
- **Stable releases**: stage digit = `3`, `D = 0`, and the stage label is omitted from versionName and filename. Never `310`.
- **APK filename**: `Ala Mobile Tool v<versionName>.apk` (note the `v` prefix is added only in the filename, not in versionName).
- **CI build APK** (non-tag push): `Ala Mobile Tool v<versionName> CI.apk` — `versionCode` stays identical to the latest Release; the ` CI` suffix is the only distinguisher.
- **Single source of truth**: `versionName` lives in `app/build.gradle.kts`; CI extracts it from there to derive the APK filename. `module.prop` `version` must mirror it. `versionCode` must be consistent with the stage/sequence per the table above.

### Release Stage Policy

- **Stable releases** publish as GitHub **Release** (`prerelease=false`).
- **Alpha/Beta releases** publish as GitHub **Pre-release** (`prerelease=true`).
- The CI workflow's `Upload to Release` step currently hardcodes `prerelease: true` — correct for Alpha/Beta, but must be flipped to `false` (or auto-derived from `versionName`) when publishing a Stable release.

## IL2CPP Reverse Engineering

Reverse-engineering artifacts are generated from the local APK and should not be committed to GitHub.

Run Il2CppDumper (requires a local `Il2CppDumper` binary):
```bash
mkdir -p il2cpp-dumps/v8.0.0
Il2CppDumper /tmp/ala-mobile-research/native/lib/arm64-v8a/libil2cpp.so \
             /tmp/ala-mobile-research/il2cpp/assets/bin/Data/Managed/Metadata/global-metadata.dat \
             il2cpp-dumps/v8.0.0/
```

Important output files:
- `il2cpp-dumps/v8.0.0/dump.cs` — human-readable class/method/field dump.
- `il2cpp-dumps/v8.0.0/offsets_sheet.csv` — curated table of target methods and fields used by the module.

Update `OffsetTable.kt` after every IL2CPP dump.

## Key Code Conventions

- Package root: `tools.alamobile.mod`
- `AlaMobileModule` is the single `XposedModule` subclass and entry point. Register it in `src/main/resources/META-INF/xposed/java_init.list`.
- Keep the native bridge surface small. Java passes only resolved offsets and feature toggles to `libala-core.so`.
- All native IL2CPP hooks are gated by `VersionGate`: refuse to install if the game version is not exactly `8.0.0 (200142)`.
- Overlay Views use raw Android Canvas (not Compose) because Compose cannot overlay reliably on a Unity SurfaceView.
- A JSON file in external storage is used for configuration. The ConfigActivity writes; `AlaMobileModule` reads.

## Files to Know

- `app/src/main/kotlin/tools/alamobile/mod/AlaMobileModule.kt` — LSPosed entry point.
- `app/src/main/kotlin/tools/alamobile/mod/ConfigActivity.kt` — miuix Compose settings UI.
- `app/src/main/kotlin/tools/alamobile/mod/NativeBridge.kt` — JNI declarations.
- `app/src/main/kotlin/tools/alamobile/mod/overlay/PedalOverlayView.kt` — dual-zone pedal.
- `app/src/main/kotlin/tools/alamobile/mod/util/VersionGate.kt` — version gating.
- `native/src/ala_core.c` — native entry points and ShadowHook init.
- `native/src/pedal_hook.c` — throttle/brake/gear hook logic + input writer thread.
- `native/src/drs_hook.c` — auto DRS / active aero hook logic.
- `native/src/unlock_hook.c` — billing/unlock IL2CPP hook logic.
- `app/src/main/resources/META-INF/xposed/module.prop` — libxposed module metadata.
- `app/src/main/resources/META-INF/xposed/scope.list` — target package list.

## TODO(human) Integration Points

Two areas are explicitly designated for human contribution during implementation:

1. `PedalOverlayView.updateValues(y: Float)` — implemented with deadzone, configurable transition point, and linear/quadratic/exponential curves. Fine-tune defaults and curve exponents based on real-device feel.
2. `native/src/drs_hook.c` — if telemetry polling is used, read `inDRSZone`, `throttle`, `steeringAngle`, `speed` from IL2CPP instance fields and evaluate DRS eligibility.

## Current Progress

- M1: Module skeleton, LSPosed entry, native build, and overlay scaffolding — done.
- M2: ByteDance ShadowHook integration and real inline hooks for throttle/brake/DRS — done.
- M3: Configurable MIUIX settings UI, JSON config, pedal mapping curve, and release packaging — done.
- M4: Overlay display and pedal input verification in-game — done. Shift hooks and DRS auto logic remain pending.
- M5: Overlay editor sync, pedal stutter mitigation, and ConfigActivity three-tab UI refactor — done (v1.0.0-Alpha-2). Published with KernelSU-style UI, dark mode support, and demo videos.
- M6: Coexistence build stability overhaul (v1.0.0-Beta-1, 100210) — done. Fixed dual-ClassLoader injection (System.setProperty guard), abolished non-atomic file IPC in favor of JNI direct path, and switched overlay touch mapping to rawY + configured position to survive pairip relayout drift. CI auto-builds + tag-triggered Pre-release via `.github/workflows/build.yml`.
- M7: CI/CD repair — done. CI now truly builds APK (lint + assembleRelease) and uploads to Release on tag push. Fixed lint false-green (`|| true` + `continue-on-error` swallowed 3 NewApi errors, replaced with lint baseline), upgraded actions to v7/v5/v3/v7 (Node 20 deprecation removed), cloned runner's `android-37.0`→`android-37` with 4 path-identifier sed fixes (AGP integer ApiLevel comparison), and conditionalized Aliyun mirror via `System.getenv("CI")` to bypass 502 Bad Gateway. M7 extended: configured `KEYSTORE_BASE64` secret + `Decode release keystore` workflow step (env-guarded, `KEYSTORE_PATH` only set on successful decode), CI now produces release-signed APK verified by apksigner fingerprint match; tag→Release→APK-upload loop closed-loop verified with test tag `v1.0.0-Beta-2-test` (cleaned up after); real-device smoke test passed (overlay/pedal/shift/billing) on both original and coexistence builds.
- M8: Version naming convention + CI artifact `archive:false` — done. Landed 6-digit versionCode encoding (`A.B.C 阶段 D 0`, stage 1=Alpha/2=Beta/3=Stable, D=sequence for Alpha/Beta, D=0 for Stable; e.g. `159300` for stable 1.5.9, never `310`), space-separated versionName style (`1.0.0 Beta 1`, no `v` prefix), and `v` prefix only in APK filename. CI workflow adds `Rename APK to project naming convention` step (greps versionName from build.gradle.kts as single source of truth) and uses `actions/upload-artifact@v7` `archive: false` (2026-02 platform feature) to produce directly-downloadable named apk artifacts (no zip shell). Release assets verified to preserve spaces in browser (`Ala Mobile Tool v1.0.0 Beta 2.apk`). Closed-loop verified: Beta 1 CI artifact = `Ala Mobile Tool v1.0.0 Beta 1 CI.apk` (2073332 bytes, direct apk), Beta 2 tag → Pre-release with space-preserving asset (cleaned up after), Beta 1 regression CI green. Release stage policy: Stable=Release, Alpha/Beta=Pre-release (workflow hardcodes `prerelease: true`, flip to `false` for Stable).
- M9: Configure page UI refactor + "manual shift" switch wiring — done. Added "Overlay 控件" section grouping input-related toggles (show overlay / linear pedal / manual shift), renamed "踏板覆盖"→"线性踏板" (UI-only rename, field `enableControlReplacement` unchanged for JSON compat), removed standalone "关闭自动换挡" switch (its semantics merged into "手动换挡": enabling manual shift ⇒ disabling game auto-shift). New `enableManualShift` config field flows through `ModConfig` → `ConfigUiState` → `ConfigMainScreen` save → `AlaMobileModule` (derives `disableAutoGear = enableManualShift`, native signature unchanged) → `OverlayManager` (gates `GearShiftView` creation + `addGearEditLayer`). Switch is UI-disabled but bound to real state, so the current false actually suppresses the gear overlay and keeps game auto-shift on. Also fixed `toggleOverlays`/`toggleEditMode` to only check `pedalView` for re-add trigger (gearView null is normal when manual shift off, old condition caused duplicate pedalView add). Build green; real-device smoke test pending.
- M10: Pedal topology dropdown + dual-pedal + curve split — UI done. "线性踏板" Switch→dropdown (OFF/SINGLE/DUAL via new `ModConfig.PedalMode` enum, JSON `pedal_mode`, migrates legacy `enable_control_replacement` bool→mode). Deadzone/transition moved into "Overlay 控件" Card, shown only in SINGLE mode with `AnimatedVisibility` (expandVertically/shrinkVertically). `pedal_curve` split into `throttle_curve`+`brake_curve` (legacy `quadratic`→EXPONENTIAL on read). `PedalCurve` enum slimmed to LINEAR/EXPONENTIAL. `PedalOverlayView` gained `PedalRole`(SINGLE/THROTTLE/BRAKE) + `position` params; `applyCurve` exponent changed to 0.42 (ease-out, 30%→60%) — direction fixed from old ≥1 ease-in. `OverlayManager` creates 0/1/2 pedal views per mode (DUAL adds `brakeView`+`brakeEditView` + `DEFAULT_BRAKE` position). Also: bottom-bar overlap fixed (three pages accept `bottomBarHeight` param threaded from outer Scaffold); Card row padding unified to 12dp vertical + 16dp horizontal (SwitchRow 8→12, OverlayDropdownPreference/BasicComponent get explicit `insideMargin`); auto-DRS default false + read forced false (feature unimplemented); "解锁付费内容" moved above auto-DRS + summary trimmed.
- M11: Config IPC fix + curve/brake direction — done. Root cause of M10 runtime not effective was NOT just `by lazy` caching, but **Android 11+ scoped storage + package visibility dual isolation**: module process writes module `filesDir` (reachable), game process can't read it (different sandbox uid + package visibility blocks `createPackageContext`/ContentProvider with `NameNotFoundException`). Fix: ConfigActivity writes module `filesDir` (persistence) + sends targeted broadcast `Intent.setPackage(gamePkg)` (bypasses visibility — direct dispatch, no PackageManager query); game process `ConfigReceiver` (registered in `onPackageReady` with `RECEIVER_EXPORTED` for Android 13+) receives and writes game's own `getExternalFilesDir` (game process native read/write); `OverlayManager.readFromTargetProcess` reads same path. `OverlayManager.settings` changed from `by lazy val` to re-readable `var`; `showOverlays`/`toggleOverlays`/`toggleEditMode` re-read JSON + `removeGamingOverlays`+rebuild (separate from `removeExisting` which also clears toggle button). `PedalOverlayView` split raw/mapped values: `rawThrottle`/`rawBrake` (finger displacement, used for `onDraw` — visual follows finger) vs `mappedThrottle`/`mappedBrake` (curve-transformed, sent to native — nonlinear game input). BRAKE visual changed to `drawRect(0, top, width, height)` where `top=height*(1-rawBrake)` — fills from finger position down to bottom, mirror-symmetric with THROTTLE (fills bottom-up to finger). Real-device verified: dual-pedal + exponential curve effective, visual follows finger, brake direction correct. Known limit: first install + first game launch reads defaults (receiver not registered yet); after user changes config once and toggles, persists. `ConfigProvider.kt` left in tree but unused (broadcast superseded it; can delete).
- M12: Config instant-effect + coordinate system + layout persistence — done. Three fixes: (1) **Instant effect**: `ConfigReceiver.onReceive` after merge-write calls `OverlayManager.notifyConfigChanged()` (static, post to main thread) → `rebuildFromConfigChange()` (refreshRoot → re-read settings → removeGamingOverlays → addGamingOverlays → restore `overlaysVisible` visibility → `updateEditModeVisibility` if editMode). Fixes M11's leftover "must toggle to apply" — `PedalOverlayView` constructor copies `ModConfig.Settings` snapshot (value semantics), so writing JSON alone isn't enough; must rebuild view. Dual-ClassLoader safe: 2nd ClassLoader's `instance` is null (never constructed, `isNativeInstalled` guard) → `notifyConfigChanged` no-op. (2) **Coordinate system**: `DEFAULT_PEDAL`/`DEFAULT_BRAKE`/`DEFAULT_GEAR` changed to bottom-left-origin percentages user specified (pedal (80,55),(95,55),(80,5),(95,5); brake (5,55),(20,55),(5,5),(20,5); gear=brake). Internal storage stays Android-native (top-left origin, y-down) — only default values changed; user coords convert: `top_android=1-top_user`, `height=top_user-bottom_user`. All conversion helpers (`topPx`/`leftPx`/`widthPx`/`heightPx`/`fromPixels`) unchanged. (3) **Layout persistence** (most severe bug): `ModConfig.write` drops `put(KEY_PEDAL_POSITION/GEAR/BRAKE)` 3 lines — broadcast JSON no longer carries position; `ConfigReceiver.onReceive` merges (reads existing game `externalFilesDir` JSON, incoming overwrites non-position keys, position keys preserved from game process). Root cause was `ConfigMainScreen.saveNow` creating `Settings` without position fields → defaults → broadcast carried default position → `ConfigReceiver.writeText` overwrote game's drag-saved position. Position state now localizes in game process (`ConfigReceiver` merge + `saveOverlayPosition` drag-write + `readFromTargetProcess` read). Also: `root` changed `val`→`var` + `refreshRoot()` (Activity may rebuild after broadcast arrives, stale decorView invalidates addView/removeView); `removeGamingOverlays`/`removeExisting` use local `val parent=root?:return` snapshot to dodge smart-cast limit on mutable property; DUAL+manual-shift runtime guard (`enableManualShift && pedalMode != DUAL` gates `gearView` creation — user requires mutual exclusion, switch UI still disabled, guard prevents JSON-edit/future-UI-bug overlap). Known limit: original/coexistence builds don't share layout (Android sandbox isolates `externalFilesDir` by package name — expected, not a bug). Build green; APK installed; real-device test pending.
- M13: Long-press reset to factory default + SINGLE/DUAL position isolation + curve softening + DUAL pedal arbitration + brake-transition UI — done, real-device verified. Five fixes: (1) **Long-press reset**: `OverlayEditView` constructor split into `defaultPosition` (= `OverlayPosition.DEFAULT_*` factory default, reset target) + `runtimePosition` (runtime saved value, initial layout alignment). Fixes M12's leftover "`resetPosition` resets to runtime saved position not factory default" — `OverlayManager.addPedalEditLayer/addBrakeEditLayer/addGearEditLayer` now explicitly pass `DEFAULT_PEDAL/BRAKE/GEAR` as `defaultPosition` and `settings.*Position` as `runtimePosition`. (2) **SINGLE/DUAL position isolation**: new `KEY_SINGLE_PEDAL_POSITION` + `singlePedalPosition` field (default `DEFAULT_PEDAL`); `ConfigReceiver.POSITION_KEYS` adds `single_pedal_position` (broadcast merge preserves it). `OverlayManager` SINGLE branch uses `singlePosition` + `KEY_SINGLE_PEDAL_POSITION`; DUAL throttle still uses `pedalPosition` + `KEY_PEDAL_POSITION` — the two are physically isolated JSON slots, fixing "DUAL throttle inherits SINGLE's dragged position" bug (root cause: both read same `pedal_position`). (3) **Curve softening**: `applyCurve` exponent `0.42`→`0.66` (30%→45%, `0.3^0.66≈0.45`), less aggressive per user feedback. (4) **DUAL pedal arbitration**: `PedalOverlayView.companion` holds `@Volatile sharedRawThrottle/sharedRawBrake/sharedBrakeTransition/arbitratedThrottle/arbitratedBrake`; `updateDedicatedThrottle/Brake` each update shared raw then call `arbitrateDual()`: `brake≥brakeTransition && brake>0` → brake priority, shield throttle mapped (`arbitratedThrottle=0`); `brake<brakeTransition && throttle>0` → throttle priority, shield brake mapped (`arbitratedBrake=0`). Shield applies only to mapped/native; raw still follows finger for drawing (consistent with M11 raw/mapped split — visual feedback both fingers, game receives only priority side's input). `ACTION_UP/CANCEL` clears this view's shared raw (avoids stale-arbitration when one finger lifts but other remains). SINGLE mode bypasses `arbitrateDual` (single-view `updateSingle` is self-consistent). (5) **Brake-transition UI**: new `KEY_BRAKE_TRANSITION` + `brakeTransition` field (default 0.1); `ConfigurePage` adds `AnimatedVisibility` under "线性踏板" dropdown that expands a "刹车过渡点" slider (0–20%) when `pedalMode==DUAL`, symmetric with SINGLE's deadzone/transition. Build green; APK installed (381QYFCN22B9A); real-device test passed (user confirmed "可以了，目前测试通过"). **Post-M13 CI fix**: M11's `ConfigReceiver` registration used a hand-written `if (SDK_INT >= TIRAMISU)` branch; the `else` branch `context.registerReceiver(receiver, filter)` (no flag) tripped lint `UnspecifiedRegisterReceiverFlag` — CI `lintDebug` failed on M11/M12/M13. Fix: replace the branch with `ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)` (AndroidX auto-dispatches by SDK_INT, lint-friendly). `androidx.core` arrives transitively via `activity-compose`; no new dependency. Local `:app:lint` green; CI run 30514831609 all-green.
- M14: Direction-key fix + config-sync bug root-caused — **direction-key fix done & real-device verified; config-sync migration to XSharedPreferences/openRemoteFile pending next session**. (A) **Direction-key fix** (`native/src/pedal_hook.c`): root cause — `input_writer_thread` at ~500Hz continuously wrote `0.0f` to throttle/brake fields even when user wasn't pressing the module pedal, overwriting the game's own input. `ButtonsSteering` (on-screen direction keys) mode's steering assist (`steerHelp`/`LockSteerAtVelocity`/`TractionFilter`) depends on throttle/brake values to decide assist strength; continuously zeroed → steering assist fails → direction keys don't steer. Gyroscope mode doesn't depend on throttle/brake for steering, so unaffected (matches user report "gravity-sensor/gyroscope users unaffected"). Fix: writer thread early-returns when `!g_throttle_active && !g_brake_active` (writes nothing); `apply_inputs_to_controller` drops the `else if (value<=0) zero` branch (only writes when active); new `clear_throttle_field`/`clear_brake_field` called by `pedal_set_throttle_value`/`pedal_set_brake_value` on active→inactive transition (writer no longer polls-zero, so release moment must actively clear once, else value sticks). Real-device verified: "方向键和摇杆都正常了". (B) **Config-sync M11 first-launch-lag bug root-caused**: real-device logcat captured full evidence — game running + config change → broadcast received + file written (`ConfigReceiver: merged`); game killed + config change → broadcast sent but **no `ConfigReceiver: merged` log** (game not running, directed broadcast dropped by system); next game launch `readFromTargetProcess` reads stale file. Mechanism: broadcast is instantaneous dispatch, dropped if target process not running. **All conventional cross-process paths verified failed on MeiZu 20 / Android 16 / game targetSdk 35**: module filesDir → game EACCES (uid isolation); public `/sdcard/AlaMobileTool/` → module EACCES write (scoped storage bans writing external storage root, WRITE_EXTERNAL_STORAGE ineffective for targetSdk 30+); ContentProvider → `Unknown authority` (package visibility, game can't query module provider even with manifest exported=true); `createPackageContext(MODULE_PKG, CONTEXT_IGNORE_SECURITY)` → `NameNotFoundException` (package visibility); module APK `/data/app/.../base.apk` → game **readable** (`-rw-r--r--`) but read-only. **Two subagents researched LSPosed official cross-process config-sync mechanisms**: (1) `XSharedPreferences` — LSPosed wiki "New XSharedPreferences" (API 93+), all mainstream open-source modules use it (Pixelify-Google-Photos/SimpleHook/StarVoyager/NexAlloy etc.); write `getSharedPreferences(name, MODE_WORLD_READABLE).edit().apply()`, read `XSharedPreferences(pkg, name).reload().getString(...)`; reads disk XML, ignores target-process liveness; needs manifest `xposedsharedprefs=true` meta-data; can delete entire broadcast+JSON+ConfigReceiver+ConfigProvider IPC layer. (2) `openRemoteFile(String)`/`getRemotePreferences(String)` — libxposed API 102 `XposedModule` base-class methods (from `XposedInterfaceWrapper`); Binder to LSPosed daemon (system uid, can read any filesDir), bypasses uid isolation; minimal change (module writes filesDir unchanged, only `readFromTargetProcess` switches to openRemoteFile). Next session: try `openRemoteFile()` first (API 102 native, minimal change); fallback to XSharedPreferences if unavailable. Failed experiments (`ModConfig.write` public-dir write, `readFromTargetProcess` public-file pull, `pullLatestViaProvider` ContentProvider+createPackageContext probe) all reverted clean; diagnostic logs (`readFromTargetProcess: path=... lastModified=...`) retained for next-session debugging; `ConfigReceiver.POSITION_KEYS` refactored to reference `ModConfig.POSITION_KEYS` (promoted to public val) to avoid duplicate maintenance. Build + lint green. (C) **Brake pedal direction invert (M14 sub-slice, done & real-device verified)**: DUAL mode "刹车踏板方向反转" switch under the brake-transition slider in `ConfigurePage`'s DUAL `AnimatedVisibility` block. New `KEY_BRAKE_INVERT` + `Settings.brakeInvert` (default false) flows through `ModConfig` (read/write/fromJson/defaultSettings) → `ConfigUiState.brakeInvert` → `saveNow` → broadcast → `ConfigReceiver` → `OverlayManager.rebuildFromConfigChange` reconstructs `PedalOverlayView` with new settings snapshot. `updateDedicatedBrake` takes `raw = if (brakeInvert) t else 1-t`; `onDraw` BRAKE branches: default `drawRect(0, top, w, h)` (red anchored bottom, grows up — original behavior), inverted `drawRect(0, 0, w, bottom)` (red anchored top, grows down). raw drives drawing + arbitration, mapped drives native — all three flip together, no native/arbitration changes needed (M11 raw/mapped split pays off here). Summary text corrected once ("开启后红色改为从上往下生长（默认从下往上）") after first version used ambiguous "A 改为 B" Chinese phrasing. Real-device verified: "可以了，功能正常". Build + lint green. **(D) Config-sync migration (M14-B, done & real-device verified)**: switched to **Remote Preferences** (libxposed API 102 `getRemotePreferences`). Root cause of prior failed attempts: `openRemoteFile` reads LSPosed daemon dir (`/data/adb/lspd/modules/<userId>/<pkg>/files/`), NOT module `filesDir` (`/data/data/<pkg>/files/`) — ConfigActivity wrote one, reader read the other; logcat `must not be null` + `listRemoteFiles:` empty confirmed. `de.robv.android.xposed.XSharedPreferences` is **forbidden by libxposed API 102** (XposedInterface.java L42 comment), and LSPosed v2.1.0 removed the "New XSharedPreferences" compat layer — so HANDOFF's prior "fallback XSharedPreferences" was itself a dead end (a research blind spot). Fix: new `App : Application, XposedServiceHelper.OnServiceListener` connects LSPosed daemon's `XposedService` (async bind in onCreate); `ModConfig.write` prioritizes `service.getRemotePreferences("ala_mobile_tool").edit().putString("config_json", json).apply()` (daemon SQLite), falls back to filesDir + broadcast if service not bound yet (zero regression). `readFromTargetProcess` reads via injected `remoteConfigReader` (AlaMobileModule.onPackageReady sets it to call `getRemotePreferences(PREF_GROUP).getString(KEY_CONFIG_JSON)`), merges local externalFilesDir position fields (daemon JSON has no position). Broadcast + ConfigReceiver + notifyConfigChanged retained as runtime instant-update + service-async-bind fallback. Real-device verified: user changed SINGLE→DUAL, launched game, immediately read DUAL; logcat `getRemotePreferences ok len=393` + `Config via remote prefs (merged local position): pedalMode=DUAL` (21:01:41). Build + lint green. Next: optional cleanup of dead `ConfigProvider.kt` + manifest provider declaration, then release Beta 3 (`1.0.0 Beta 3`, versionCode `100230`).
- M15: Activation card UI rework — **done & real-device verified**. Reworked the overview "激活状态" card to mirror KernelSU's `HomeMiuix.kt` StatusCard: symmetric emphasized backgrounds for both states (activated = green tones `#1A3825` dark / `#DFFAE4` light + green check `#36D167`; inactive = red tones `#3D1A1A` dark / `#FAE4E4` light + red cross `#FF5252`), text white in dark mode / theme color in light mode. The Non-root confirmation dialog switched from hand-rolled `androidx.compose.ui.window.Dialog` + miuix Card to the official miuix `OverlayDialog` (copied from `references/miuix/example/shared/.../component/CardSection.kt`'s LongPressHoldDownCardDemo pattern: `OverlayDialog(show, title, onDismissRequest, content)` + two `TextButton`, "是" uses `ButtonDefaults.textButtonColorsPrimary()`). **Critical correction during work**: I initially replaced the KernelSU green palette with miuix semantic tokens (`primaryContainer`/`primaryVariant`) — but miuix default primary is blue (`#3482FF`), not green, so it clashed with the green check icon and the user saw "纯黑底" in dark mode (default `surface` ≈ black). Restored the hardcoded KernelSU green values per user's explicit pointer to KernelSU's `HomeMiuix.kt`. Local reference repo `references/miuix` (shallow clone) added under `.gitignore`'s new `references/` rule for offline API consultation. Build + lint green.
- M16: LSPosed real-activation detection + overview icons — **done code-wise; real-device visual verification pending**. Two sub-slices: (A) **Activation detection rework** (`LsposedStatus.kt`): switched `evaluate` from `System.getProperty(MODULE_LOADED_FLAG)` (dead path — `onModuleLoaded` only fires in injected target App process, ConfigActivity process is never injected → property never set → always "未激活") to `App.xposedService != null` (daemon binder = Manager currently enables module). daemon binder only fires via `XposedProvider.call("SendBinder")` when LSPosed Manager enables the module; user disables module → no trigger → service null. Semantics = "Manager currently enabled" (user confirmed this via AskUserQuestion). Removed process-level property read + polling (unreachable); removed daemon `module_loaded` persistent write (superseded by service bind state). Non-root manual confirm path kept unchanged — LSPatch uses legacy `assets/xposed_init` + `de.robv.android.xposed.XposedInit` (doesn't go through libxposed API 102's `onModuleLoaded`), doesn't bind daemon → `xposedService == null` → auto-falls to Non-root manual path, no explicit isLSPatch check needed. Parameter `awaitModuleLoad` → `awaitService` (poll target changed to daemon async bind). `AlaMobileModule.markActivated` dropped daemon `module_loaded` write; kept `System.setProperty(MODULE_LOADED_FLAG)` for backward-compat (`clearAll` still clears it). (B) **Overview LinksCard icons** (`OverviewPage.kt`): added `svgIcon(name, svgPath)` helper — uses `PathParser().parsePathString(svg).toNodes()` to parse SVG `d` string, dispatches via `when` over `PathNode` sealed subclasses in `path()` DSL's `PathBuilder` lambda (supports all M/L/H/V/C/Q/A/Z commands + relative/reflective variants). `GithubMark` (Octocat) + `QqMark` (QQ penguin) as top-level vals, paths from simple-icons (CC0). LinksCard: "GitHub 源代码" row `Icons.Rounded.Info`→`GithubMark`, "QQ 群" row `Icons.Rounded.Phone`→`QqMark`. Cloned `references/libxposed-api`, `references/libxposed-service`, `references/LSPatch` (shallow, covered by `.gitignore` `references/` rule) for API consultation. Build + lint green; APK installed to 381QYFCN22B9A; real-device visual verification pending user. Next: optional cleanup of dead `ConfigProvider.kt` + manifest provider declaration. **Beta 2 released 2026-07-31** (`versionCode 100220`, `versionName "1.0.0 Beta 2"`, Pre-release): three files synced (`app/build.gradle.kts` + `module.prop` + README 版本历史), README rewritten to reflect M9~M16 演进, CI run `30556486131` 全绿, release 签名 APK `Ala Mobile Tool v1.0.0 Beta 2.apk` uploaded, Release notes 贴上. M16 真机视觉验证仍待用户确认（Beta 2 唯一遗留）.


## Notes for Future Changes

- Do not commit the APK or any IL2CPP dump larger than GitHub's file size limit. Large files are excluded via `.gitignore`.
- miuix is a Kotlin Multiplatform library; keep Compose code in `ConfigActivity` and do not use it for runtime overlays.
- Before adding new native hooks, regenerate the IL2CPP dump and update `offsets_sheet.csv`/`OffsetTable.kt`.
