# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T00:55:00+08:00 · Git HEAD: 最近提交 `4651d3f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `4651d3f` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `4651d3f`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M21 条目 + 本文件。

## 1. 当前目标
M20 后续收尾 + M21 激活状态误判修复已完成。**下一步是找到 ABS 真正实现位置 + 重做手动换挡 + 发 Beta 3。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **分支 `main` 已 push 到 origin**——`git log --oneline -3` 显示 `4651d3f` (docs) + `29c6c50` (fix) + `3273248` (docs)。
- [V] **build + lint 全绿**——`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL`（50 tasks: 10 executed, 40 up-to-date，exit 0）。
- [V] **首次安装误判已修复**——`LsposedStatus.evaluate` 新增 `hasEnabledScope(service)` 调 `XposedService.getScope()` 检查 scope 非空才判 LSPOSED。模块未在 Manager 启用时 scope 为空 → 不判已激活。
- [V] **未激活自动弹窗已实现**——`OverviewPage.ActivationCard` 的 `LaunchedEffect(Unit)` 刷新后 `status == INACTIVE` → `showNonRootDialog = true` 自动弹窗。卡片 onClick 仍保留手动触发，描述文案不变。
- [V] **真机验证通过**——APK 安装到 `381QYFCN22B9A`，用户确认模块未启用时首次打开正确显示"未激活" + 自动弹窗。

