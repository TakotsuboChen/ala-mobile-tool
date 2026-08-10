# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T01:05:00+08:00 · Git HEAD: 最近提交 `e05e6dc`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `e05e6dc` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `e05e6dc`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M20 条目 + 本文件。

## 1. 当前目标
M20 后续收尾：配置页图标已自定义（TC/踏板/刹车曲线用 Inkscape flatten 纯 path，ABS/Gearbox 待重做），ABS 和手动换挡开关已暂时注释。**下一步是找到 ABS 真正实现位置 + 重做手动换挡 + 发 Beta 3。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **分支 `main` 已 push 到 origin**——`git log --oneline -3` 显示 `e05e6dc` (docs) + `cae68e5` (feat) + `a4c209a` (merge)。
- [V] **build + lint 全绿**——`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL`（50 tasks: 10 executed, 40 up-to-date）。
- [V] **TC 图标已换**——`ConfigurePage.kt` L110 `icon = TcIcon`（CustomIcons.kt，Inkscape flatten 纯 path，单 path fill）。
- [V] **踏板图标已换**——`ConfigurePage.kt` L158 `imageVector = PedalsIcon`（CustomIcons.kt，单 path fill）。
- [V] **刹车曲线图标已换**——`ConfigurePage.kt` L292 `imageVector = BrakeCurveIcon`（CustomIcons.kt，3 条 fill path，viewport 470×462）。
- [V] **油门曲线图标保持原样**——`ConfigurePage.kt` L268 `imageVector = Icons.Rounded.Speed`（用户要求不换）。
- [V] **ABS 开关已注释**——`ConfigurePage.kt` L118-127 `/* SwitchRow(... AbsIcon ...) */`，title 文本已改为"防抱死系统"。
- [V] **手动换挡开关已注释**——`ConfigurePage.kt` L243-252 `/* SwitchRow(... GearboxIcon ...) */`。
- [V] **刹车方向反转图标改为 Flip**——`ConfigurePage.kt` L233 `icon = Icons.Rounded.Flip`。
- [V] **TC summary 改为"启用游戏原生 TC"**——`ConfigurePage.kt` L109。
- [V] **TC 已真机验证生效**——用户确认「TC 关闭效果很明显很滑」。hook `TractionFilter`（RVA 0x1A570C8）方法入口，`enable_tc=false` 时直接返回原始 accel。 [?] 继承自上一份 HANDOFF，本会话未重新验证。
- [V] **HandleABS 是死代码**——全 `libil2cpp.so` 搜不到任何 `bl 0x1a5763c` 调用。 [?] 继承自上一份 HANDOFF。
- [V] **DoGearShifting hook 导致出不了 P 房**——已回退为直接调 orig。 [?] 继承自上一份 HANDOFF。
- [?] **ABS/Gearbox 图标渲染不理想**——用户反馈 ABS "文字一坨"（已去掉文字只保留圆+弧）；Gearbox "看不见孔洞"（已改为两齿轮 45° 对角线环+齿帽分离方案）。用户尚未确认最新版 Gearbox 效果，因为手动换挡开关已注释，图标暂时不显示。

