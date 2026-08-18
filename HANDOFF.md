# HANDOFF — 读全文再开始干活

生成时间: 2026-08-18T20:28:26+08:00 · Git HEAD: `82ce729`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `82ce729` (2026-08-18)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `82ce729`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：修复最近 5 次 CI 失败**。根因是 `GameVersionChecker.kt:43` 调用 `PackageInfo.longVersionCode`（API 28+）但项目 `minSdk = 26`，lint NewApi 硬 error 拦构建。改用 `PackageInfoCompat.getLongVersionCode(info)` 修复，CI 已转绿。

## 2. 已验证状态 — 工作实际停在哪

- [V] **CI 修复提交** — `GameVersionChecker.kt:43` 的 `info.longVersionCode` → `PackageInfoCompat.getLongVersionCode(info)`，新增 `import androidx.core.content.pm.PackageInfoCompat`。提交 `82ce729`，已 push。
- [V] **本地 lint 通过** — `./gradlew :app:lintDebug` → BUILD SUCCESSFUL，lint 从「1 error, 28 warnings, 3 hints」变成「0 error, 28 warnings, 3 hints」。
- [V] **远程 CI 转绿** — CI run `32128876731`（HEAD `82ce729`）→ conclusion = `success`，build job 全绿，「Run lint」step ✓。
- [V] **五次失败同因** — 查验 run 32112291658/32112175792/32110771813/32110709013/32110671469 的日志，全部是 `GameVersionChecker.kt:43` 的 NewApi error，修一处全解。
- [V] **工作区干净** — `git status` 无未提交改动（除后续 HANDOFF 提交本身），`main` 与 `origin/main` 同步。
- [?] **M50 游戏版本检测胶囊** — `06b584c` 已提交，真机验证项仍待用户逐项确认。见旧 HANDOFF.md。
- [?] **M49 弹窗退出动画 + 检查更新 + 支持开发** — 上会话完成（提交 `c03eae3` 后），真机验证项仍待用户逐项确认。
- [?] **M47 EULA 启动门控** — `d115618` 已提交，真机验证未确认。
- [?] **M46 设置页 UI 重组** — `41ec5ee` 已提交，真机验证未确认。
- [?] **M45 移除「显示悬浮窗」开关** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — `resolveLatestSettings()` 的 `mergePositionFromLocalPublic()` 公开化，用户未确认是否生效。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:lintDebug → BUILD SUCCESSFUL in 1m 23s, 29 actionable tasks, 11 executed, 18 up-to-date
Lint found 0 error, 28 warnings, 3 hints (and 3 errors and 17 warnings filtered by baseline lint-baseline.xml)
CI run 32128876731 → success (build job 5m57s, Run lint ✓)
```

## 3. 决策与理由

- **用 `PackageInfoCompat.getLongVersionCode(info)` 而非手写 `SDK_INT` 分支** [V]——`PackageInfoCompat` 内部用 `Api28Impl` 分支：API ≥ 28 直接调 `info.longVersionCode`，API < 26 退化到 `info.versionCode.toLong()`。lint 看到 compat 调用而非直接调用 API 28 属性，NewApi 放行。比手写分支更干净，且 `androidx.core:core-ktx:1.18.0` 已通过 miuix 传递依赖，无需改 build 配置。否决方案：① `SDK_INT >= 28` 分支 + `@Suppress("DEPRECATION")` 旧版用 `versionCode.toLong()`——可行但啰嗦；② `@Suppress("NewApi")` + 注释——掩盖意图，不治本。

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

## 5. 已知坑

- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable` 而非线性公式** [V]——`smallTitleAlpha` 和 `smallTitleTranslationY` 是 `Animatable<Float>`，在 `smallTitleVisible` 变化时用 `animateTo` 做 spring 过渡（damping 0.15 隐藏/0.3 显示）。不跟随 `collapsedFraction` 即时变化，任何用 `fraction` 线性驱动的联动动画都可能在某个滑动速度下与小标题不同步。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [V]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次，内容下移。用 `Box` 叠加让外层元素共享 `TopAppBar` 内部 inset。
- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。daemon 异步绑定可能延迟，广播比 remote 先到。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position，用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [V]——用 `Text` 显示进度百分比替代。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 minSdk 差异，用 `values-vNN` 或 `SDK_INT` 守卫。**本会话已修复 `GameVersionChecker.kt` 的 `longVersionCode` 具体实例**（改用 `PackageInfoCompat`），但通用坑仍成立：未来引入新 API 调用前先查 `minSdk` 兼容性。

## 6. 下一步（有序）

1. **真机确认文案修改** — 模块配置页确认弹窗标题「向开发者捐赠」、概览页两条响应曲线 summary。
2. **真机验证 M50 胶囊** — 官版/共存版已适配/未适配/未安装三态、亮暗色、下滑即时隐藏/上滑延迟渐显。
3. **真机验证 M49 各项** — 弹窗退出动画、检查更新、支持开发卡片。
4. **真机验证 M47 EULA 启动门控**。
5. **验证 position 合并修复** — 切双踏板再切回单踏板，位置/大小是否保持。
6. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB）。

## 7. 留给用户的开放问题

- 文案修改（弹窗标题、两条响应曲线 summary）真机表现是否满意？
- M49 各项真机表现是否满意？
- M47 EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 是否计划近期发 stable release？