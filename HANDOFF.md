# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T12:37:19+08:00 · Git HEAD: b7b4581（本轮改动尚未 commit）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

M13：修复 M12 遗留的 5 项体验问题 + 双踏板仲裁机制。**已完成并真机测试通过**：长按重置到出厂默认、SINGLE/DUAL 位置分离、拟真曲线温和化（30%→45%）、双踏板油门/刹车仲裁、刹车过渡点 UI。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `b7b4581`，7 个文件改动未 commit。
- [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL in 44s（compileDebugKotlin 通过）。
- [V] `adb install -r app/build/outputs/apk/debug/app-debug.apk` Success（设备 381QYFCN22B9A）。
- [V] 用户真机测试通过（"可以了，目前测试通过"）。
- [V] 五项改动闭环（代码审查 + 真机）：
  1. `OverlayEditView.kt`：构造拆 `defaultPosition`（= `OverlayPosition.DEFAULT_*` 出厂默认，重置目标）+ `runtimePosition`（运行时已保存值，初始布局对齐）。长按重置回到出厂默认而非已保存值。
  2. `ModConfig.kt`：新增 `KEY_SINGLE_PEDAL_POSITION` + `singlePedalPosition` 字段（默认 DEFAULT_PEDAL）；新增 `KEY_BRAKE_TRANSITION` + `brakeTransition` 字段（默认 0.1）。read/fromJson/write/defaultSettings/Settings data class 全同步。
  3. `ConfigReceiver.kt`：`POSITION_KEYS` 加入 `single_pedal_position`，广播合并写保留它。
  4. `OverlayManager.kt`：SINGLE 分支用 `singlePosition` + `addPedalEditLayer(pedalParams, singlePosition, KEY_SINGLE_PEDAL_POSITION)`；DUAL 油门仍用 `pedalPosition` + `KEY_PEDAL_POSITION`——两者彻底独立存储。三个 `add*EditLayer` 显式传 `DEFAULT_PEDAL/BRAKE/GEAR` 作重置默认，`runtimePosition` 作初始布局。
  5. `PedalOverlayView.kt`：companion 加 `sharedRawThrottle/Brake`、`sharedBrakeTransition`、`arbitratedThrottle/Brake`（全 `@Volatile`）。`updateDedicatedThrottle/Brake` 各自更新共享 raw 后调 `arbitrateDual()`：brake≥transition（且>0）→ 刹车优先屏蔽油门 mapped；brake<transition 且 throttle>0 → 油门优先屏蔽刹车 mapped。屏蔽只作用于 mapped/native，raw 仍跟手绘制。ACTION_UP/CANCEL 同步清本 view 的共享 raw。exponent 0.42→0.66（30%→45%）。
  6. `ConfigMainScreen.kt`：ConfigUiState 加 `brakeTransition`，saveNow 传入。
  7. `ConfigurePage.kt`：DUAL 模式下 AnimatedVisibility 弹出"刹车过渡点"滑动条 0–20%，与 SINGLE 的死区/过渡点对称。

### 测试/build 输出 tail

```
$ ./gradlew :app:assembleDebug
> Task :app:compileDebugKotlin
> Task :app:assembleDebug
BUILD SUCCESSFUL in 44s
$ adb install -r app/build/outputs/apk/debug/app-debug.apk
Performing Streamed Install
Success
```

## 3. 决策与理由

- **SINGLE/DUAL 位置分离用独立 JSON 字段** [V]——根因：SINGLE 和 DUAL-油门 view 共用 `pedal_position`，SINGLE 拖拽 `saveOverlayPosition(KEY_PEDAL_POSITION)` 污染 DUAL 油门。新增 `single_pedal_position` 独立槽，SINGLE 拖拽只写它。否决方案：切模式时重置 position——丢失用户分别调好的位置，违反"分别配置"预期。
- **长按重置拆 defaultPosition/runtimePosition** [V]——根因：M12 `OverlayEditView` 传 `settings.*Position` 作 `defaultPosition`，重置只是回到当前已保存值，用户感知无变化。拆为出厂默认（重置目标）+ 运行时值（初始布局对齐）。否决方案：只传 DEFAULT_*——丢失初始布局对齐，editView 初始位置与 target view 不符。
- **双踏板仲裁用 companion 共享 @Volatile 状态** [V]——油门/刹车是两个独立 View，各自 onTouchEvent 互不可见。用 companion 静态字段持共享 raw，任意 view 更新都调 arbitrate()。用 raw 判定（跟手即时、不受曲线影响）、用 mapped 屏蔽（送 native）——与 M11 raw/mapped 分离设计一致：视觉仍跟手，仅 native 输出被仲裁。否决方案：让两 view 互相引用——View 间无引用通道，companion 是最小耦合。
- **ACTION_UP 清本 view 共享 raw** [V]——否则一指抬起另一指还在屏上时，仲裁仍用旧 raw 误判（例如油门抬起后刹车未过点，mappedBrake 仍被油门优先规则屏蔽）。
- **exponent 0.66（30%→45%）** [V]——用户反馈 0.42（30%→60%）太激进。`0.3^x=0.45` → `x=ln0.45/ln0.3≈0.66`。更温和，先快后慢仍是 ease-out 方向。

## 4. 失败的尝试 — 不要再试

- **M12 OverlayEditView 长按重置传 settings.*Position 作 defaultPosition** [V]——重置只是回到当前已保存值，用户感知无变化。必须传 DEFAULT_* 出厂默认。
- **SINGLE/DUAL 共用 pedal_position 字段** [V]——SINGLE 拖拽污染 DUAL 油门位置，"双踏板的油门控件的位置大小居然是之前调过的单踏板的位置大小"。必须独立字段 single_pedal_position。
- （前向搬运自 M12）ConfigReceiver 直接 writeText 覆盖 [V]、root 保持 val 不刷新 [V]、`root?.findViewWithTag()?.let` smart cast 失败 [V]、文件直读跨进程 [V]、ContentProvider 跨进程 [V]、createPackageContext [V]、5 参 call 重载 [V]、by lazy 只改缓存不够 [V]、applyCurve 作用单字段 [V]、BRAKE 从底向上画水位式 [V]——均不再试。

## 5. 已知坑

- **原版/共存版布局存档不共用** [V]——原版和共存版 externalFilesDir 按包名隔离，两版各自内部 position 正确，跨版不共享。Android 沙箱设计，无法绕过。本轮用户"暂时接受不共用"，保留此问题。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播 Intent.setPackage 是唯一可靠跨进程 IPC。
- **Android 13+ registerReceiver 需 flag** [V]——RECEIVER_EXPORTED（跨应用），targetSdk 35 强制。
- **广播首次启动滞后** [V]——首次安装后首次进游戏读默认值；用户改一次配置后 receiver 接收并写入，之后即时生效。
- **PedalOverlayView 构造拷 settings 快照** [V]——加/改 Settings 字段必须同步默认参数构造；配置变更必须重建 view（rebuildFromConfigChange 或 toggle）。本轮加了 brakeTransition 和 singlePedalPosition，默认参数构造已同步。
- **applyCurve exponent 方向** [V]——<1 是 ease-out（先快后慢），≥1 是 ease-in。拟真用 0.66（30%→45%）。仅作用于 mapped（送 native），不影响 raw（绘制）。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 模式单 view 内 updateSingle 已自洽（上下分区互斥），不走 arbitrateDual 路径。
- **仲裁用 raw 判定、mapped 屏蔽** [V]——raw 跟手即时（视觉反馈手指位移），mapped 送 native 被仲裁。两指同按时视觉两指都填充，但游戏只收到优先方的输入。
- **ConfigProvider.kt 已废弃** [V]——广播方案落地后未使用，代码仍在，可删。
- **OverlayManager.settings 不再 by lazy** [V]——改可重读 var。
- **removeGamingOverlays vs removeExisting** [V]——前者只清踏板/换挡 view 保留 toggle 按钮，后者连按钮清。
- **ModConfig.read() 对 enableAutoDrs 强制 false** [V]——功能未实现，避免老用户升级后开关显示"开"但无效果。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。notifyConfigChanged 在第二个 ClassLoader 是 no-op（instance 为 null）。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。

## 6. 下一步（有序）

1. **commit + 发 Beta**——本轮 5 项修复闭环，真机测试通过，可 commit 后发 Beta（versionCode 按命名规则，versionName `1.0.0 Beta 3`）。
2. **清理 ConfigProvider.kt**（可选）——广播方案落地后未使用，可删避免混淆。manifest 里 provider 声明一并删。
3. （后续）实现"手动换挡""自动 DRS"——换挡开关当前 UI 禁用，启用时加 DUAL 互斥 UI 联动。自动 DRS 默认读 false，待 IL2CPP telemetry 字段接入。

## 7. 留给用户的开放问题

- 原版/共存版布局不共用：用户已"暂时接受"，保留。未来若要同步需 ConfigActivity 写 position 到两版都能读的位置（受 scoped storage 限制，复杂）。
- 拟真曲线 exponent=0.66（30%→45%）手感已确认合适（本轮真机通过）。
- 双踏板刹车过渡点默认 10% 是否合适？用户可在配置页调 0–20%，本轮默认值未反馈是否需改。
