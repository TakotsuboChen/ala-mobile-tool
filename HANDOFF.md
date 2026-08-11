# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T17:20:00+08:00 · Git HEAD: `c6265f4`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `c6265f4` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `c6265f4`；变了说明快照可能过期。
- 工作区: 已 commit + push（含 `.handoffs/` 归档），新 HANDOFF.md 待提交。
- 先读: `CLAUDE.md` M26 条目 + 本文件。

## 1. 当前目标
**用户协议（EULA）强制确认弹窗**已完成并真机验证：首次安装/旧版升级未同意 → 启动弹协议（排激活弹窗前），不同意强制退出；同意状态存模块 filesDir（pm clear 可清）；激活弹窗被 EULA 门控不覆盖协议；设置页新增「用户协议」行可重弹。默认音乐替换开关改为开。

## 2. 已验证状态 — 工作实际停在哪
- [V] **构建全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`（exit 0，全新 shell 重跑）。
- [V] **APK 已安装**——`adb install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`（设备 381QYFCN22B9A）。
- [V] **EULA 存储只存 filesDir**——`pm clear` 后 `adb logcat` 见 `EulaManager.isAccepted: stored=-1 current=2 accepted=false`（连续多次清数据均正确，remote prefs 残留 bug 已根治）。
- [V] **协议弹窗顺序**——清除数据后先弹用户协议，同意后（已启用模块）直接已激活，激活弹窗不再覆盖协议（用户确认"好了"）。
- [V] **设置页「用户协议」行**——点击清同意状态 + 当场弹协议，不同意 `finish()` 退出（用户确认"OK 了"）。
- [V] **工作 commit `e6d3a75` 已 push**——`feat: 用户协议强制确认弹窗 + 默认音乐替换开关开启`。
- [V] **README commit `97fad71` 已 push**、**CLAUDE.md commit `c6265f4` 已 push**——M26 条目。
- 工作区: 仅剩新 HANDOFF.md + `.handoffs/` 归档待提交。

### 构建输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 1s / 39 actionable tasks: 2 executed, 37 up-to-date (exit 0)
```