### build 输出（本次交接 run 真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 23s
50 actionable tasks: 10 executed, 40 up-to-date
EXIT=0
```

## 3. 决策与理由
- **用 `getScope()` 而非 `xposedService != null` 判激活** [V]——LSPosed 检测到已安装 xposed 模块 APK 时，模块进程首次创建就推 daemon binder（即使 Manager 未启用），导致 `xposedService != null` 但模块实际未启用。`getScope()` 返回 Manager 里挂接的目标 App 列表，scope 非空 = 真启用。否决：仅凭 binder 存在（首次安装误判）、仅凭 Non-root 标记（LSPosed 用户不会走这条路）。
- **`getScope()` 异常时保守返回 true** [V]——避免已启用模块因 daemon 临时故障被误判为未激活（"已激活→未激活"比"未激活→已激活"更让用户困惑）。
- **轮询中 service 绑上但 scope 空 → 立即 break** [V]——scope 不会随时间变化，等再久也没用，停止轮询避免浪费 3s。
- **自动弹窗放在 `LaunchedEffect(Unit)` 而非卡片点击** [V]——用户要求未激活时启动主动弹窗，`LaunchedEffect(Unit)` 只在首次进入 composition 时执行一次，正好对应"页面打开时"。卡片 onClick 保留手动触发作为备选。

## 4. 失败的尝试 — 不要再试
- **仅凭 `App.xposedService != null` 判激活** [V]——LSPosed 首次安装就推 binder，即使 Manager 未启用。导致"第一次显示已激活、清后台再开变未激活"。不要再仅凭 binder 存在判激活。
- **（前向搬运）** 写 `tclEnable=0` 关 TC、写 `absEnable=0` 关 ABS、hook HandleABS、hook DoGearShifting 整段跳过、FixedUpdate 写 `automatic=false`、`OnAlreadyOwned` 手写 IL2CPP string、`dlopen("libil2cpp.so")`、`forceUnlockNow` 15s 调 `get_Instance()`、只 defer `onPackageReady` 不 defer `onPackageLoaded`、ShadowHook SHARED 模式、`carPilot`(0x68) 作玩家判据、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、`by lazy` 只改缓存、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段、手写 ImageVector.Builder + PathBuilder 转换 SVG、Inkscape object-stroke-to-path 把 stroke 转 fill、Gearbox 单 path evenOdd、Gearbox 单 path NonZero + 反向缠绕、ABS "ABS" 文字 fill/stroke 渲染——均不再试。

## 5. 已知坑
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知。需更深入反汇编。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要 DoGearShifting 内的离合器结合/挂挡逻辑。
- **⚠️ FixedUpdate 每帧覆盖 automatic** [V]——`0x1a5de94: strb w9,[x19,#0xbc]`。
- **⚠️ 游戏每帧覆盖 tclEnable/absEnable** [V]——logcat `before=1 after=1`。
- **⚠️ Inkscape text-to-path 产出 centerline 而非 outline** [V]——system-ui 字体的 text-to-path 产出单线 centerline path（无 z），不是闭合轮廓。fill/stroke 渲染都不理想。小字体图标不要用 Inkscape text-to-path。
- **⚠️ ImageVector evenOdd 对重叠子路径产生花瓣空洞** [V]——齿轮圆+齿矩形 2 层重叠 = 偶数 = 挖空。齿轮类图标不要用 evenOdd。
- **⚠️ ImageVector NonZero 反向缠绕孔洞可能不可见** [V]——理论 winding=0 挖空，实际渲染全黑。不要依赖 arc sweep 方向做反向缠绕挖洞。
- **LSPosed 首次安装推 binder 但不等于已启用** [V]——LSPosed 检测到已安装 xposed 模块 APK 时，模块进程首次创建推 daemon binder（即使 Manager 未启用）。必须用 `getScope()` 验证。
- **daemon binder 仅首次安装推一次** [V]——清后台后进程重启，daemon 不再重复推 binder。这解释了"第一次已激活、第二次未激活"。
- **油门＞0 时 AI 车被误控** [?]——M18 遗留，本次未单独验证。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 用 CLAUDE.md M8 表格锚点反推** [V]——Beta 2=`100220`→Beta 3=`100230`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。
- **Android 13+ registerReceiver 需 flag** [V]。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。
- **ConfigProvider.kt 已废弃** [?]——待清理。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。
- **NPatch `references/` 克隆的 Gradle 文件干扰主项目 build** [V]——已删 settings.gradle.kts + build.gradle.kts + gradle/。
- **Inkscape flatten 工具链在 /tmp 临时安装** [V]——Inkscape apt 安装持久，svgo/svgpathtools 在 /tmp（重启丢失）。如需再次 flatten SVG 需重装。

## 6. 下一步（有序）
1. **找到 ABS 真正实现位置**——HandleABS 是死代码，需反汇编研究：搜全 so 对 `absTriggered`(0xC8) 的写入点、对 `absEnable`(0xC4) 的所有 ldrb 读取点，或查 IRDSSimplePhysics.FixedUpdate 刹车段是否含 ABS。
2. **重做手动换挡关自动换挡**——DoGearShifting 不能整段跳过。需反汇编确认"自动升降挡"与"起步挂挡"的区别，可能只需禁自动升降挡部分。
3. **恢复 ABS/手动换挡开关**——找到正确实现后，取消 `ConfigurePage.kt` 中的 `/* ... */` 注释。
4. **验证 AI 车误控是否已根治**——`proxy_player_controls_update` 天然只跑玩家车，需真机多人模式验证。
5. **清理诊断日志 + HandleABS hook**——HandleABS hook 无用可删。
6. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步 + CI workflow `prerelease: true` + tag `v1.0.0-Beta-3`。
7. **可选：清理 ConfigProvider.kt**——已废弃，broadcast superseded。

## 7. 留给用户的开放问题
- ABS 真正实现位置在哪？HandleABS 是死代码，`absEnable` 字段不被内联 ABS 读取。是否在 Unity WheelCollider 引擎层（无法 IL2CPP hook）？还是在别的 IL2CPP 类里？
- 手动换挡关自动换挡：DoGearShifting 内"自动升降挡"与"起步挂挡"如何区分？能否只禁前者？
- M18 AI 车误控是否已由 `proxy_player_controls_update` 天然玩家车过滤根治？需真机多人验证。
- Gearbox 最新方案（两齿轮 45° 对角线环+齿帽分离）效果如何？手动换挡开关已注释，图标暂时不显示。恢复开关时需用户确认。
- Inkscape flatten 工具链的 svgo/svgpathtools 在 /tmp，重启后丢失。是否需要持久化安装？