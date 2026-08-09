# HANDOFF — 读全文再开始干活

生成时间: 2026-08-10T02:35:00+08:00 · Git HEAD: 最近提交 `a326486`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `feat/gamepad-native-mode` @ `a326486` (2026-08-10)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `a326486`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M20 条目 + 本文件。

## 1. 当前目标
M20（原生 TC/ABS 开关 + 手动换挡 UI 启用）部分完成。**TC 已真机验证生效；ABS 未生效（HandleABS 是死代码，需要找到真正的 ABS 实现位置）；手动换挡关自动换挡待重做（DoGearShifting hook 导致出不了 P 房已回退）。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **分支 `feat/gamepad-native-mode` 已 push 到 origin**——`git log --oneline -3` 显示 `a326486` (docs) + `7bdda0a` (feat) + `2ef89f4` (main)。
- [V] **build + lint 全绿**——`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL`（50 tasks: 10 executed, 40 up-to-date）。
- [V] **TC 已真机验证生效**——用户确认「TC 关闭效果很明显很滑」。hook `TractionFilter`（RVA 0x1A570C8）方法入口，`enable_tc=false` 时直接返回原始 accel 不削减。反汇编确认 `TractionFilter` 被 `carController` 在 `0x1a56a6c: bl 0x1a570c8` 调用。
- [V] **HandleABS 是死代码**——全 `libil2cpp.so` 搜不到任何 `bl 0x1a5763c` 调用。`HandleABS`（RVA 0x1A5763C）作为方法存在但从未被执行。hook 它的入口没用；在 FixedUpdate/PlayerControls.Update 写 `absEnable=0` 也没用（内联的 ABS 逻辑不读 `absEnable` 做门控，或者 ABS 根本不在这个类里）。用户确认「高速重刹都不滑」——ABS 关不掉。
- [V] **DoGearShifting hook 导致出不了 P 房**——设 `overrideClutchManagement(0x15C)=1`+`automatic(0xBC)=1` 让 `DoGearShifting`（RVA 0x1A5E734）开头 return，但游戏起步需要 DoGearShifting 里的离合器结合/挂挡逻辑，整段跳过后车在 1 挡但离合器没结合，轰油门原地蠕动。已回退为直接调 orig。用户确认回退后能出 P 房。
- [V] **TC/ABS 配置实时同步链路已建**——`ConfigReceiver` 收广播后调 `NativeBridge.setTcAbs()` → `pedal_set_tc_abs()` 更新 `g_config.enable_tc/enable_abs`。但初期日志显示 `module enable_tc=1` 不更新（15s 延迟路径只设一次），加 `setTcAbs` 后修好。
- [V] **TC/ABS 诊断日志确认游戏默认值**——logcat: `tclEnable before=1 after=1 | absEnable before=1 after=1 | TCLSlip=0.400000 ABSSlip=0.503000`。游戏非手柄模式下 TC/ABS 默认开启且强度非零。
- [?] **AI 车误控（M18 遗留）**——`proxy_player_controls_update` 天然只跑玩家车（IRDSPlayerControls 只挂玩家车），理论上应根治 M18 AI 误控，但未单独验证。

