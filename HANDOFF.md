# HANDOFF — 读全文再开始干活

生成时间: 2026-08-14T03:05:00+08:00 · Git HEAD: `ab0aaea`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `ab0aaea` (2026-08-13)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `ab0aaea`；变了说明快照可能过期
- 待重探的 [?]: 无
- 先读: `CLAUDE.md` + 本文件 + `references/KernelSU/manager/` 全树

## 1. 当前目标

**全盘照搬 KernelSU manager 的 UI，彻底重构 ala-mobile-tool 的配置界面。** 不是"选择性借鉴"——用户多次强调"任何东西全部用 KernelSU 的"。包括：Navigation3（`NavDisplay` + `entryProvider` + `LocalNavAnimatedContentScope`）、miuix preference 组件（`SwitchPreference`/`ArrowPreference`，不是手写 `Row+Column+Text`）、双层 backdrop、`rememberContentReady`、`MainScreenBackHandler`、`SavedStateHandle` tab 恢复、`shouldShowSplitPane` rail 分支。

完成定义：切 tab 无掉帧（gfxinfo janky < 5%），blur 开启，UI 结构与 KernelSU `MainActivity.kt` + 各 `*Miuix.kt` 一一对应。

## 2. 已验证状态 — 工作实际停在哪

- [V] **工作区干净**——`git status` 无未提交改动。本次会话的所有 A/B 测试代码已 `git checkout -- .` + `git clean -fd` 回退到 `ab0aaea`。
- [V] **HEAD 未变**——`git rev-parse --short HEAD` = `ab0aaea`，与 M37 handoff 一致。本次会话没有产生任何 commit。
- [V] **M37 的"已解决"是假阳性**——用户明确："之前那些说的流畅都是假的，不然我为什么叫你重构"。M37 handoff 记录的"真机验证通过，非常流畅"不可信。用户每次测到一次流畅就 /handoff，导致上一会话误判已解决。
- [V] **当前代码状态 = M37**——`ab0aaea` = M37 的 handoff commit，代码内容 = `4ea4ce0`（perf(ui) 切页掉帧修复）。用 HorizontalPager + 启发式 `contentReady`（`currentPageOffsetFraction != 0f`）+ 双层 backdrop，**无 Navigation3**。

### 本会话 A/B 测试数据（真机骁龙 8 Gen 2，设备 `381QYFCN22B9A`）

| 配置 | janky% | 50th | 结论 |
|---|---|---|---|
| M37 现状（无 Nav3 + blur 开 + 启发式 contentReady） | 8% [?] | — | 用户说"还是掉帧"（M37 handoff 的 8% 不可信） |
| Nav3 + blur 开 + 真实混合 3 page | 24-35% [V] | 18-20ms | 卡 |
| Nav3 + blur 关 + 真实混合 3 page | 20% [V] | 14ms | 卡 |
| Nav3 + blur 开 + 3 个相同 OverviewPage | 1.29% [V] | 8ms | 流畅 |
| Nav3 + blur 开 + 3 个相同 ConfigurePage | 2.19% [V] | 8ms | 流畅 |
| Nav3 + blur 开 + 3 个相同 SettingsPage | 1.70% [V] | 8ms | 流畅 |
| Nav3 + blur 开 + 空 page | 1.92% [V] | 8ms | 流畅 |
| KernelSU（blur 开） | 1.31% [V] | 9ms | 流畅（用户亲测） |

**关键发现**：三个相同 page 流畅，混合三个不同 page 就卡。blur 不是唯一原因（关掉也 20%），Nav3 本身也有开销。

## 3. 决策与理由

- **本次会话未产生可保留的代码** [V]——所有改动是 A/B 测试残骸，已回退。原因：测试过程中代码被反复修改（去 AnimatedVisibility、去 scrollBehavior、去 page backdrop、改 beyondViewportPageCount），最终没有找到一个可用的稳定状态。
- **用户核心诉求重新理解** [V]——用户要的不是"照搬 KernelSU 的 Navigation3 架构"，而是"照搬 KernelSU 的**全部 UI 实现**，包括 page 内部的 composable 用 miuix preference 组件而非手写 Row+Column"。我之前只照搬了外壳（NavDisplay/backdrop/pager），没有把 page 内部的 `SwitchRow`/`SliderRow` 换成 miuix `SwitchPreference`/`SliderPreference`。
- **KernelSU 的 page 用 miuix preference 组件** [V]——KernelSU `HomeMiuix.kt`/`SettingsMiuix.kt` 用 `SwitchPreference`/`ArrowPreference`/`OverlayDropdownPreference`。我们用 `Row+Column+Text+Switch` 手写。手写组件在 Nav3 的 entry scope 下产生更多 RenderNode（trace 显示 72 次 `calculateBounds`），KernelSU 的 trace 里 0 次 `calculateBounds`。

## 4. 失败的尝试 — 不要再试