## 3. 决策与理由
- **EULA 存储只用模块 filesDir，不用 remote prefs** [V]——remote prefs 在 LSPosed daemon SQLite（`/data/adb/lspd/`），`pm clear tools.alamobile.mod` 清不掉残留标记 → "清除数据后仍显示已接受、不弹协议"（用户实测 bug）。只存 filesDir，pm clear/卸载重装天然清掉，语义正确。EULA 是"每安装会话"本地 UI 状态，不需要跨进程同步（remote prefs 是给游戏进程读配置用的）。
- **EulaDialog 必须渲染在 miuix `Scaffold` 的 `popupHost` 槽位里** [V]——`OverlayDialog` 默认 `renderInRootScaffold=true` 依赖 Scaffold 提供的 `LocalDialogStates`/`MiuixPopupHost`。在 Activity 层（Scaffold 外）渲染 → dialog 加不进渲染列表 + dim 层铺满屏 → 灰屏无弹窗。修法：`ConfigMainScreen` 的 `Scaffold(popupHost = { eulaDialog(); MiuixPopupHost() })`，EulaDialog 先渲染保证 zIndex 高于激活弹窗。
- **激活弹窗用 `activationEnabled` 门控** [V]——`OverviewPage.ActivationCard` 加参数（由 ConfigMainScreen 的 `eulaAccepted` 传），未同意前 `LaunchedEffect` 不执行、点击不弹，同意后重组才评估激活。否决方案：靠 zIndex 叠层（两弹窗会同时出现，激活盖协议上）。
- **`ArrowRow` 去掉右侧装饰图标 + 改 `.clickable`** [V]——用户要求"左边都有了右边还加干啥"。
- **`EulaManager.clear` 从 remote+filesDir 双删改为只删 filesDir** [V]——与存储策略一致（remote 不再写）。
- **「关于」Toast 版本号改 `BuildConfig.VERSION_NAME`** [V]——原硬编码 Alpha-1 过期；自动读取当前版本（现为 Beta 2）。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M14/M23/M24/M25 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile` 读 LSPosed daemon 目录、模块进程写公共 `/sdcard/`（scoped storage EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式、只给 setter 加 `is_player` 条件（AI 车仍被误控）、`kotlin.daemon.enabled=false`（KGP 2.4.0 无视仍卡死）——均不再试。
- **（本会话）EULA 存 remote prefs** → "清除数据后仍显示已接受、不弹协议" [V]——根因 remote prefs 在 LSPosed daemon SQLite，`pm clear` 清不掉。只存 filesDir。
- **（本会话）EulaDialog 在 ConfigActivity 层（Scaffold 外）渲染** → 灰屏无弹窗 [V]——`OverlayDialog` 依赖 Scaffold 提供的 `LocalDialogStates`，外部渲染 dialog 加不进 `MiuixPopupHost` 渲染列表 + dim 层铺满屏。必须放 Scaffold `popupHost` 槽位。
- **（本会话）EulaDialog 用 `LaunchedEffect` 在 ConfigMainScreen 外触发** → 激活弹窗仍同时弹出覆盖协议 [V]——两个独立触发点各自跑。改 `activationEnabled` 门控，未同意前激活弹窗根本不触发。

## 5. 已知坑
- **⚠️ `OverlayDialog` 必须在 miuix Scaffold 的 `popupHost` 槽位内渲染** [V]——`ConfigMainScreen` 用 `Scaffold(popupHost={ eulaDialog(); MiuixPopupHost() })`；设置页协议重弹在 SettingsPage 自己的 Scaffold 里（默认 popupHost 已含 `MiuixPopupHost`，OverlayDialog 直接放 content 即可）。
- **⚠️ EULA 只存 filesDir** [V]——remote prefs 清不掉（daemon SQLite）。
- **⚠️ 改 `EULA_SECTIONS` 必须递增 `EulaManager.EULA_VERSION`** [V]——否则旧版用户升级不重弹。当前 = 2。
- **⚠️ miuix-blur minSdk=33 而项目 minSdk=26** [V]——manifest `tools:overrideLibrary` 绕过，库内 `isRenderEffectSupported()` 运行时降级安全。
- **⚠️ `BlurredBar` 的模糊层依赖对端 `layerBackdrop`** [V]——两端必须用同一个 backdrop 实例。
- **⚠️ `MiuixScrollBehavior()` 不自动生效** [V]——LazyColumn 必须挂 `.nestedScroll(scrollBehavior.nestedScrollConnection)`。
- **⚠️ miuix-blur 0.9.3 的 blurRadius 已 clamp 到 [0,150dp]** [V]。
- **⚠️ WSL2 下 Kotlin 编译守护进程 RMI loopback 卡死** [V]——`gradle.properties` `kotlin.compiler.execution.strategy=in-process` 规避。
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——玩家车判据走 `g_player_controller`（IRDSPlayerControls.Update 设置）。
- **⚠️ Release 构建 R8 重命名 res/raw 下 mp3** [V]——音乐放 `assets/`。
- **⚠️ 游戏进程 ClassLoader 取不到 APK 内资源** [V]——走 `NativeBridge.resolveModuleApkPath()` + ZipFile。
- **⚠️ HandleABS 是死代码** [V]；**⚠️ DoGearShifting 不能整段跳过** [V]；**⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）；**⚠️ versionCode Beta 3=`100230`** [V]。
- **⚠️ 共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。

## 6. 下一步（有序）
1. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true`。M25 的 UI 改动 + M26 的用户协议会包含进 Beta 3。

## 7. 留给用户的开放问题
- `proxy_fixed_update` orig 后写 `apply_inputs_to_controller` 被注释——若油门迟滞再出现需恢复（用 `g_player_controller`）。当前无问题。
- miuix-blur 毛玻璃在 Android 12 以下设备自动降级为纯色底——是否需要为老设备显式设 `LocalEnableBlur` false 可后续讨论。
- 320kbps 替换曲是否够用？换曲直接替换 `app/src/main/assets/f1_music.mp3`。
- 设置页「关于」Toast 现在读 `BuildConfig.VERSION_NAME`（Beta 2）——确认 Beta 3 发布时无需改代码，自动跟随。
