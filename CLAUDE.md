# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Cross-session handoff

- On session start: invoke the `lsposed-mod-dev` skill first, then read [HANDOFF.md](HANDOFF.md) fully, then summarize the previous session's goal, current state, and next step before proceeding.

## Project Overview

`ala-mobile-tool` is an free, open-source LSPosed module for the Unity IL2CPP mobile F1 racing game **Ala Mobile** (package `com.Vince.AlamobileFormula`). It targets the modern **libxposed API 102** and uses native inline hooks to extend the game's input controls and DRS/aero behavior.

Source repository: `https://github.com/TakotsuboChen/ala-mobile-tool`
License: Apache-2.0

Supported game version: **Ala Mobile 8.0.4 (versionCode 200146)**. IL2CPP method offsets are version-specific; the module gates all native hooks behind `VersionGate`.

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
├── MusicPlayer               # Main menu music replacement (MediaPlayer + APK asset extraction)
├── IntroSoundPlayer          # Intro V10 engine sound replacement (MediaPlayer + APK asset)
├── Logger / LogExporter      # Unified logging (logEnabled-gated file output) + ShareSheet export
├── LogReceiver               # Static receiver for game→module log push (setComponent broadcast)
└── libala-core.so            # ShadowHook + IL2CPP inline hooks + native_log, built for arm64-v8a only
```

The module has three runtime contexts:

1. **ConfigActivity** runs in the module's own process. It reads/writes settings to a JSON file in external storage and presents a miuix-themed UI.
2. **AlaMobileModule** runs inside the target game process. It initializes the native bridge, installs the overlay, and reads configuration.
3. **libala-core.so** also runs in the target game process. It receives method offsets from Java and installs inline hooks on the IL2CPP runtime via ByteDance ShadowHook (`com.bytedance.android:shadowhook`).

## Common Commands

> Note: the Gradle project builds successfully with `./gradlew :app:assembleDebug`. Build environment: AGP 9.3.1, Kotlin 2.4.10, compileSdk 37, NDK 26.1.10909125. Maven mirrors (Aliyun) are configured in `settings.gradle.kts` to bypass Clash TUN TLS failures on `dl.google.com`.

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
- The CI workflow auto-derives `prerelease` from `versionName`: if it contains "Beta"/"Alpha"/"Pre" → `prerelease=true`, otherwise `prerelease=false`. No manual flip needed.

## IL2CPP Reverse Engineering

Reverse-engineering artifacts are generated from the local APK and should not be committed to GitHub.

Run Il2CppDumper (requires a local `Il2CppDumper` binary):
```bash
mkdir -p il2cpp-dumps/v8.0.4
Il2CppDumper /tmp/ala-mobile-research/native/lib/arm64-v8a/libil2cpp.so \
             /tmp/ala-mobile-research/il2cpp/assets/bin/Data/Managed/Metadata/global-metadata.dat \
             il2cpp-dumps/v8.0.4/
```

Important output files:
- `il2cpp-dumps/v8.0.4/dump.cs` — human-readable class/method/field dump.
- `il2cpp-dumps/v8.0.4/offsets_sheet.csv` — curated table of target methods and fields used by the module.

Update `OffsetTable.kt` after every IL2CPP dump.

## Key Code Conventions

- Package root: `tools.alamobile.mod`
- `AlaMobileModule` is the single `XposedModule` subclass and entry point. Register it in `src/main/resources/META-INF/xposed/java_init.list`.
- Keep the native bridge surface small. Java passes only resolved offsets and feature toggles to `libala-core.so`.
- All native IL2CPP hooks are gated by `VersionGate`: refuse to install if the game version is not exactly `8.0.4 (200146)`.
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
- `native/src/music_hook.c` — main menu music mute + heartbeat signal.
- `native/src/intro_hook.c` — intro V10 engine sound: mute introSound + one-shot signal.
- `native/src/hide_pedals_hook.c` / `native/src/hide_pedals_hook.h` — hide game-native throttle/brake buttons via IRDSUIMobileControls + il2cpp_runtime_invoke(SetActive).
- `native/src/native_log.h` / `native/src/native_log.c` — shared native file logging (logcat + file, logEnabled-gated).
- `app/src/main/kotlin/tools/alamobile/mod/util/Logger.kt` — unified Java logger (logcat + file, logEnabled-gated).
- `app/src/main/kotlin/tools/alamobile/mod/util/LogExporter.kt` — merge module+game logs, FileProvider → ShareSheet.
- `app/src/main/kotlin/tools/alamobile/mod/config/LogReceiver.kt` — static receiver for game→module log push via setComponent broadcast.
- `app/src/main/kotlin/tools/alamobile/mod/MusicPlayer.kt` — main menu music replacement player.
- `app/src/main/kotlin/tools/alamobile/mod/IntroSoundPlayer.kt` — V10 engine sound player.
- `app/src/main/resources/META-INF/xposed/module.prop` — libxposed module metadata.
- `app/src/main/resources/META-INF/xposed/scope.list` — target package list.

## TODO(human) Integration Points

Two areas are explicitly designated for human contribution during implementation:

1. `PedalOverlayView.updateValues(y: Float)` — implemented with deadzone, configurable transition point, and linear/quadratic/exponential curves. Fine-tune defaults and curve exponents based on real-device feel.
2. `native/src/drs_hook.c` — if telemetry polling is used, read `inDRSZone`, `throttle`, `steeringAngle`, `speed` from IL2CPP instance fields and evaluate DRS eligibility.

## Notes for Future Changes

- Do not commit the APK or any IL2CPP dump larger than GitHub's file size limit. Large files are excluded via `.gitignore`.
- miuix is a Kotlin Multiplatform library; keep Compose code in `ConfigActivity` and do not use it for runtime overlays.
- Before adding new native hooks, regenerate the IL2CPP dump and update `offsets_sheet.csv`/`OffsetTable.kt`.
- Coexistence APK build: use the `coex-apk-builder` skill (`.claude/skills/coex-apk-builder/SKILL.md`). Two paths maintained: LSPosed (root) and NPatch local mode (non-root, majority users). NPatch flow: Claude provides coex APK → Takotsubo injects via NPatch → self-signs with fixed keystore → distributes to end users.
