# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T22:55:00+08:00 · Git HEAD: `daa45c1`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `daa45c1` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `daa45c1`；变了说明快照可能过期。
- 工作区: 干净（LsposedStatus 修复 + README 已提交）。
- 先读: `CLAUDE.md` + `README.md` + 本文件。

## 1. 当前目标
**LSPosed 激活判定已根治**（本会话完成，双 bug 修复）。待办：① 调查 NPatch 注入时序依赖（用户报告的"必须先开一次 NPatch 才生效"）；② 发 Beta 3（versionCode `100230`）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **激活判定改为 `frameworkName`**——commit `8d79ee1`（`fix: LSPosed 激活判定改 frameworkName`）。参照 AdClose `ServiceManager.onServiceBind` 思路：LSPosed daemon 只在模块启用时推 binder，`App.xposedService != null && frameworkName == "LSPosed"` 即激活。
- [V] **根治 scope 残留误判**——`getScope()` 在 NPatch 下返回记忆的 scope（非空）→ 误判 LSPOSED（"清数据+关开关+第一次打开"场景）。已弃用。
- [V] **根治 running targets 空误判**——`getRunningTargets()` 返回**当前正在注入**的目标，游戏没跑时为空 → 把"开关开但游戏没跑"误判成未激活。已弃用。
- [V] **Non-root 标记只存 filesDir**——`confirmNonRoot`/`readNonRootConfirmed`/`clearNonRootConfirmed` 全部改本地 filesDir，不再写 remote prefs。remote 标记存 NPatch 管理器进程，`pm clear` 清不掉 → "清数据后仍显示 Non-root 已激活"（用户实测 bug）。
- [V] **构建全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`，exit 0（`daa45c1`，UP-TO-DATE）。
- [V] **README 已同步**——commit `daa45c1`（`docs: 更新 README 激活检测描述`）。架构图 LsposedStatus 描述同步更新。

### 构建输出（本次交接 run）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 1s (exit 0, UP-TO-DATE)
```

