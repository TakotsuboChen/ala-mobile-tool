# HANDOFF — 读全文再开始干活

生成时间: 2026-08-18T15:20:00+08:00 · Git HEAD: `c03eae3`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `c03eae3` (2026-08-18)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `c03eae3`；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**M50：概览页游戏版本检测胶囊**。在 "Ala Mobile Tool" 大标题上方空白处新增两个并排胶囊（官版在前、共存版在后），每次启动自动检测安装情况与版本适配。已实现、编译通过、adb 安装成功、用户确认 OK。

## 2. 已验证状态 — 工作实际停在哪

- [V] **游戏版本检测胶囊** — `VersionCapsule.kt` + `GameVersionChecker.kt` + `OverviewPagerMiuix.kt` + `AndroidManifest.xml`。两个胶囊放在 `Scaffold` 的 `topBar` 里 `TopAppBar` 上方（`Box` 叠加），左端对齐，动态计算垂直位置（状态栏高度 + (52dp - 胶囊高度)/2）。提交 `06b584c`。
- [V] **静默查询无需权限** — `<queries>` 声明 `com.Vince.AlamobileFormula` + `com.Takotsubo.AlamobileFormula`，`getPackageInfo` 静默可见，无运行时权限请求。Android 原生无"读取应用列表"危险权限，去掉用户原需求第 3 种"未授权"状态。
- [V] **三态显示** — 已适配=绿（同激活卡片配色 `0xFF1A3825`/`0xFFDFFAE4`）、未适配=红（同未激活 `0xFF3D1A1A`/`0xFFFAE4E4`）、未安装=黄（`0xFF3D3A1A`/`0xFFFAF4D6`），亮暗色模式同步。
- [V] **收缩动画** — 用 `Animatable<Float>` + `LaunchedEffect(fraction)` 非对称：下滑 `fraction > 0` 时 `snapTo(0)` 即时隐藏；上滑 `fraction == 0` 时 `delay(350ms)` 等小标题 spring 淡出后 `animateTo(1)` 渐显。解决下滑/上滑与居中小标题重叠。
- [V] **编译通过** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (exit 0)。
- [V] **adb 安装成功** — `adb install -r` → Success。
- [V] **用户确认 OK** — 胶囊位置、配色、收缩动画均经真机验证。
- [V] **工作区干净** — `git status` 无未提交改动，`main` 与 `origin/main` 同步。
- [?] **M49 弹窗退出动画 + 检查更新 + 支持开发** — 上一会话完成，真机验证项仍待用户逐项确认。见旧 HANDOFF.md。
- [?] **M47 EULA 启动门控** — `d115618` 已提交，真机验证未确认。
- [?] **M46 设置页 UI 重组** — `41ec5ee` 已提交，真机验证未确认。
- [?] **M45 移除"显示悬浮窗"开关** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — `resolveLatestSettings()` 的 `mergePositionFromLocalPublic()` 公开化，用户未确认是否生效。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 1s, 40 tasks, UP-TO-DATE, exit 0
adb install -r app/build/outputs/apk/debug/app-debug.apk → Success
```

## 3. 决策与理由

- **`<queries>` 静默查询而非运行时权限** [V]——Android 原生无"读取应用列表"危险权限，`<queries>` 声明特定包名后 `getPackageInfo` 静默可见，无需用户授权。否决方案：应用内自定义对话框模拟权限请求，不自然且多余。
- **`Box` 叠加而非 `Column` 包裹** [V]——`TopAppBar` 内部自带状态栏 inset 处理，`Column` 加 `Spacer` 会导致状态栏高度被算两次，内容下移。`Box` 让胶囊行和 `TopAppBar` 共享 `TopAppBar` 内部的 inset，胶囊用 `offset { IntOffset(0, topOffsetPx) }` 精确定位。
- **动态计算 top offset 而非硬编码 dp** [V]——`topOffset = WindowInsets.statusBars.getTop(density) + (52dp - 胶囊高度)/2`。52dp 是 miuix `CollapsedHeight` 常量（状态栏下边缘到大标题上边缘的空间）。适配所有设备状态栏高度。否决方案：硬编码 8dp top padding，不同设备状态栏高度差异大。
- **`Animatable` 非对称动画而非线性 `fraction` 公式** [V]——小标题 alpha 是 spring `Animatable`，不跟随 `fraction` 即时变化。线性公式 `(1 - fraction * 3)` 在上滑恢复时与小标题 spring 动画不同步，慢滑/快滑都重叠。改用 `Animatable` + `LaunchedEffect(fraction)`：下滑 `snapTo(0)` 即时隐藏，上滑 `fraction == 0` 时 `delay(350)` 等 spring 完成后渐显。否决方案：`translationY` 物理移出视区，仍因 spring 滞后重叠。

## 4. 失败的尝试 — 不要再试

- [X] **胶囊放在 LazyColumn 首项（与卡片在一起）** — 用户明确要求放在大标题上方的空白处，不是和卡片在一起。改到 `Scaffold` 的 `topBar`。
- [X] **`Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏** — `TopAppBar` 内部也处理状态栏 inset，状态栏高度被算两次，"Ala Mobile Tool" 和卡片整体下移。改用 `Box` 叠加。
- [X] **硬编码 `padding(top = 8.dp)` 定位胶囊** — 不同设备状态栏高度差异大，8dp 在某设备上居中在另一设备上贴边。改用动态计算 `WindowInsets.statusBars.getTop(density)`。
- [X] **`onSizeChanged` 测量 `TopAppBar` 展开态总高度** — 测到的是整个 `TopAppBar` 高度（含大标题），减去状态栏后远大于实际可用空间（52dp），胶囊被推到标题位置。改用 miuix `CollapsedHeight = 52.dp` 常量。
- [X] **线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显** — 小标题 alpha 是 spring `Animatable`，不跟随 `fraction` 即时变化。上滑恢复时 spring 动画滞后，胶囊用线性公式已开始渐显而小标题还没隐藏完，重叠。慢滑/快滑都复现。
- [X] **`fraction` 阈值分段（`fraction < 0.15` 才渐显）** — spring 动画完成时机不可从 `fraction` 推断，任何阈值都可能在某个滑动速度下重叠。
- [X] **`translationY` 物理移出视区 + alpha 渐隐** — spring 动画滞后导致上滑时小标题还在显示而胶囊已移回，仍重叠。
- [X] **`spring` 动画 `animateTo(0)` 渐隐** — 动画需要时间完成，下滑时小标题已开始显示而胶囊还没完全隐藏，重叠。改用 `snapTo(0)` 即时隐藏。

