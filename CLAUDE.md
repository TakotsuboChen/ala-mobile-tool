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

Supported game version: **Ala Mobile 8.0.6 (versionCode 200150)**（2026-09-04 起弃 8.0.4 支撑；共存版定制：应用名 "Ala Mobile Pro"、图标 #FF8000 橙）. IL2CPP method offsets are version-specific; the module gates all native hooks behind `VersionGate`.

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
├── Logger / LogExporter      # Unified logging (unconditional file output) + ShareSheet export
├── LogReceiver               # Static receiver for game→module log push (setComponent broadcast)
├── CrashCatcher / crash_hook.c # 崩溃自捕：模块进程 Java 未捕获异常 + 游戏进程 native 信号（SIGSEGV 等，PC 相对模块偏移落盘）
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

> ⚠️ 本机（2026-09-09）`assembleRelease` 会因 `lintVitalAnalyzeRelease` Kotlin FIR 崩溃失败（AboutScreen.kt 内部错误，干净基线复现 = 环境问题非代码问题）：加 `-x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` 跳过；`:app:lint` 正常，CI 不受影响。

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

- `安装包/` — 各版本成品安装包（如 `Ala Mobile 8.0.6 Takotsubo 共存版.apk`）
- `build/v<版本>-official/base.apk` + `build/v<版本>-official-native/split_config.arm64_v8a.apk` — 官方版分包，解 `lib/arm64-v8a/libil2cpp.so` 用后者
- `build/` 下还有各历史版本的 base.apk / split_config.arm64_v8a.apk
- global-metadata.dat 在 base.apk 的 `assets/bin/Data/Managed/Metadata/` 内

Run Il2CppDumper (requires a local `Il2CppDumper` binary)——先从上面的项目内 APK 解出两个输入文件：
```bash
mkdir -p il2cpp-dumps/v8.0.6
Il2CppDumper <解出的 libil2cpp.so> \
             <解出的 global-metadata.dat> \
             il2cpp-dumps/v8.0.6/
```

Important output files:
- `il2cpp-dumps/v8.0.6/dump.cs` — human-readable class/method/field dump.
- `il2cpp-dumps/v8.0.6/offsets_sheet.csv` — curated table of target methods and fields used by the module. ⚠️ 字段偏移核对清单 = native 全部 `OFF_*` 常量而非本表（8.0.6 闪退教训）。

Update `OffsetTable.kt` after every IL2CPP dump.

## Key Code Conventions