- **Nav3 + 手写 page composable + blur** [X]——24-35% janky。手写 `SwitchRow`/`SliderRow` 产生过多 RenderNode，blur 放大开销。不要再用手写 Row+Column+Text 做 preference 项。
- **Nav3 + 手写 page composable + blur 关** [X]——20% janky。即使关 blur，Nav3 本身的 entry 渲染开销 + 手写组件的 RenderNode 仍然卡。说明问题不只在 blur。
- **Nav3 + 去掉 page 内 backdrop（只留外层 blurBackdrop）** [X]——29% janky。去掉 page 内 `layerBackdrop` 没有改善。
- **Nav3 + 去掉 AnimatedVisibility** [X]——35% janky。不是 AnimatedVisibility 的问题。
- **Nav3 + 去掉 MiuixScrollBehavior + nestedScroll** [X]——24% janky。不是 scrollBehavior 的问题。
- **Nav3 + 禁用 LsposedStatus.evaluate 异步轮询** [X]——8.28%（但滑动后变流畅）。不是 LsposedStatus 的 3 秒轮询。
- **Nav3 + swipe 时临时关 blur（currentPageOffsetFraction != 0f → blur off）** [X]——45% janky。频繁挂载/卸载 layerBackdrop 开销更大。
- **Nav3 + 延迟 contentReady 30 帧后放开 beyondViewportPageCount** [X]——先卡后流畅（滑动后）。延迟没用。
- **Nav3 + beyondViewportPageCount=0（只渲染当前页）** [X]——24% janky。不预组相邻页也卡。
- **三个相同 page + blur** [V]——流畅（1.3-2.2%）。相同结构的 page blur 纹理可复用。**但这对实际应用无意义——我们需要三个不同 page。**
- **（前向搬运 M37）共享同一个 LayerBackdrop 实例多处 layerBackdrop** [X]——SIGSEGV @ RenderThread。
- **（前向搬运 M37）移除外层 layerBackdrop** [X]——底栏模糊消失。
- **（前向搬运 M37）在 remember{} 里调 evaluate(awaitService=true)** [X]——阻塞主线程。
- **（前向搬运 M37）Handler.postDelayed 做异步写配置** [X]——实际在 main looper。

## 5. 已知坑

- **⚠️ KernelSU blur 默认关** [V]——`SettingsRepositoryImpl.kt:69` `prefs.getBoolean("enable_blur", false)`。KernelSU 流畅可能部分因为默认不开 blur。但用户说开了 blur 也流畅（gfxinfo 1.31% 验证）。
- **⚠️ "滑动后流畅"现象未解释** [?]——多次复现：冷启动后切 tab 卡（20-35%），在某个 page 上下滑动一次后切 tab 变流畅（1-8%）。杀掉重来又卡。不是着色器编译（blur 关也复现），不是 scrollBehavior（去掉也复现），不是 LsposedStatus（禁用也复现）。可能是 Skia GPU 着色器缓存或 Compose layout 缓存，但未证实。
- **⚠️ AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错。只保留 `compose.compiler` + `kotlin-parcelize`。
- **（前向搬运 M37）NPatch 需要管理器唤醒注入** [V]。
- **（前向搬运 M37）apktool 2.7.0 doNotCompress 丢失** [V]。
- **（前向搬运 M37）/tmp 是 tmpfs 只有 7.3G** [V]。

## 6. 下一步（有序）

1. **升级构建配置**——AGP 8.9.1→9.3.1, Kotlin 2.4.0→2.4.10, Gradle 8.11.1→9.6.1, 加 Compose BOM 2026.06.01 + navigation3 1.1.4 + miuix-navigation3-ui + lifecycle-viewmodel-navigation3。AGP 9 去掉 `kotlin-android` 插件。`compileSdk` 改新语法。加 `kotlin-parcelize`。本次会话已验证此配置可编译通过。
2. **照搬 KernelSU 基础设施文件**——`DeferredContent.kt`、`PagerNavigationSpring.kt`、`MainPagerState.kt`、`WindowSize.kt`、`Navigator.kt`、`Routes.kt`、`ConfigMainViewModel.kt`。本次会话已写好这些文件但已回退，可从 git 历史或重新照搬。
3. **重写 ConfigActivity.kt**——`NavDisplay` + `entryProvider` + `rememberNavigator`。
4. **重写 ConfigMainScreen.kt → MainScreen**——照搬 KernelSU `MainActivity.kt:226-411`。
5. **关键：把三个 page 的手写 composable 全部换成 miuix preference 组件**——`SwitchRow`→`SwitchPreference`，`ArrowRow`→`ArrowPreference`，`SliderRow`→`SliderPreference`（如果有），`OverlayDropdownPreference` 保留。这是本次会话没有做的核心步骤，也是卡顿的可能根因。
6. **构建验证 + 真机测试**——blur 开启，janky < 5%。

## 7. 留给用户的开放问题

- KernelSU 开 blur 在你设备上 1.31% janky——它的 page 用 miuix preference 组件。换成 preference 组件后我们的 janky 能否降到 5% 以下？未验证。
- "滑动后流畅"现象的根因仍未定位——如果换 preference 组件后不再出现此现象，说明确实是手写组件的 RenderNode 问题。
- 是否需要照搬 KernelSU 的 `HomePager`/`SettingPager` 等 wrapper（含 ViewModel + UiMode 分发）？还是直接调 `*Miuix` composable？
