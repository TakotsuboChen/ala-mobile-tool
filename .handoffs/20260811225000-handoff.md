# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T21:20:00+08:00 · Git HEAD: `2faae07`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `2faae07` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `2faae07`；变了说明快照可能过期。
- 工作区: 干净（README 已提交）。
- 先读: `CLAUDE.md` + `README.md`（已按社区标准重写）+ 本文件。

## 1. 当前目标
**README 已按社区标准完全重写并提交**（本会话完成）。待办：① 真机复核 LSPosed 激活判定误判；② 发 Beta 3（versionCode `100230`）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **README 重写已 commit + push**——`2faae07`（`docs: 完全重写 README — 按社区标准重构 + 深入代码与 commit 修正事实性错误`），`259 insertions, 85 deletions`。上一版 subagent 摘要不完整，本会话逐一读全部 Kotlin/C 源文件 + 80+ commit 后重写。
- [V] **工作区干净**——`git status --short --branch` 无未提交改动。
- [V] **构建全绿**（上一交接验证，`80ea7e8`）——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`，exit 0。本会话未改代码，无新增构建。
- README 保留了 3 处 `[image-N]` 占位符（供用户后补 GIF/截图素材）。

### 构建输出（本会话未跑构建，沿用上一交接记录）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 9s (exit 0)  [上一交接 H0]
```

## 3. 决策与理由
- **README 按移动应用类型适配** [V]——视觉优先、面向玩家（非开发者首读）、配置表格化、功能按"已实现/开发中"分组。社区标准（readme-best-practices + readme-types）判为 LSPosed 模块 = 移动应用。
- **用真实代码/commit 修正事实性错误** [V]——修正了：手动换挡已禁用（`DoGearShifting` hook 致出不了 P 房）、解锁的 preserve 模式、踏板曲线指数 0.66、ABS 死代码改写字段、ToolButton Base64 前景、vivo/Android 16 延迟初始化、配置三路同步、AI 车隔离（`g_player_controller` + 0x108）等。
- **保留 `[image-N]` 占位符** [V]——用户明确要求留占位符提示要插入什么素材。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M14/M23/M24/M25/M26/M27 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile`、模块进程写公共 `/sdcard/`（EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式、只给 setter 加 `is_player` 条件（AI 车仍误控）、`kotlin.daemon.enabled=false`（KGP 2.4.0 无视仍卡死）、EULA 存 remote prefs（pm clear 清不掉）、EulaDialog 在 Scaffold 外渲染（灰屏）、只用 `System.getProperty(MODULE_LOADED_FLAG)` 判激活（永远未激活）——均不再试。
- **（本会话）靠 subagent 摘要写 README** → 事实性错误 + 不完整 [V]——第一版基于 3 个 subagent 摘要，漏了 vivo 兼容、ToolButton、NPatch 日志直通、ABS 死代码、preserve 模式等，且把"手动换挡"误列为正常功能。已改为逐文件读源码 + 全部 commit 后重写。**写 README（或其他文档）时不可只信 subagent 摘要，须抽查源码。**

## 5. 已知坑
- **⚠️ `getScope()` 残留误判** [V]——scope 存 daemon SQLite，禁用/`pm clear` 都不清。激活判定仍是近似信号，待真机复核。
- **⚠️ `OverlayDialog` 必须在 miuix Scaffold `popupHost` 槽位内渲染** [V]。
- **⚠️ EULA 只存 filesDir** [V]——remote prefs 清不掉；**改 `EULA_SECTIONS` 必须递增 `EulaManager.EULA_VERSION`**（当前 = 2）。
- **⚠️ miuix-blur minSdk=33 而项目 minSdk=26** [V]；**⚠️ `MiuixScrollBehavior()` 不自动生效** [V]；**⚠️ WSL2 Kotlin 编译守护进程 RMI loopback 卡死** [V]（`gradle.properties` in-process 规避）。
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——玩家车判据走 `g_player_controller`。
- **⚠️ Release R8 重命名 res/raw 下 mp3** [V]——音乐放 `assets/`；**⚠️ 游戏进程 ClassLoader 取不到 APK 资源** [V]——走 `resolveModuleApkPath()` + ZipFile。
- **⚠️ HandleABS 是死代码** [V]（改写 `absEnable` 字段 0xC4）；**⚠️ DoGearShifting 不能整段跳过** [V]（手动换挡已禁用）；**⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）；**⚠️ versionCode Beta 3=`100230`** [V]。
- **⚠️ README 的 QQ 群链接是占位符** [?]——`qunpro/share?appKey=...` 是假的，需替换为 OverviewPage.kt 里真实的 `qun.qq.com/universal-share` 链接。

## 6. 下一步（有序）
1. **真机复核激活判定**：确认"清数据 + 关开关 + 第一次打开"是否仍误判已激活。若仍误判，探索 `getRunningTargets()`（API 102）；若可接受"第二次打开才正确"则标记完成。
2. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true`。M25/M26/M27 改动会包含进 Beta 3。
3. **补 README `[image-N]` 占位符素材**：image-1 单踏板录屏、image-2 编辑模式录屏、image-3 三页设置截图。并入 `assets/videos/` 或 `assets/screenshots/`。

## 7. 留给用户的开放问题
- 激活误判根治是否用 `getRunningTargets()`，还是接受 scope 残留折中——需用户实机反馈。
- `proxy_fixed_update` 的 `apply_inputs_to_controller` 已注释——油门迟滞需恢复时用 `g_player_controller`。当前无问题。
- miuix-blur 在老设备（Android 12 以下）自动降级纯色底——是否需要显式 `LocalEnableBlur` false 待讨论。
- 320kbps 替换曲是否够用？换曲直接替换 `assets/f1_music.mp3`。
- README 的 QQ 群链接需替换为 OverviewPage.kt 真实 `universal-share` 链接（当前 README 是占位符）。
- 「关于」Toast 读 `BuildConfig.VERSION_NAME`（Beta 2）——Beta 3 发布时自动跟随。