## 3. 决策与理由
- **激活判定用 `frameworkName` 而非 scope/apiVersion/runningTargets** [V]——`frameworkName` 是框架直接上报的标识：LSPosed 返回 `"LSPosed"`，NPatch 返回 `"NPatch"`（`XposedServiceBinder.kt`）。`getScope()` 误判残留、`getRunningTargets()` 误判空、`apiVersion` 靠版本号猜框架不可靠（NPatch 未来可能升 102）。参照 AdClose 的 `onServiceBind` 即激活思路，但区分 NPatch binder 走弹窗。
- **Non-root 确认标记只存 filesDir** [V]——与 EulaManager 的 EULA 标记策略一致：`pm clear` 可清，不留跨进程僵尸标记。remote prefs 写别的进程（NPatch 管理器）`pm clear` 清不掉。
- **保留 `hasModuleLoadedFlag()` 优先级最高** [V]——游戏进程被注入（`onModuleLoaded` 执行）是 LSPOSED 最可靠信号，ConfigActivity 进程才需要走 `frameworkName` 判定。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M14/M23/M24/M25/M26/M27/M28 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile`、模块进程写公共 `/sdcard/`（EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式、只给 setter 加 `is_player` 条件（AI 车仍误控）、`kotlin.daemon.enabled=false`（KGP 2.4.0 无视仍卡死）、EULA 存 remote prefs（pm clear 清不掉）、EulaDialog 在 Scaffold 外渲染（灰屏）、只用 `System.getProperty(MODULE_LOADED_FLAG)` 判激活（ConfigActivity 永远 false）——均不再试。
- **（本会话）`getScope()` 判激活** [V]——NPatch 的 `XposedServiceBinder.getScope()` 返回 `ConfigManager.getAppsForModule(packageName)`（记忆的 scope，非空），关开关不清 → "清数据+关开关+第一次打开"误判 LSPOSED。改用 frameworkName。
- **（本会话）`getRunningTargets()` 判激活** [V]——API 102 新增方法，NPatch 下抛 `Requires Xposed service API 102`，catch 兜底 true → 必误判。修好 NPatch 后，LSPosed 下游戏没跑时返回空数组 → "开关开但游戏没跑"误判未激活。改用 frameworkName。
- **（本会话）Non-root 标记写 remote prefs** [V]——remote 标记存 NPatch 管理器进程的 SharedPreferences，`pm clear`（清模块数据）清不掉 → 清数据后打开 NPatch 再开模块，仍显示 Non-root 已激活（用户实测）。改只存 filesDir。
- **（本会话）靠 subagent 摘要写文档** → 事实性错误 [V]（M28 已有记录，写 README 必须逐文件读源码）。

## 5. 已知坑
- **⚠️ NPatch 注入时序依赖** [V]——用户实测：清模块+游戏数据 + 保持 NPatch 不打开 → 开模块再开游戏，模块**不生效**，反复杀游戏重启都不生效；**一旦点开 NPatch 一次**，模块就生效，之后杀掉 NPatch 也一直生效。疑似 NPatch 管理器第一次启动时才注册模块/写入注入配置（见第 6 节待调查）。
- **⚠️ LSPosed 共存版双 ClassLoader** [V]——`System.setProperty(NATIVE_INSTALLED_FLAG)` 进程级标记避免双注入，标记在 native 装好后立。
- **⚠️ `OverlayDialog` 必须在 miuix Scaffold `popupHost` 槽位内渲染** [V]。
- **⚠️ EULA 只存 filesDir** [V]——remote prefs 清不掉；**改 `EULA_SECTIONS` 必须递增 `EulaManager.EULA_VERSION`**（当前 = 2）。
- **⚠️ miuix-blur minSdk=33 而项目 minSdk=26** [V]；**⚠️ `MiuixScrollBehavior()` 不自动生效** [V]；**⚠️ WSL2 Kotlin 编译守护进程 RMI loopback 卡死** [V]（`gradle.properties` in-process 规避）。
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——玩家车判据走 `g_player_controller`。
- **⚠️ Release R8 重命名 res/raw 下 mp3** [V]——音乐放 `assets/`；**⚠️ 游戏进程 ClassLoader 取不到 APK 资源** [V]——走 `resolveModuleApkPath()` + ZipFile。
- **⚠️ HandleABS 是死代码** [V]（改写 `absEnable` 字段 0xC4）；**⚠️ DoGearShifting 不能整段跳过** [V]（手动换挡已禁用）；**⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）；**⚠️ versionCode Beta 3=`100230`** [V]。
- **⚠️ README 的 QQ 群链接是占位符** [?]——`qunpro/share?appKey=...` 是假的，需替换为 OverviewPage.kt 里真实的 `qun.qq.com/universal-share` 链接。
- **⚠️ `App.KEY_NONROOT_CONFIRMED` 常量已无引用** [V]——confirmNonRoot 只写 filesDir，App.kt:61 的常量可删（无害残留）。

## 6. 下一步（有序）
1. **调查 NPatch 注入时序依赖**（用户报告）：清模块+游戏数据 + 不开 NPatch → 模块不生效；点开 NPatch 一次 → 永久生效。假设：NPatch 管理器第一次启动时才向 patched 游戏 APK 注册模块/写注入配置。验证路径：`references/NPatch` 的 `patch-loader` / `meta-loader` 源码，找"管理器启动时做什么"的注册逻辑；对比 `manager` 模块的 `RemoteApiProvider` / `ConfigManager`。
2. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true`。M25/M26/M27/M28 + 本次激活判定修复都会包含进 Beta 3。
3. **补 README `[image-N]` 占位符素材**：image-1 单踏板录屏、image-2 编辑模式录屏、image-3 三页设置截图。并入 `assets/videos/` 或 `assets/screenshots/`。
4. **清理 `App.KEY_NONROOT_CONFIRMED` 残留常量**（低优先级，无害）。

## 7. 留给用户的开放问题
- NPatch 注入时序依赖根因待查（见第 6 节）——用户需确认 NPatch 管理器"第一次打开才注册"是否符合预期。
- `proxy_fixed_update` 的 `apply_inputs_to_controller` 已注释——油门迟滞需恢复时用 `g_player_controller`。当前无问题。
- miuix-blur 在老设备（Android 12 以下）自动降级纯色底——是否需要显式 `LocalEnableBlur` false 待讨论。
- 320kbps 替换曲是否够用？换曲直接替换 `assets/f1_music.mp3`。
- README 的 QQ 群链接需替换为 OverviewPage.kt 真实 `universal-share` 链接（当前 README 是占位符）。
- 「关于」Toast 读 `BuildConfig.VERSION_NAME`（Beta 2）——Beta 3 发布时自动跟随。
