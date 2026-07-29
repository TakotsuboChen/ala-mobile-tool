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

## Notes for Future Changes

- Do not commit the APK or any IL2CPP dump larger than GitHub's file size limit. Large files are excluded via `.gitignore`.
- miuix is a Kotlin Multiplatform library; keep Compose code in `ConfigActivity` and do not use it for runtime overlays.
- Before adding new native hooks, regenerate the IL2CPP dump and update `offsets_sheet.csv`/`OffsetTable.kt`.
