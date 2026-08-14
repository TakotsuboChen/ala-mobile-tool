# HANDOFF — 读全文再开始干活

生成时间: 2026-08-14T12:45:00+08:00 · Git HEAD: `f3cd35b`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `refactor/ui-kernelsu-clone` @ `f3cd35b` (2026-08-14)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `f3cd35b`；变了说明快照可能过期
- 先读: `CLAUDE.md` + 本文件 + `references/KernelSU/manager/` 全树

## 1. 当前目标

**完全照搬 KernelSU Manager UI，达到 KernelSU 同等流畅度（janky < 1%）。**

当前状态：janky 22-38%，KernelSU 0.3-0.7%。差距 50-100 倍。多次会话反复尝试均未解决。

## 2. 已验证状态 — 工作实际停在哪

- [V] **构建配置已对齐 KernelSU** — AGP 9.3.1, Kotlin 2.4.10, Gradle 9.6.1, Compose BOM 2026.06.01, navigation3 1.1.4。`./gradlew :app:assembleRelease` → BUILD SUCCESSFUL
- [V] **Navigation3 基础设施已照搬** — Navigator.kt, Routes.kt, PagerNavigationSpring.kt, MainPagerState.kt, WindowSize.kt, DeferredContent.kt, BlurExt.kt, Theme.kt, UiMode.kt
- [V] **ViewModel 已创建** — MainActivityViewModel (SavedStateHandle), ConfigViewModel (异步 IO 加载配置)
- [V] **ConfigActivity 已重写** — NavDisplay + entryProvider + CompositionLocalProvider + MiuixTheme
- [V] **MainScreen 已重写** — 照搬 MainActivity.kt:226-388，含 HorizontalPager + 双层 layerBackdrop + MainScreenBackHandler
- [V] **三个 page 已重写** — OverviewPagerMiuix / ConfigurePagerMiuix / SettingsPagerMiuix，全部用 miuix SwitchPreference / ArrowPreference / OverlayDropdownPreference
- [V] **旧文件已删除** — ConfigMainScreen.kt, ConfigurePage.kt, OverviewPage.kt, SettingsPage.kt, BlurExt.kt (旧位置)
- [V] **工作区干净** — `git status --short` 无未提交改动。所有工作已 commit 到 `refactor/ui-kernelsu-clone` 分支并 push。

### A/B 测试数据（真机骁龙 8 Gen 2，设备 `381QYFCN22B9A`）

3 轮冷启动 + 切 tab：

| | 我们 (round 1-3) | KernelSU (round 1-3) |
|---|---|---|
| Janky% | 22%, 25%, 38% | **0.34%, 0.34%, 0.68%** |
| Frames | 114-135 | 291-298 |

KernelSU 帧数 291-298 vs 我们 114-135——相同时间内 KernelSU 渲染了 2.5 倍帧。

## 3. 决策与理由

- **全盘照搬 KernelSU** [V]——用户多次明确要求"任何东西全部用 KernelSU 的"，不是选择性借鉴
- **去掉了 rememberContentReady** [V]——它依赖 `LocalNavAnimatedContentScope.current.transition.isRunning`，但我们的 page 是 HorizontalPager 内部切换不经过 Nav3 AnimatedContent，transition 永远不 running。直接全预组 `beyondViewportPageCount = LAST_PAGE_INDEX`
- **ConfigViewModel 异步加载** [V]——`ModConfig.read` 做文件 IO + JSON 解析，在构造函数同步调用阻塞主线程。改为 init block IO 线程 + 默认值初始态

## 4. 失败的尝试 — 不要再试

- [X] **手写 SwitchRow/SliderRow → miuix preference 组件** — 换了仍 22-38% janky。RenderNode 数量不是根因
- [X] **关 blur（enable_blur=false）** — 仍 15-25% janky。blur 不是根因
- [X] **移除 rememberContentReady 门控** — 直接全预组仍 22-38% janky
- [X] **ModConfig.read 改 IO 线程异步** — 仍 22-38% janky
- [X] **LsposedStatus.evaluate 改完全异步（null 初始态）** — 仍 22-38% janky
- [X] **M37/M38 的"切页掉帧已解决"是假阳性** — M37 handoff 记录"真机验证通过，非常流畅"不可信。用户每次测到一次流畅就 /handoff 导致误判

## 5. 已知坑

- ⚠️ **KernelSU blur 默认关** [V]——`SettingsRepositoryImpl.kt:69` `prefs.getBoolean("enable_blur", false)`。我们默认开。但关了也卡，不是根因
- ⚠️ **"滑动后流畅"现象未解释** [?]——冷启动后切 tab 卡，在某 page 上下滑动一次后变流畅。杀掉重来又卡。不是着色器编译（blur 关也复现），不是 scrollBehavior，不是 LsposedStatus
- ⚠️ **AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错
- ⚠️ **NDK 29 下载失败** [V]——Clash TUN TLS 干扰导致 NDK 29 无法自动下载，用本地 NDK 26 替代
- ⚠️ **build-tools 36.0.0 自动下载失败** [V]——需显式指定 `buildToolsVersion = "36.1.0"`

## 6. 下一步（有序）

1. **逐层剥离 A/B 测试** — 把三个 page 的内容全删掉换成空 Box，看 janky 是否降到 0。如果是，根因在 page 内容；如果还是卡，根因在 MainScreen / NavDisplay / HorizontalPager 层级
2. **用 perfetto 抓 trace 对比** — 需要 root 或 `adb shell perfetto`，对比我们和 KernelSU 在切 tab 时的 composition/layout/draw 时间分布
3. **检查 KernelSU 是否实际跑 120hz** — 帧数 291-298 在 2s 窗口内暗示 ~150fps。KernelSU 可能通过 `Window.setFrameRate()` 或 `Layout` 请求高刷新率
4. **对比 ConfigActivity 的 setContent 块** — 逐行对比 KernelSU MainActivity.kt:114-212，检查是否有遗漏的结构性差异

## 7. 留给用户的开放问题

- KernelSU 帧数 291-298 vs 我们 114-135——在 60hz 下 2 秒应只有 ~120 帧。KernelSU 实际可能在跑 120hz+，需确认
- 是否需要对比 R8/proguard 配置？我们的 release 构建有 `isMinifyEnabled=true`
- 是否应该换 debug 构建测试（排除 R8 优化差异）？