- Package root: `tools.alamobile.mod`
- `AlaMobileModule` is the single `XposedModule` subclass and entry point. Register it in `src/main/resources/META-INF/xposed/java_init.list`.
- Keep the native bridge surface small. Java passes only resolved offsets and feature toggles to `libala-core.so`.
- All native IL2CPP hooks are gated by `VersionGate`: refuse to install if the game version is not exactly `8.0.6 (200150)`.
- Native hooks that intercept or override gameplay behavior (TC/ABS disable, future ESC tuning) must gate on the whitelist comparison `is_target_player_car` (`this == g_player_controller`), never on the `is_player_controller` field probe — the `playerControls` field (0x108) can be non-null on AI cars, and intercepting them breaks all AI drivers (verified: disabling TC once crippled the whole AI field).
- Passthrough hooks (installed on methods shared by all cars/instances, e.g. proxy_shift_up/down) must stay log-free — any unconditional LOGI there floods the log (measured: 18810 lines in 21 min, fires even when the feature is off) and drowns diagnostic logs. Log only at install time or inside player-gated paths.
- **日志红线（2026-09-07）**：所有 Java/Kotlin 日志必须经 `Logger`（双写 logcat + 文件），**禁止直用 `android.util.Log.x`**——直用的日志只进 logcat 环形缓冲不落文件，且会被 native 高频诊断（ABSdiag/TCdiag 0.5s 一组）挤出缓冲，排查时证据链被劈成两半（2026-09-07 配置仲裁排查实证：裁决日志只在 logcat 且 3 分钟后被刷掉，只能靠字节长度指纹间接定案）。新增日志一律 `Logger.i/w/e(TAG, msg[, throwable])`（带 tag 重载已就绪，签名对齐 android.util.Log）；全仓直用点已于 2026-09-07 收编清零（15 文件 148 处），lint 前可 grep `(^\|[^A-Za-z_.])Log\.(i\|w\|e\|d\|v)\(` 自查，命中即违规。`Log.INFO` 等常量与 `Log.getStackTraceString` 仍可直接用。
- Overlay Views use raw Android Canvas (not Compose) because Compose cannot overlay reliably on a Unity SurfaceView. Overlay 是注入进游戏 Activity decorView 的 View（非独立悬浮窗），其绘制节奏直接进入游戏窗口的刷新率仲裁输入：**触摸驱动的 invalidate 必须合并到 vsync 帧边界**（`postOnAnimation` + pending 标记，见 PedalOverlayView.scheduleDraw），且触摸热路径零分配（复用缓冲、裸比较替代 `in 0f..X` 装箱）。2026-09-09 定案：iQOO 12/OriginOS 4 的 LTPO 逐帧刷新率重估会被事件率驱动的 invalidate 扰动（刷新率 120/72/60/51/45/30 乱跳→掉帧/锁30），scheduleDraw 也压不住——用户侧解法=把游戏加进游戏魔盒（白名单即锁刷新率，与具体设置无关）。
- miuix dialogs (`OverlayDialog`) must stay mounted in the composition tree and be driven by their `show` param — mounting under `if (visible) { ... }` skips the exit animation (dialog vanishes instantly). Follow the SupportDialog/EulaDialog pattern: **two variables** — `mounted` gates the composition-tree mount, `show` drives the param; close = flip `show=false` (plays exit animation), and `onDismissFinished` flips `mounted=false` to unmount. A single variable serving both roles skips the exit animation. Dialogs also follow the layout rule: **title centered + bold, body left-aligned** (never center body text).
- **跨进程配置仲裁（2026-09-08）**：`ModConfig.readFromTargetProcess` 不再按固定优先级（remote→Provider→local）——三源全取比 `saved_at`（epoch millis）**最新者胜出**。固定优先级已被实机证伪：NPatch 下 remote prefs 返回陈旧快照（955 字节旧 JSON）会一票否决新鲜的本地文件，用户"关 ABS 启动游戏读到开"。写配置一律带 `KEY_SAVED_AT`；旧 JSON 无此字段 = 0 最旧自然淘汰。**写入侧永远只有一个权威方（ConfigActivity），时间戳才可靠**——多写方场景此法失效。
- User-visible emoji in UI strings need the U+FE0F variation selector (`⚠️` = U+26A0 + U+FE0F); the bare codepoint renders as a monochrome text glyph on Android.
- A JSON file in external storage is used for configuration. The ConfigActivity writes; `AlaMobileModule` reads.
- Compose gesture exclusivity: a claim-from-down gesture layer must NEVER be used on full-width dense elements (slider strips) without directional adjudication — claim only when |dx|≥|dy| at touch-slop crossing, otherwise fully release (v2 claimed everything and ate all vertical scrolls starting on strips; config-page frames dropped to 1/6 of baseline). When a layer does claim, it must also drive the value: Compose slop arbitration is free-for-all — any below-slop consumption is caught by the Final-pass recheck and cancels ALL pending detectors including the child's own; there is no "block parents only" consumption layer.
- Cross-gesture direction exclusion (pager swipe vs page scroll) lives at the DELTA level (NestedScrollConnection eating the locked axis's UserInput component), not the detector level — canceled detectors revive mid-gesture via AwaitGesturePickup. Axis blocking must consume the per-axis component only (whole-vector consumption hijacks the neighbor axis's legitimate scrolling).
- On-device frame-rate measurement: `adb shell input` swipes interleave with the user's real touches and corrupt readings — screencap-verify page state before injecting and have the user hands-off during the window. Compare via same-protocol A/B (git stash → build baseline → identical battery); fewer frames ≠ slower rendering, it may mean gestures are being eaten.
- **主屏 Pager 焦点闪跳坑（2026-09-10 定案）**：`MainPagerState.springAnimateToPage` 结尾必须**无条件 `scrollToPage(target)` 精确归位**（KernelSU 原版的"误差<1px 就跳过归位"优化在锁页 `userScrollEnabled=false` 下让亚像素残差常驻——此后 TextField 聚焦/IME 弹出的 bringIntoView 请求经 `PagerBringIntoViewSpec` 判定"不在页边界"，算出整页吸附距离 → pager 无手势凭空跳邻页且滑不回（跳页时刻无触摸事件，`show(ime)` 与 `targetPage` 跳变仅差 56ms，日志实锤）。跳到哪取决于残差方向与表单落点，表现为"乱窜"。每次程序化导航后精确归位 → 残差物理性消失。诊断法：`settledPage`/`targetPage` Logger 埋点 + logcat 对照触摸时间线。

