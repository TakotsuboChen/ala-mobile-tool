# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T04:15:09+08:00 · Git HEAD: 最近提交 `58da6c7`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `58da6c7` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `58da6c7`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M23 条目 + 本文件。

## 1. 当前目标
**NPatch 下游戏不运行时改配置 → 启动游戏生效** 已修复（M23 第二个任务完成）。NPatch local/embedded 模式无 daemon 无管理器，`getRemotePreferences`/`bindNpatchRemoteService` 不可用，配置只写模块 filesDir + 广播，游戏不运行时广播被系统丢弃。**根因已定位并修复。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **构建全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`（exit 0）。`./gradlew :app:lint` → `BUILD SUCCESSFUL`（exit 0）。
- [V] **APK 已 adb 安装**——`adb install -r app/build/outputs/apk/debug/app-debug.apk` → `Performing Streamed Install / Success`（设备 381QYFCN22B9A / MEIZU 20 / Android 16）。
- [V] **NPatch 配置不生效已修复（代码层面）**——修改 `app/src/main/kotlin/tools/alamobile/mod/config/ModConfig.kt` 的 `readFromTargetProcess`：新增 ConfigProvider 回退路径。**真机效果待用户确认。**
- [V] **分支 `main` 已 push 到 origin**——`git log --oneline -3` 显示 `58da6c7` (docs) → `1e45925` (feat) → `3dd009f` (旧 handoff)。
- 工作区: clean。

## 3. 决策与理由
- **复用项目已有的 ConfigProvider 作 NPatch 回退路径** [V]——`ConfigProvider`（`content://tools.alamobile.mod.config`，M11 创建、M14-B 后被 remote prefs 方案取代但仍保留在 manifest `exported=true`）读模块 filesDir。NPatch loader 绕过 Android 11+ 包可见性，游戏进程能访问模块 ContentProvider。`ModConfig.write()` 始终写模块 filesDir（不依赖 daemon/广播），ConfigProvider 总能读到最新值。否决方案：`XSharedPreferences`（libxposed API 102 禁止）、`openRemoteFile` 读模块 filesDir（读的是 LSPosed daemon 目录，非模块 filesDir）、模块进程写公共 `/sdcard/`（scoped storage EACCES）。
- **读取优先级：remote prefs → ConfigProvider → 本地 externalFilesDir → 默认值** [V]——LSPosed 下 ConfigProvider 调用抛 IllegalArgumentException/SecurityException（包可见性）被静默捕获，零回归；NPatch 下 remote prefs 返回 null，走 ConfigProvider 读取模块最新配置。
- **`withPositionDefaults` 加 `@Suppress("unused")`** [V]——重构后不再被调用，保留方法防误删。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M22/M23 前全部死路）** 只 hook Awake 预设 IsUnlocked=true 不挡 OnOwnedNone、`XSharedPreferences`（被 API 102 禁止）、`openRemoteFile` 读模块 filesDir（实际读 LSPosed daemon 目录）、模块进程写公共 `/sdcard/`、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon 返回 null）、`bindNpatchRemoteService` 用于 embedded/local 模式（无管理器 ContentProvider）——均不再试。
- **（本会话）** 无新增死路——ConfigProvider 回退路径直接生效。

## 5. 已知坑
- **⚠️ NPatch embedded/local 模式配置同步：现走 ConfigProvider 回退路径** [V]——已修复，但真机效果待确认。若用户反馈仍不生效，logcat 过滤 `AlaMobileTool` 查 `readFromTargetProcess: ConfigProvider ok`（走通）或 `ConfigProvider not reachable`（未走通，包可见性仍拦）。
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要其离合器结合/挂挡逻辑。
- **⚠️ FixedUpdate 每帧覆盖 automatic** [V]——`0x1a5de94: strb w9,[x19,#0xbc]`。
- **⚠️ 游戏每帧覆盖 tclEnable/absEnable** [V]——logcat `before=1 after=1`。
- **油门＞0 时 AI 车被误控** [?]——M18 遗留，未在本会话验证。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 用 CLAUDE.md M8 表格锚点反推** [V]——Beta 3=`100230`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **ConfigProvider.kt 现在非废弃** [V]——本会话复用为 NPatch 回退路径，不可删。manifest provider 声明必须保留（`android:exported="true"`）。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。
- **音乐替换 ClassLoader.getResourceAsStream 备选路径** [?]——真机走主路径已验证，备选未确认触发。
- **`withPositionDefaults` 现在是未使用的私有方法** [V]——加了 `@Suppress("unused")`。

## 6. 下一步（有序）
1. **真机验证 NPatch 配置同步**——用户用 NPatch local 模式：改配置 → 关游戏 → 启动 → 确认生效。logcat 过滤 `AlaMobileTool` 确认走哪条路径。
2. **确认 NPatch 下音乐替换开关是否同步生效**——走同一配置链路，应一并修复。
3. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true` + tag `v1.0.0-Beta-3`。**等 NPatch 真机验证完再发。**
4. **可选**：M18 AI 误控真机多人验证。

## 7. 留给用户的开放问题
- NPatch local 模式配置同步经 ConfigProvider 回退路径是否真机生效？（用户需实测）
- M18 AI 车误控是否已由 `proxy_player_controls_update` 天然玩家车过滤根治？需真机多人验证。
- 音乐替换的 ClassLoader.getResourceAsStream 备选路径是否触发过？（真机日志 `MusicPlayer:` 可确认）