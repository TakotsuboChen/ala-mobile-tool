# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Cross-session handoff

- On session start: invoke the `lsposed-mod-dev` skill first, then read [HANDOFF.md](HANDOFF.md) fully, then summarize the previous session's goal, current state, and next step before proceeding.

## Project Overview

`ala-mobile-tool` is an free, open-source LSPosed module for the Unity IL2CPP mobile F1 racing game **Ala Mobile**. It targets the modern **libxposed API 102** and uses native inline hooks to extend the game's input controls and DRS/aero behavior.

Two game packages are supported (module is developed for both):
- **官版 (official)**: `com.Vince.AlamobileFormula`
- **共存版 (coexistence, renamed repackage)**: `com.Takotsubo.AlamobileFormula` — same game, different package name so it installs alongside the official one. All package-name checks (VersionGate, scope, log paths) must handle both.

Source repository: `https://github.com/TakotsuboChen/ala-mobile-tool`
License: Apache-2.0

**Sister repository**: `../ala-mobile-paddock` (`https://github.com/TakotsuboChen/ala-mobile-paddock`) — paddock (围场) private-server backend (Rust axum + Postgres + Garage, Apache-2.0). **已上线** `https://paddock.takotsubo.cloud`（VPS 8.134.50.222，Docker Compose 三容器 app/postgres/garage，反代由 1Panel openresty 终结 443 → 127.0.0.1:8080；Caddy 已弃用于该栈）。管理端 `/admin`（凭据在 VPS `~/paddock/.env`）。QQ bot webhook `/qq/webhook`（凭据经管理端设置页落库 configs 表）。Development sessions always run in **this** repo; both sides are developed together in one session. The sole contract source is `docs/PADDOCK_PLAN.md` (API shapes, points formula, track display names) — any contract change must update **both** repos in the same session.

Supported game version: **Ala Mobile 8.0.4 (versionCode 200146)**. IL2CPP method offsets are version-specific; the module gates all native hooks behind `VersionGate`.

**Log pulling (实机日志拉取)**: game-side logs live at `/sdcard/Android/data/<游戏包>/files/ala_tool.log` (Java) + `ala_tool_native.log` (native), where `<游戏包>` is **either** package name above depending on which build the user runs. Device log file timestamps are the fastest way to tell which build produced a given log.

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

> ⚠️ `:app:assembleDebug/Release` do **not** run lint — CI's `:app:lint` step is the only lint gate. After any Kotlin/Java change, run `./gradlew :app:lint` (require 0 errors) before pushing; misses surface as long streaks of red CI runs (once 20+ from a single API-29 call under minSdk 26).

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

**游戏 APK 本地副本在项目内，不要去 /tmp 找临时解包目录（WSL 重启即丢），也不要 adb pull：**

- `安装包/` — 各版本成品安装包（如 `Ala Mobile 8.0.4 Takotsubo 共存版.apk`）
- `build/v<版本>-official/base.apk` + `build/v<版本>-official-native/split_config.arm64_v8a.apk` — 官方版分包，解 `lib/arm64-v8a/libil2cpp.so` 用后者
- `build/` 下还有各历史版本的 base.apk / split_config.arm64_v8a.apk
- global-metadata.dat 在 base.apk 的 `assets/bin/Data/Managed/Metadata/` 内