## 5. 已知坑

- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable` 而非线性公式** [V]——`smallTitleAlpha` 和 `smallTitleTranslationY` 是 `Animatable<Float>`，在 `smallTitleVisible` 布尔值变化时用 `animateTo` 做 spring 过渡（damping 0.15 隐藏/0.3 显示）。不跟随 `collapsedFraction` 即时变化，任何用 `fraction` 线性驱动的联动动画都可能在某个滑动速度下与小标题不同步。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [V]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次，内容下移。用 `Box` 叠加让外层元素共享 `TopAppBar` 内部 inset。
- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。daemon 异步绑定可能延迟，广播比 remote 先到。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position，用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [V]——用 `Text` 显示进度百分比替代。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 minSdk 差异，用 `values-vNN` 或 `SDK_INT` 守卫。

## 6. 下一步（有序）

1. **真机验证 M50 胶囊** — 用户已确认基本 OK，可逐项细验：官版/共存版已适配/未适配/未安装三态显示、亮暗色配色、下滑即时隐藏、上滑延迟渐显。
2. **真机验证 M49 各项** — 弹窗退出动画、检查更新流程、更新通道、跳过/清除、支持开发卡片（见旧 HANDOFF.md M49 验证项）。
3. **真机验证 M47 EULA 启动门控** — 见旧 HANDOFF.md M47 验证项。
4. **验证 position 合并修复** — 用户确认"切双踏板再切回单踏板，位置/大小是否保持"。
5. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB）。

## 7. 留给用户的开放问题

- M50 胶囊各项表现是否满意（三态显示、配色、收缩动画）？
- M49 各项真机表现是否满意？
- M47 EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 1970/置顶问题等正式版解决——是否计划近期发 stable release？