## Files to Know

- `app/src/main/kotlin/tools/alamobile/mod/AlaMobileModule.kt` — LSPosed entry point.
- `app/src/main/kotlin/tools/alamobile/mod/update/ForceUpdateGate.kt` — 启动门控（2026-09-10 扩展）：判定红线 = **确认游戏/模块版本之前完全零 Hook**。四态：游戏版本不匹配（本地同步判定，`isSupportedVersion`，无 fail-open——错版本装 hook = 开屏闪退实证）/ 模块落后最新 Release（缓存秒判+后台检查，离线 fail-open）/ 双失配 / **未登录围场**（ConfigProvider 权威判定，fail-closed，更新失配优先于登录）；激活后循环 Toast（文案四态动态取）。⚠️ scope 里有非游戏包（GMS/WebView）——`evaluate` 入口必须按包名放行非游戏进程，否则误激活会在 GMS 进程弹 Toast（实机踩过）。`isVerdictDone` 语义 = 游戏版本已确认 **且** 模块判定出结果；context 为 null 时 verdict 不放行，hook 路径 200ms 主线程轮询重试（绝不阻塞）。`isLoginVerdictDone` 是 early unlock 路径的额外守门（登录判定走阻塞 IPC，在途时 verdictDone 拉回 false）；⚠️ `evaluateLoginGate` 禁止用 `loginVerdictDone` 初始值 true 做 early return——那会让查询永不发起、门控永不激活（短路教训实测）。登录门控 UI 侧 = `LoginGateCoordinator`（弹窗优先级最低，EULA/更新/激活弹窗埋点汇报）+ MainScreen `gateLocked` 状态机（**点「去登录」之后才锁页**，弹窗期间可自由浏览）。
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
- `native/src/crash_hook.c` / `native/src/crash_hook.h` — 游戏进程 native 崩溃自捕（信号级）：SIGSEGV 等六信号 handler 落盘 `ala_tool_crash_native.log`（PC/LR 相对模块偏移 + 寄存器现场，对拍 OffsetTable 定位元凶 hook）；`sigaltstack`+`SA_ONSTACK` 覆盖栈溢出型；handler 内全 async-signal-safe、链式转发旧 handler；由 `NativeBridge.init` 安装（hook 装之前，尽早覆盖场景加载窗口）。落盘文件经 `pushGameLogs`→`LogReceiver`（"nativecrash" 通道）→`LogExporter`「游戏进程崩溃记录」段导出（不走 24h 过滤）。模块进程 Java 侧对应 `util/CrashCatcher.kt`（未捕获异常 → `ala_tool_crash.log`）。
- `app/src/main/kotlin/tools/alamobile/mod/PaddockClient.kt` / `PaddockUploader.kt` — paddock (围场) leader-board client: HTTPS lap upload (HttpURLConnection, no new deps), 30-day local pending queue, login/register/reset API, `fetchMe`（登录态恢复+计时赛总积分）, server Toast mapping; `PaddockUploader` polls native slot at 1Hz from AlaMobileModule's 15s delay path, server address = ModConfig `paddock_server` override (empty → built-in `https://paddock.takotsubo.cloud`). Track display names are a **shared contract** with the paddock repo (see below). ⚠️ **登录 token 跨进程通道是三级回落**（loadAuth 顺序）：① 本地 auth 文件（`getExternalFilesDir` 下，`saveAuth` 双写双清）→ ② Remote Preferences daemon（`paddock_token_v1`，游戏进程经 `remoteTokenReader` 读）→ ③ **ConfigProvider `read_token`**（游戏进程 call 模块进程，读同一 auth 文件）。⚠️ **NPatch 本地模式下 ② 是空壳**：loader 的 `requestRemotePreferences` 返回 `Bundle.EMPTY`，模块 App 经管理器写入的 store 与游戏进程本地 fallback store 是两个物理文件——LSPosed 下 ② 权威，NPatch 下 ③ 才是唯一可用通道（用户日志实证 41 次重试全 null）。⚠️ ConfigProvider 读 auth 文件必须与 `saveAuth` 的 `getDir()` 同用 `getExternalFilesDir`，读 `filesDir` 永远 null。⚠️ **登录判定的权威通道是 `queryLoginStateFromModule()`（ConfigProvider read_token）**，不用 daemon remote prefs——两框架（lspd + NPatch 管理器）的 `remove()` 都不生效（日志成功、DB 不变），清 token 必须 `putString("")` 空串覆盖；写/清必须遍历 `App.allServices`（LSPosed 与 NPatch 是两个物理 store，单 binder 覆盖模式会残留 → "退出登录后游戏仍放行+上传 401"）。⚠️ **新安装强制清一次登录态**（`wipeAuthIfFreshInstall`）：内部 prefs 标记 `auth_wipe_done_v1`（卸载随删）+ external auth 文件（跨卸载存活）存活期错开构成 fresh-install 指纹，标记缺失 = 卸载重装 → 无条件全渠道清；升级覆盖安装标记在 → 不清。⚠️ `PaddockUploader` 无 token 时 60s 限流重试恢复 + 进围场页自动 drain 队列——"先跑圈后登录"的圈靠这两条补传。⚠️ **鉴权 GET 必须显式传 token**（`getJson(url, token)`——参数默认 null，漏传不会编译报错，只会 401 触发 needRelogin 自动登出，第一次集成 `/v1/me` 就踩过）。⚠️ **两进程共用代码只准用 `Logger`**，`AlaMobileModule.logX` 只准在游戏进程路径用——libxposed-api 是 compileOnly，模块进程没有 Xposed 类，引用即 NoClassDefFoundError（会被外层 catch 捕获伪装成"网络错误"）。⚠️ **头像三级缓存**（fetchAvatar：内存 LruCache→磁盘 cacheDir/paddock_avatars→网络）：失效靠服务端版本化 URL（`?v=avatar_version`，上传即变，URL 同=内容同），**磁盘必须存降采样 JPEG 而非原图字节**——原图最大 ~1MB，5MB 上限在满编榜单下必触发 trim 自删 → 重启全量重下死循环（实机日志实证）。围场页进页自动刷新（isCurrentPage+导航栈归位为键）与下拉刷新共用 `PaddockViewModel.refresh(onDone)`。
- `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/` — Paddock UI page (3rd bottom-bar page, MainScreen `when(page)` 序号 2；页序=概览0/配置1/围场2/设置3): 未登录=通行证核验（用户名+密码共用表单，注册/登录并排，注册走"申请即设密+弹窗复制指令"流，无 verify 步骤，格式校验走本地 Toast 规则逐字复刻服务端 auth.rs）；**未登录不挂 PullToRefresh**（核验页内容不满一屏，LazyColumn 消费不掉下拉位移，全量直达 PullToRefresh 会凭空拉出指示器——列表 composable 提为共用 lambda，仅 loggedIn 分支包 PullToRefresh）；注册弹窗正文为防呆文案（勿改指令文字/勿当入群答案），蓝色按钮=复制指令+openQqGroup 跳群（群号/兜底 URL 常量在 `util/UrlUtils.kt` 与概览页 QQ 群入口共用单源）；已登录=用户信息连体卡（首行头像+粗体用户名，下两行三列统计含计时赛积分 `/v1/me`）+ 计时赛排行榜/大奖赛/娱乐匹配入口 + 页底独立退出登录卡（OverlayDialog 确认）。`LeaderboardScreen`（积分/赛道榜，行=排名(前三🥇🥉居中放大50%)+圆形头像+用户名+右对齐圈速连排，水平 16dp 对齐 miuix 标准）：**榜单行必须是 LazyColumn 的 items 而非 Card 内 forEach**（205 行全量组合会在数据帧撞上转场/过渡动画掉帧；连体卡视觉=每行 surfaceContainer 底色+首末行 16dp 圆角拼接+尾部 12dp spacer item）；**筛选卡钉在 PullToRefresh 手势区之外、刷新指示器出现在筛选卡下方**（指示器永远出现在 PullToRefresh 内容最上方——筛选卡若还是列表第一项就随内容下移，指示器只能落在标题下；LazyColumn 加视口顶缘 `clip(topStart/topEnd 16dp)` 防滚动时行被平直截断缺角）；**切换两阶段时序**：旧行显式 `Animatable` alpha 渐隐（立即启动，与筛选卡伸缩并行）→ 完成后换 `visibleBoard` 数据源（渲染数据≠筛选状态）→ 新行 `Modifier.animateItem` 从 0 淡入——animateItem 的移除淡出在换源帧才启动，撞上布局动画结束点就是"闪一下"；头像解码必须 `inSampleSize` 降采样（512px 全尺寸位图×205 张=GC 压力）；页面进入转场 500ms（miuix NavDriver PROGRAMMATIC_DURATION_MILLIS 契约值）结束后才渲染行，数据请求与转场并行不串行。二级页 `navigationIcon` 必须显式 `clickable { navigator.pop() }`（Icon 本身无点击事件）。头像上传（`Route.Avatar`，canhub cropper 1:1 圆形裁剪；`CropImageActivity` 需专用 AppCompat 主题且**必须带 ActionBar**——确认按钮走 options menu）。操作提示全走 Toast 不用页面卡片。Settings 页围场服务器=OverlaySpinnerPreference 选项菜单（CAMDA（默认）/自定义+内联输入框+显式保存按钮）。
- `native/src/native_log.h` / `native/src/native_log.c` — shared native file logging (logcat + file, unconditional).
- `app/src/main/kotlin/tools/alamobile/mod/util/Logger.kt` — unified Java logger (logcat + file, unconditional).
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
- **所有 IL2CPP/Unity 方法 RVA 的单一事实源是 `OffsetTable.kt`**：native 侧（`native/src/*.c`）禁止硬编码 `#define RVA_*` 或裸 RVA 常量——一律由 Java 侧从 OffsetTable 取值、经 JNI 参数注入（既有模式：`NativeBridge.init(...)` 的 offset 参数 / `hide_pedals_set_offsets`）。`unlock_hook.c` 里仅存的 3 个 fallback 数值是 `g_config` 异常时的应急兜底，注释必须标明对应版本，升版时同步更新。升版流程 = 跑 Il2CppDumper → 只改 `OffsetTable.kt`（+ offsets_sheet.csv）→ native 目录零 RVA 改动。
- **游戏升版时，字段偏移核对清单 = native 里全部 `#define OFF_*` 常量，不是 offsets_sheet.csv**：sheet 只记录方法 RVA + 部分字段，lap_hook/pedal_hook 里还有一批 8.0.0 时代硬编码的中间跳转字段（如 odometerHandler.stGUI→stadistics 链），漏核一个就是跑圈中途 SIGSEGV（8.0.6 实证：odometerHandler 头部插入 centralMessagesContainer 致其后字段全 +8，stGUI 0xF8→0x100）。核对方法：对每个 OFF_ 常量在**新旧两版 dump.cs** 里找 `字段; // 0xXX` 注释逐一对拍，逐类全量 diff 不抽样。
- Coexistence APK build: use the `coex-apk-builder` skill (`.claude/skills/coex-apk-builder/SKILL.md`). Two paths maintained: LSPosed (root) and NPatch local mode (non-root, majority users). NPatch flow: Claude provides coex APK → Takotsubo injects via NPatch → self-signs with fixed keystore → distributes to end users.