Run Il2CppDumper (requires a local `Il2CppDumper` binary)——先从上面的项目内 APK 解出两个输入文件：
```bash
mkdir -p il2cpp-dumps/v8.0.4
Il2CppDumper <解出的 libil2cpp.so> \
             <解出的 global-metadata.dat> \
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
- Native hooks that intercept or override gameplay behavior (TC/ABS disable, future ESC tuning) must gate on the whitelist comparison `is_target_player_car` (`this == g_player_controller`), never on the `is_player_controller` field probe — the `playerControls` field (0x108) can be non-null on AI cars, and intercepting them breaks all AI drivers (verified: disabling TC once crippled the whole AI field).
- Passthrough hooks (installed on methods shared by all cars/instances, e.g. proxy_shift_up/down) must stay log-free — any unconditional LOGI there floods the log (measured: 18810 lines in 21 min, fires even when the feature is off) and drowns diagnostic logs. Log only at install time or inside player-gated paths.
- Overlay Views use raw Android Canvas (not Compose) because Compose cannot overlay reliably on a Unity SurfaceView.
- miuix dialogs (`OverlayDialog`) must stay mounted in the composition tree and be driven by their `show` param — mounting under `if (visible) { ... }` skips the exit animation (dialog vanishes instantly). Follow the SupportDialog/EulaDialog pattern: flip `show=false` in `onDismissRequest`, run side effects in `onDismissFinished`.
- User-visible emoji in UI strings need the U+FE0F variation selector (`⚠️` = U+26A0 + U+FE0F); the bare codepoint renders as a monochrome text glyph on Android.
- A JSON file in external storage is used for configuration. The ConfigActivity writes; `AlaMobileModule` reads.
- Compose gesture exclusivity: a claim-from-down gesture layer must NEVER be used on full-width dense elements (slider strips) without directional adjudication — claim only when |dx|≥|dy| at touch-slop crossing, otherwise fully release (v2 claimed everything and ate all vertical scrolls starting on strips; config-page frames dropped to 1/6 of baseline). When a layer does claim, it must also drive the value: Compose slop arbitration is free-for-all — any below-slop consumption is caught by the Final-pass recheck and cancels ALL pending detectors including the child's own; there is no "block parents only" consumption layer.
- Cross-gesture direction exclusion (pager swipe vs page scroll) lives at the DELTA level (NestedScrollConnection eating the locked axis's UserInput component), not the detector level — canceled detectors revive mid-gesture via AwaitGesturePickup. Axis blocking must consume the per-axis component only (whole-vector consumption hijacks the neighbor axis's legitimate scrolling).
- On-device frame-rate measurement: `adb shell input` swipes interleave with the user's real touches and corrupt readings — screencap-verify page state before injecting and have the user hands-off during the window. Compare via same-protocol A/B (git stash → build baseline → identical battery); fewer frames ≠ slower rendering, it may mean gestures are being eaten.

## Files to Know

- `app/src/main/kotlin/tools/alamobile/mod/AlaMobileModule.kt` — LSPosed entry point.
- `app/src/main/kotlin/tools/alamobile/mod/ConfigActivity.kt` — miuix Compose settings UI.
- `app/src/main/kotlin/tools/alamobile/mod/NativeBridge.kt` — JNI declarations.
- `app/src/main/kotlin/tools/alamobile/mod/overlay/PedalOverlayView.kt` — dual-zone pedal.
- `app/src/main/kotlin/tools/alamobile/mod/overlay/TcAbsIndicatorView.kt` — TC/ABS intervention indicator (elliptical bow, RadialGradient, 16ms JNI polling; signal from native RoadForce instruction interceptor, see pedal_hook.c `abs_rf_intercept_pre`).
- `native/src/pedal_hook.c` — throttle/brake/gear hook logic + input writer thread + ABS/TC control (carController hook + per-wheel usesABS) + TC/ABS gear-level field overwrites with baseline capture/restore + TC/ABS intervention indicator signals (RoadForce 0x1A7B7DC instruction interceptor + 25Hz frame-phase clock; single-writer phase clock, frame-seq age matching — see docs/MODULE_ABS_NOTES.md §2c for the evolution and pitfalls; ⚠️ the interceptor callback must stay float-free — it replays `str s0` and any FP register use corrupts tempBrakeF, killing all ABS gears).
- `app/src/main/kotlin/tools/alamobile/mod/util/VersionGate.kt` — version gating.
- `native/src/ala_core.c` — native entry points and ShadowHook init.
- `native/src/drs_hook.c` — auto DRS / active aero hook logic.
- `native/src/unlock_hook.c` — billing/unlock IL2CPP hook logic.
- `native/src/music_hook.c` — main menu music mute + heartbeat signal.
- `native/src/intro_hook.c` — intro V10 engine sound: mute introSound + one-shot signal.
- `native/src/hide_pedals_hook.c` / `native/src/hide_pedals_hook.h` — hide game-native throttle/brake buttons via IRDSUIMobileControls + il2cpp_runtime_invoke(SetActive); on disable, restores hidden buttons via a stash + re-traverse pointer match (never dereferences stashed pointers).
- `native/src/lap_hook.c` / `native/src/lap_hook.h` — time-trial valid-lap listener + paddock upload source: hooks `odometerHandler.HandleSectorsTimes` (lap events) + `IRDSLevelLoadVariables.Awake` (session reset); valid-lap from game's own `validLap` bit; session gate via `champManager.isTimeAttack` (NULL = suspend); track ID via `LAPscene` probe (SceneManagerHelper + GetGPIndex); valid laps exposed to Java via single-slot upload buffer (`lap_poll_upload`/`lap_mark_upload_consumed`). ⚠️ lap completion must fire on the `order==2` event, never on the 2→0 wrap (delayed a full S1). See `docs/LAP_HOOK_NOTES.md`.
- `app/src/main/kotlin/tools/alamobile/mod/PaddockClient.kt` / `PaddockUploader.kt` — paddock (围场) leader-board client: HTTPS lap upload (HttpURLConnection, no new deps), 30-day local pending queue, login/register/reset API, server Toast mapping; `PaddockUploader` polls native slot at 1Hz from AlaMobileModule's 15s delay path, server address = ModConfig `paddock_server` override (empty → built-in `https://paddock.takotsubo.cloud`). Track display names are a **shared contract** with the paddock repo (see below). ⚠️ **登录 token 必须走 Remote Preferences daemon**（`paddock_token_v1`，与 config_json 同通道）：两进程 `getExternalFilesDir` 是按包名隔离的目录互不可见，本地 auth 文件只是缓存——`saveAuth` 双写、`loadAuth` 经 `remoteTokenReader`（游戏进程注入）恢复、`clearAuth` 双清。⚠️ **两进程共用代码只准用 `Logger`**，`AlaMobileModule.logX` 只准在游戏进程路径用——libxposed-api 是 compileOnly，模块进程没有 Xposed 类，引用即 NoClassDefFoundError（会被外层 catch 捕获伪装成"网络错误"）。
- `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/` — Paddock UI page (4th bottom-bar page): 未登录=通行证核验（用户名+密码共用表单，注册/登录并排，注册走"申请即设密+弹窗复制指令"流，无 verify 步骤）；已登录=个人信息卡（头像+车手 #ID）+ `LeaderboardScreen`（积分/赛道榜，行=排名+圆形头像+用户名+右对齐圈速连排）+ 头像上传（`Route.Avatar`，canhub cropper 1:1 圆形裁剪；`CropImageActivity` 需专用 AppCompat 主题且**必须带 ActionBar**——确认按钮走 options menu）。操作提示全走 Toast 不用页面卡片。Settings 页的围场服务器地址编辑用 OverlayDialog 常驻组合树模式（`paddockServerDialogVisible` + `onDismissFinished` 副作用）。
- `native/src/native_log.h` / `native/src/native_log.c` — shared native file logging (logcat + file, logEnabled-gated).
- `app/src/main/kotlin/tools/alamobile/mod/util/Logger.kt` — unified Java logger (logcat + file, logEnabled-gated).
- `app/src/main/kotlin/tools/alamobile/mod/util/LogExporter.kt` — merge module+game logs, FileProvider → ShareSheet.
- `app/src/main/kotlin/tools/alamobile/mod/config/LogReceiver.kt` — static receiver for game→module log push via setComponent broadcast.
- `app/src/main/kotlin/tools/alamobile/mod/MusicPlayer.kt` — main menu music replacement player.
- `app/src/main/kotlin/tools/alamobile/mod/IntroSoundPlayer.kt` — V10 engine sound player.
- `app/src/main/resources/META-INF/xposed/module.prop` — libxposed module metadata.
- `app/src/main/resources/META-INF/xposed/scope.list` — target package list.
- `docs/TECHNICAL_ANALYSIS.md` — Ala Mobile game-engine reverse-engineering analysis, organized by subsystem (paper-style, LaTeX + Mermaid). Completed: ABS, vehicle dynamics (TC/ESC/steer assist), lap timing & track ID. Planned: aero/DRS, drivetrain.
- `docs/MODULE_ABS_NOTES.md` — engineering notes for the ABS and TC subsystems: module hook layers, tunable field paths, native-scan toolchain pitfalls, upgrade verification checklist.
- `docs/ABS_LEVEL_DESIGN.md` — ABS gear-level tuning design & finalized calibration (v2: intervention-strength b override + max brake pressure T_b scaling), same skeleton as TC_LEVEL_DESIGN.md.
- `docs/TRACK_IDENTIFICATION.md` / `docs/LAP_HOOK_NOTES.md` — 16 GP track table (buildIndex 2–17, scene names verbatim; `trackToRace`='MobileScene' dead sign) + lap_hook engineering notes (HandleSectorsTimes semantics, session-gate matrix, **9-session mode-signal matrix §4a**, upgrade checklist).

## TODO(human) Integration Points

Two areas are explicitly designated for human contribution during implementation:

1. `PedalOverlayView.updateValues(y: Float)` — implemented with deadzone, configurable transition point, and linear/quadratic/exponential curves. Fine-tune defaults and curve exponents based on real-device feel.
2. `native/src/drs_hook.c` — if telemetry polling is used, read `inDRSZone`, `throttle`, `steeringAngle`, `speed` from IL2CPP instance fields and evaluate DRS eligibility.

## Notes for Future Changes

- Do not commit the APK or any IL2CPP dump larger than GitHub's file size limit. Large files are excluded via `.gitignore`.
- miuix is a Kotlin Multiplatform library; keep Compose code in `ConfigActivity` and do not use it for runtime overlays.
- Before adding new native hooks, regenerate the IL2CPP dump and update `offsets_sheet.csv`/`OffsetTable.kt`.
- Coexistence APK build: use the `coex-apk-builder` skill (`.claude/skills/coex-apk-builder/SKILL.md`). Two paths maintained: LSPosed (root) and NPatch local mode (non-root, majority users). NPatch flow: Claude provides coex APK → Takotsubo injects via NPatch → self-signs with fixed keystore → distributes to end users.
