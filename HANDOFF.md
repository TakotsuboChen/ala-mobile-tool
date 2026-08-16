# HANDOFF — 读全文再开始干活

生成时间: 2026-08-17T00:43:00+08:00 · Git HEAD: `73c1ddb`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `refactor/ui-kernelsu-clone` @ `73c1ddb` (2026-08-17)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `73c1ddb`；变了说明快照可能过期
- 先读: `CLAUDE.md` + 本文件 + `references/KernelSU/manager/` 全树

## 1. 当前目标

**完全照搬 KernelSU Manager UI，达到 KernelSU 同等流畅度（janky < 1%）。**

当前状态：janky 42% vs KernelSU 0.17%，差距 ~250 倍。**本会话已搁置**，改动已合并到 main，待以后修复。

## 2. 已验证状态 — 工作实际停在哪

- [V] **UI 对齐改动已提交并 push** — `73c1ddb`（contentPadding/theme/manifest/rememberContentReady/MiuixPopupHost 移除/jniLibs useLegacyPackaging）
- [V] **构建通过** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL (exit 0)
- [V] **工作区干净** — `git status --short` 无未提交改动（HANDOFF.md 已归档到 `.handoffs/`）
- [V] **分支已合并到 main** — 用户指示"合并到 main，待以后修复"

### 最小复现实验（本会话关键发现）

| 配置 | Janky% | P50 | Frames |
|---|---|---|---|
| 纯 Compose Text（无 miuix，release） | **2.80%** | 7ms | 1428 |
| miuix SwitchPreference ×30（release） | 22-25% | 13-14ms | 609-645 |
| 禁用 LSPosed binding + miuix | 22.33% | 13ms | 645 |
| 完整 3 page（切 tab） | 42% | 20ms | 1032 |
| KernelSU 垂直滑动（同条件） | **0.10%** | 9ms | 1950 |

**根因定位**：纯 Compose Text 只 2.8% → 排除 Compose 运行时/LSPosed 环境。加 miuix SwitchPreference 跳到 22% → **miuix SwitchPreference 是主要开销源**。但 KSU 用同样的 miuix 0.9.3 只 0.10% → 差异不在 miuix 版本。

## 3. 决策与理由

- **本会话搁置** [V]——用户明确"太花时间了，到此为止搁置，修改过的不影响功能的可以保留，合并到 main，待以后修复了"
- **保留不影响功能的改动** [V]——contentPadding 对齐、theme/manifest 对齐、rememberContentReady 恢复、MiuixPopupHost 移除、jniLibs useLegacyPackaging。这些是纯对齐 KSU 的改动，不影响功能
- **恢复 ConfigActivity 到 committed 版本** [V]——最小复现测试用的临时 ConfigActivity 已 git checkout 还原

## 4. 失败的尝试 — 不要再试

- [X] **手写 SwitchRow/SliderRow → miuix preference 组件** — 换了仍 22-38% janky。RenderNode 数量不是根因
- [X] **关 blur（enable_blur=false）** — 仍 15-25% janky。blur 不是根因
- [X] **移除 rememberContentReady 门控** — 直接全预组仍 22-38% janky（本会话已恢复，冷启动修复）
- [X] **ModConfig.read 改 IO 线程异步** — 仍 22-38% janky
- [X] **LsposedStatus.evaluate 改完全异步** — 仍 22-38% janky
- [X] **M37/M38 的"切页掉帧已解决"是假阳性** — 用户每次测到一次流畅就 /handoff 导致误判
- [X] **`Modifier.semantics(mergeDescendants = true) {}`** — 不影响 janky，已回退
- [X] **移除 `LocalNavigationEventDispatcherOwner`** — 仍 45% janky，已恢复
- [X] **debug 构建** — 36% janky（比 release 更差），排除 R8 差异
- [X] **OverlayDropdownPreference → ArrowPreference** — 不影响，已回退
- [X] **注释掉 AnimatedVisibility** — 不影响，已回退
- [X] **beyondViewportPageCount = 0 vs 2** — 不影响（24% vs 22%）
- [X] **App 作为 ViewModelStoreOwner** — 不影响（41% vs 44%）
- [X] **纯 Compose Text（不用 miuix）** — 2.8% janky（**关键对照**：证明 miuix 是开销源）
- [X] **禁用 LSPosed service binding** — 仍 22% janky，排除 LSPosed 干扰
- [X] **useLegacyPackaging / isShrinkResources** — 不影响 janky
- [X] **MiuixTheme controller vs colors 版本** — 不影响 janky
- [X] **Theme.Material vs Theme.Material.Light** — 不影响 janky
- [X] **userScrollEnabled = false** — 仍 43% janky

## 5. 已知坑

- ⚠️ **miuix SwitchPreference 在我们的 app 中 22% janky，KSU 同版本 0.10%** [V]——两者用完全相同的 miuix 0.9.3 + compose-ui 1.12.0-beta01 + 相同设备，但 janky 差 220 倍。**唯一可能的剩余差异：R8 优化结果不同**（KSU dex=5.2MB vs 我们 2MB）
- ⚠️ **KernelSU blur 默认关** [V]——`SettingsRepositoryImpl.kt:69` `prefs.getBoolean("enable_blur", false)`。我们默认开。但关了也卡，不是根因
- ⚠️ **AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错
- ⚠️ **NDK 29 下载失败** [V]——Clash TUN TLS 干扰导致 NDK 29 无法自动下载，用本地 NDK 26 替代
- ⚠️ **build-tools 36.0.0 自动下载失败** [V]——需显式指定 `buildToolsVersion = "36.1.0"`

## 6. 下一步（有序）

1. **对比 R8 优化结果** — 用 R8 的 mapping 文件对比两个 APK 中 miuix 类是否被同样优化。KSU dex=5.2MB vs 我们 2MB，可能是 R8 内联/优化差异
2. **用 Android Studio Layout Inspector** 对比 semantics 树节点数 — 最直接的验证方法
3. **创建全新非-LSPosed 项目** 验证是否是 LSPosed 模块 APK 的构建配置导致
4. **检查 `useLegacyPackaging` 和 `isShrinkResources` 差异** 是否影响运行时

## 7. 留给用户的开放问题

- 为什么用完全相同的 miuix 0.9.3 + compose-ui 1.12.0-beta01 + 相同设备，我们的 miuix SwitchPreference 22% janky 而 KSU 0.10%？
- 是否 R8 优化差异（KSU dex 5.2MB vs 我们 2MB）导致 miuix 类被不同方式优化？
- 是否 LSPosed 模块 APK 的构建配置（libxposed service consumer proguard 规则）影响了 Compose 类优化？