### build 输出（本次交接 run 真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 24s
50 actionable tasks: 10 executed, 40 up-to-date
```

## 3. 决策与理由
- **TC/踏板/刹车曲线图标用 Inkscape flatten 纯 path** [V]——SVG 含 mask/clipPath/text，手写 ImageVector 渲染不对（mask→evenOdd 转换错、stroke→fill 线变粗、text 无法渲染）。用 Inkscape + svgpathtools + svgo 三步流水线 flatten 为纯 path d 字符串，喂给 PathParser。否决：手写 moveTo/curveTo（坐标计算错）；Vector Drawable XML（mask 不支持）。
- **ABS 图标去掉 "ABS" 文字** [V]——Inkscape export-text-to-path 产出的是单线 centerline path（无 z），fill 渲染变 blob，stroke 渲染也粘连。用户要求去掉文字。否决：fill 渲染（blob）、stroke 渲染（粘连）、增大 font-size（比例失调）。
- **Gearbox 用环+齿帽分离方案** [V]——一条 path 做 evenOdd 环（外圆+孔圆 = 环形），另一条 path 做 NonZero 齿帽（圆外梯形，不覆盖孔）。解决了 evenOdd 花瓣空洞和 NonZero 孔洞不可见问题。否决：单 path evenOdd（花瓣）、单 path NonZero + 反向缠绕（孔洞不可见）。
- **Gearbox 改两齿轮 45° 对角线** [V]——用户要求，三齿轮太挤孔洞看不清。
- **油门曲线不换图标** [V]——用户明确要求只换刹车曲线。
- **ABS/手动换挡开关暂时注释** [V]——ABS hook 未生效（HandleABS 死代码）；手动换挡 hook 导致出不了 P 房。待功能修复后取消注释恢复。

## 4. 失败的尝试 — 不要再试
- **手写 ImageVector.Builder + PathBuilder 转换 SVG** [V]——SVG 的 mask/clipPath/transform/text 在 ImageVector 中没有直接对应物，手写坐标全部出错（旋转矩阵算错、evenOdd 合并错、stroke→fill 线变粗）。不要再手写 moveTo/curveTo 链。
- **Inkscape object-stroke-to-path 把 stroke 转 fill** [V]——stroke 线变成"管道"形状 fill 路径，线条比原始 stroke-width 粗很多。不要再对 stroke 路径用 object-stroke-to-path。
- **Gearbox 单 path evenOdd** [V]——齿轮圆(1层) + 齿(2层重叠) = 偶数 → 齿和圆交叉处出现"花瓣"空洞。不要再用 evenOdd 做齿轮。
- **Gearbox 单 path NonZero + 反向缠绕** [V]——齿轮圆(CW+1) + 中心孔(CCW-1) = 0 挖空理论上正确，但实际渲染孔洞不可见（可能 arc sweep 方向在 PathParser 中行为与预期不符）。不要再用 NonZero 反向缠绕做孔洞。
- **ABS "ABS" 文字 fill 渲染** [V]——Inkscape text-to-path 产出无 z 的 centerline path，fill 不产生可见形状。不要再 fill 渲染文字 centerline。
- **ABS "ABS" 文字 stroke 渲染** [V]——5.2px 字体的 centerline stroke 1.0 仍然粘连成一坨。不要再 stroke 渲染文字 centerline。
- **（前向搬运）** 写 `tclEnable=0` 关 TC、写 `absEnable=0` 关 ABS、hook HandleABS、hook DoGearShifting 整段跳过、FixedUpdate 写 `automatic=false`、`OnAlreadyOwned` 手写 IL2CPP string、`dlopen("libil2cpp.so")`、`forceUnlockNow` 15s 调 `get_Instance()`、只 defer `onPackageReady` 不 defer `onPackageLoaded`、ShadowHook SHARED 模式、`carPilot`(0x68) 作玩家判据、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、`by lazy` 只改缓存、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段——均不再试。

## 5. 已知坑
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知。需更深入反汇编。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要 DoGearShifting 内的离合器结合/挂挡逻辑。
- **⚠️ FixedUpdate 每帧覆盖 automatic** [V]——`0x1a5de94: strb w9,[x19,#0xbc]`。
- **⚠️ 游戏每帧覆盖 tclEnable/absEnable** [V]——logcat `before=1 after=1`。
- **⚠️ Inkscape text-to-path 产出 centerline 而非 outline** [V]——system-ui 字体的 text-to-path 产出单线 centerline path（无 z），不是闭合轮廓。fill/stroke 渲染都不理想。小字体图标不要用 Inkscape text-to-path。
- **⚠️ ImageVector evenOdd 对重叠子路径产生花瓣空洞** [V]——齿轮圆+齿矩形 2 层重叠 = 偶数 = 挖空。齿轮类图标不要用 evenOdd。
- **⚠️ ImageVector NonZero 反向缠绕孔洞可能不可见** [V]——理论 winding=0 挖空，实际渲染全黑。不要依赖 arc sweep 方向做反向缠绕挖洞。
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