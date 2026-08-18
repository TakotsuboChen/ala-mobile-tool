# HANDOFF — 读全文再开始干活

生成时间: 2026-08-18T20:55:18+08:00 · Git HEAD: `ac21138`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `ac21138` (2026-08-18)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `ac21138`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：修复所有弹窗系统返回时不关闭弹窗、退桌面的问题**。根因是 `ConfigActivity` 手动 `rememberNavigationEventDispatcherOwner(parent=null)` 创建了未绑定 `OnBackPressedDispatcher` 的独立 dispatcher，覆盖了 `ComponentActivity` 自带的 owner，导致弹窗 `NavigationBackHandler` 收不到系统返回事件。修复已提交，待真机验证。

## 2. 已验证状态 — 工作实际停在哪

- [V] **修复提交** — `ConfigActivity.kt` 删除 `rememberNavigationEventDispatcherOwner(parent=null)` + `LocalNavigationEventDispatcherOwner provides`，改用 `ComponentActivity` 自带的 `NavigationEventDispatcherOwner`。提交 `ac21138`，已 push。
- [V] **构建通过** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL，EXIT=0。
- [V] **lint 通过** — `./gradlew :app:lintDebug` → BUILD SUCCESSFUL，0 error, 28 warnings, 3 hints。
- [V] **APK 已安装** — `adb install -r app/build/outputs/apk/debug/app-debug.apk` → Success，设备 `381QYFCN22B9A`。
- [?] **真机验证未完成** — 需用户测试 SupportDialog/UpdateDialog/NonRootConfirmDialog 按返回关闭弹窗、EULA 按返回退桌面（门控预期）。
- [?] **M50 游戏版本检测胶囊** — `06b584c` 已提交，真机验证项仍待用户逐项确认。见旧 HANDOFF.md。
- [?] **M49 弹窗退出动画 + 检查更新 + 支持开发** — 提交 `c03eae3` 后，真机验证项仍待用户逐项确认。
- [?] **M47 EULA 启动门控** — `d115618` 已提交，真机验证未确认。
- [?] **M46 设置页 UI 重组** — `41ec5ee` 已提交，真机验证未确认。
- [?] **M45 移除「显示悬浮窗」开关** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — `resolveLatestSettings()` 的 `mergePositionFromLocalPublic()` 公开化，用户未确认是否生效。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 1s, EXIT=0
./gradlew :app:lintDebug → BUILD SUCCESSFUL, 0 error, 28 warnings, 3 hints
adb install -r app-debug.apk → Success
```

## 3. 决策与理由

- **删除手动 dispatcher owner 而非修复它** [V]——`activity 1.13.0` 的 `ComponentActivity` 已实现 `NavigationEventDispatcherOwner`，其 `getNavigationEventDispatcher()` 已通过 `addObserverForBackInvoker` 绑定 `OnBackPressedDispatcher`。`LocalNavigationEventDispatcherOwner` 在 Android 端是 `ViewTreeLocal`，`fallbackNavigationEventDispatcherOwner()` 从 `LocalContext` 沿 `ContextWrapper.baseContext` 找到 Activity owner。手动 `provides` 覆盖了这条自然链路。KernelSU 不手动创建 dispatcher owner，直接用 Activity 自带的，所以没这个 bug。否决方案：① 给手动 dispatcher 传 `OnBackCompletedFallback`——治标不治本，dispatcher 仍未绑 `OnBackPressedDispatcher`；② 在 `ConfigActivity` 手动注册 `OnBackPressedCallback` 桥接——重复造轮子，Activity 自带的已做。
- **EULA 按返回退桌面是预期行为** [V]——用户确认 EULA 门控保持，返回键 = 不同意 = `finish()`。

## 4. 失败的尝试 — 不要再试

> 以下全部从旧 HANDOFF 前向搬运，本会话未重新验证，标 [?]。

- [?] 响应曲线 summary 复用「油门/刹车踏板控件行程到游戏原生油门/刹车的映射方式」同一句贴到两条 — 用户明确否定：语义捆死两根轴，每条应只描述自己那条轴。
- [?] 胶囊放在 LazyColumn 首项（与卡片在一起） — 用户要求放在大标题上方的空白处。改到 `Scaffold` 的 `topBar`。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — `TopAppBar` 内部也处理状态栏 inset，状态栏高度被算两次。改用 `Box` 叠加。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 不同设备状态栏高度差异大。改用动态计算 `WindowInsets.statusBars.getTop(density)`。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 测到整个 `TopAppBar` 高度含大标题，减状态栏后远大于 52dp。改用 miuix `CollapsedHeight = 52.dp` 常量。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显 — 小标题 alpha 是 spring `Animatable`，不跟随 `fraction` 即时变化，上滑恢复时重叠。
- [?] `fraction` 阈值分段（`fraction < 0.15` 才渐显） — spring 动画完成时机不可从 `fraction` 推断，任何阈值都可能在某个滑动速度下重叠。
- [?] `translationY` 物理移出视区 + alpha 渐隐 — spring 动画滞后导致上滑时小标题还在显示而胶囊已移回。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 动画需要时间完成，下滑时小标题已开始显示而胶囊还没完全隐藏。改用 `snapTo(0)` 即时隐藏。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)` 创建独立 dispatcher** — 创建的 dispatcher 未绑定 `OnBackPressedDispatcher`，弹窗 `NavigationBackHandler` 收不到系统返回事件，直接 finish 退桌面。不要再试。改用 `ComponentActivity` 自带的。

