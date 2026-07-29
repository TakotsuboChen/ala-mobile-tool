# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Cross-session handoff

- Before you start anything, see [HANDOFF.md](HANDOFF.md) for the latest session state, verified status, failed approaches, and next steps.

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

## Notes for Future Changes

- Do not commit the APK or any IL2CPP dump larger than GitHub's file size limit. Large files are excluded via `.gitignore`.
- miuix is a Kotlin Multiplatform library; keep Compose code in `ConfigActivity` and do not use it for runtime overlays.
- Before adding new native hooks, regenerate the IL2CPP dump and update `offsets_sheet.csv`/`OffsetTable.kt`.