### build 输出（本次交接 run 真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 32s
50 actionable tasks: 10 executed, 40 up-to-date
```

## 3. 决策与理由
- **TC: hook TractionFilter 方法入口** [V]——关闭时直接返回原始 accel，不调 orig。比写字段可靠：游戏每帧覆盖 `tclEnable`，但方法直接跳过不受影响。反汇编确认 `carController` 在 `0x1a56a6c` 调 `bl 0x1a570c8`（TractionFilter），hook 有效。否决：写 `tclEnable=0`（游戏每帧覆盖回去，`before=1 after=1`）。
- **ABS: hook HandleABS 方法入口** [X]——HandleABS 是死代码，全 so 无调用。否决：写 `absEnable=0`（内联 ABS 不读这个字段，或 ABS 不在此类）。
- **手动换挡: hook DoGearShifting 设 overrideClutchManagement+automatic** [X]——导致出不了 P 房，已回退。否决：在 `proxy_drivetrain_fixed_update` 写 `automatic=false`（FixedUpdate 每帧开头用设置值覆盖 `automatic`，`0x1a5de94: strb w9,[x19,#0xbc]`）。
- **TC/ABS 开关语义: 开=保持默认，关=强制关闭** [V]——游戏默认开 TC/ABS（`tclEnable=1/absEnable=1`），用户要的是"关掉变得更专业"。`enable_tc=false` 时才干预，`true` 时不写。

## 4. 失败的尝试 — 不要再试
- **写 `tclEnable=0` 关 TC** [V]——游戏 `PlayerControls.Update` 内部每帧设回 true（logcat `before=1 after=1`），orig 前写、orig 后写都被覆盖。不要再试写字段方式关 TC。
- **写 `absEnable=0` 关 ABS** [V]——HandleABS 是死代码，`absEnable` 字段不被任何 ABS 逻辑读取做门控。不要再试写字段方式关 ABS。
- **hook HandleABS 方法入口关 ABS** [V]——HandleABS（RVA 0x1A5763C）全 so 无调用（`bl 0x1a5763c` 搜索为空），是死代码。不要再 hook 这个方法。
- **hook DoGearShifting 设 overrideClutchManagement+automatic=1** [V]——DoGearShifting 开头 return 导致起步挂挡也被跳过，车出不了 P 房（1 挡蠕动、轰油门不动）。不要再整段跳过 DoGearShifting。
- **在 `proxy_drivetrain_fixed_update` 写 `automatic=false`** [V]——`FixedUpdate` 每帧开头 `0x1a5de94: strb w9,[x19,#0xbc]` 用设置值覆盖 automatic，写 false 被立刻冲掉。不要再在 FixedUpdate 写 automatic。
- **（前向搬运）** `OnAlreadyOwned` 手写 IL2CPP string、`dlopen("libil2cpp.so")`、`forceUnlockNow` 15s 调 `get_Instance()`、只 defer `onPackageReady` 不 defer `onPackageLoaded`、只 defer `forceLoad+initUnlock` 不 defer `ShadowHook.init`、ShadowHook SHARED 模式、`g_player_controls` 主动读 0x60、`carPilot`(0x68) 作玩家判据、base64 嵌入图标、`decodeResource` 跨进程、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、5 参 call 重载、`by lazy` 只改缓存、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段、统一公式画两种方向刹车——均不再试。

## 5. 已知坑
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知，可能在 Unity WheelCollider 引擎层（无法 IL2CPP hook）、IRDSSimplePhysics.FixedUpdate 刹车段（不读 absEnable）、或别的类。需要更深入反汇编。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要 DoGearShifting 内的离合器结合/挂挡逻辑。手动换挡关自动换挡需找到"只禁自动升降挡、不禁起步挂挡"的方式。
- **⚠️ FixedUpdate 每帧覆盖 automatic** [V]——`0x1a5de94: strb w9,[x19,#0xbc]`，写 `automatic=false` 被立刻冲掉。
- **⚠️ 游戏每帧覆盖 tclEnable/absEnable** [V]——logcat `before=1 after=1`，PlayerControls.Update 内部每帧设回 true。
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

## 6. 下一步（有序）
1. **找到 ABS 真正实现位置**——HandleABS 是死代码，需要反汇编研究：搜全 so 对 `absTriggered`(0xC8) 的写入点、对 `absEnable`(0xC4) 的所有 ldrb 读取点（已找到多处：`0x1a15208`/`0x1755858`/`0x17d10b0` 等，需确认哪些属于玩家车刹车 ABS 逻辑），或查 IRDSSimplePhysics.FixedUpdate 刹车段是否含 ABS。可能需要 hook 其他方法或 WheelCollider 摩擦模型。
2. **重做手动换挡关自动换挡**——DoGearShifting 不能整段跳过。需反汇编确认 DoGearShifting 内"自动升降挡"与"起步挂挡"的区别，可能只需禁自动升降挡部分（`0x1a5e754-0x1a5e8ac` 的自动换挡判断），保留起步挂挡逻辑。
3. **验证 AI 车误控是否已根治**——`proxy_player_controls_update` 天然只跑玩家车，理论上 M18 AI 误控应已解决，需真机多人模式验证。
4. **清理诊断日志**——`proxy_player_controls_update` 里的 TC/ABS 诊断日志已移除，但 `proxy_traction_filter`/`proxy_handle_abs` 的 hook 仍在（HandleABS hook 无用可删）。
5. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步 + CI workflow `prerelease: true` + tag `v1.0.0-Beta-3`。

## 7. 留给用户的开放问题
- ABS 真正实现位置在哪？HandleABS 是死代码，`absEnable` 字段不被内联 ABS 读取。是否在 Unity WheelCollider 引擎层（无法 IL2CPP hook）？还是在别的 IL2CPP 类里？
- 手动换挡关自动换挡：DoGearShifting 内"自动升降挡"与"起步挂挡"如何区分？能否只禁前者？
- M18 AI 车误控是否已由 `proxy_player_controls_update` 天然玩家车过滤根治？需真机多人验证。
- 诊断日志保留还是发 release 前去掉？
- `references/NPatch` 已 clone，保留供查阅 NPatch 源码。