## 5. 已知坑

- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable` 而非线性公式** [V]——`smallTitleAlpha` 和 `smallTitleTranslationY` 是 `Animatable<Float>`，在 `smallTitleVisible` 变化时用 `animateTo` 做 spring 过渡（damping 0.15 隐藏/0.3 显示）。不跟随 `collapsedFraction` 即时变化，任何用 `fraction` 线性驱动的联动动画都可能在某个滑动速度下与小标题不同步。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [V]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次，内容下移。用 `Box` 叠加让外层元素共享 `TopAppBar` 内部 inset。
- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。daemon 异步绑定可能延迟，广播比 remote 先到。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position，用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [V]——用 `Text` 显示进度百分比替代。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 minSdk 差异，用 `values-vNN` 或 `SDK_INT` 守卫。
- ⚠️ **不能手动 `rememberNavigationEventDispatcherOwner` 覆盖 Activity 自带的 dispatcher owner** [V]——`activity 1.13.0` 的 `ComponentActivity` 已实现 `NavigationEventDispatcherOwner` 并绑定 `OnBackPressedDispatcher`。手动创建独立 dispatcher 未绑定 `OnBackPressedDispatcher`，弹窗 `NavigationBackHandler` 收不到系统返回事件。`LocalNavigationEventDispatcherOwner` 是 `ViewTreeLocal`，从 `ContextWrapper` fallback 找到 Activity owner。

## 6. 下一步（有序）

1. **真机验证弹窗返回修复** — SupportDialog/UpdateDialog(非下载态)/NonRootConfirmDialog 按返回关闭弹窗、EULA 按返回退桌面（门控）。
2. **真机确认文案修改** — 弹窗标题「向开发者捐赠」、响应曲线 summary。
3. **真机验证 M50 胶囊** — 官版/共存版三态、亮暗色、下滑即时隐藏/上滑延迟渐显。
4. **真机验证 M49 各项** — 弹窗退出动画、检查更新、支持开发卡片。
5. **真机验证 M47 EULA 启动门控**。
6. **验证 position 合并修复** — 切双踏板再切回单踏板，位置/大小是否保持。
7. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB）。

## 7. 留给用户的开放问题

- 弹窗返回修复真机表现是否满意？
- 文案修改（弹窗标题、两条响应曲线 summary）真机表现是否满意？
- M49 各项真机表现是否满意？
- M47 EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 是否计划近期发 stable release？