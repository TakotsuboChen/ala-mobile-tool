# HANDOFF — 读全文再开始干活

生成时间: 2026-08-19T15:50:43+08:00 · Git HEAD: `a86dce5`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `a86dce5` (2026-08-19)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `a86dce5`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：NPatch 激活确认流程收敛到 NPatch 专属**。已完成并提交、release 真机安装验证通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **工作切片** — `203754b`：NPatch 包名检测 + 激活确认弹窗门控 + 文案收窄。3 files changed, 82 insertions, 24 deletions。已 push。
- [V] **持久文档切片** — `a86dce5`：README 同步 NPatch 弹窗门控行为 + 配置页分类名。已 push。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL in 13s，仅 1 个既有 warning（`Offset.getDistance()` 阴影，非本次引入）。
- [V] **release 构建+安装** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL in 2m 5s；`adb install -r` → Success。
- 工作区: 干净（仅未提交的本次 handoff 归档文件）。
- 本次工作内容：
  - `LsposedStatus` 新增 `NPATCH_PKG = "top.nkbe.npatch"` 常量 + `isNpatchInstalled(context)` 静默检测（照搬 `checkGameVersion` 模式，TIRAMISU 分支 + 双 catch）。
  - `ActivationCard` 新增 `npatchInstalled` 状态（`LaunchedEffect` IO 线程检测）；三处弹窗触发点（自动弹、EULA 补弹、点击卡片）都要求 NPatch 已安装。
  - 未安装时点击卡片 Toast"未检测到 LSPosed 或 NPatch 框架，请确认是否已安装"。
  - `NonRootConfirmDialog` 标题改"NPatch 作用域确认"（居中，`OverlayDialog` title 参数），正文左对齐"您是否已经在 NPatch 管理器中对游戏开启了本模块的作用域？"，排版照搬 `EulaDialog`。
  - 文案：激活卡片 NONROOT 状态 →"模块已通过 NPatch 免 Root 框架加载"；配置页 Section 1 →"游戏原生特性控制"；线性踏板 summary →"悬浮窗踏板覆盖游戏输入"。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 13s (1 warning, 既有非本次)
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 2m 5s
adb install -r app-release.apk → Success
```

## 3. 决策与理由

- **NPatch 包名检测门控弹窗** [V]——未安装 NPatch 时弹"是否用了免 Root 框架"纯属误导。`<queries>` 早已声明 `top.nkbe.npatch`（NPatch 配置同步用），检测零成本。未安装点击卡片走 Toast。
- **弹窗只问 NPatch、收敛为"作用域"问法** [V]——项目实际分发路径只有 NPatch 本地模式（LSPatch/FPA 已无用户）；"是否开启作用域"比"是否安装框架"更精准——装了框架没开作用域模块同样不生效，原问法会误导用户确认。
- **弹窗排版照搬 EulaDialog** [V]——`OverlayDialog` 的 `title` 参数天然居中，正文放 `content` 里左对齐，与用户协议弹窗一致，无自创布局。
- **保留 `LsposedStatus.Status.NONROOT` 枚举** [V]——未合并进 INACTIVE：`isActivated` 语义依赖"非未激活"，且状态区分有助于未来 NPatch 直接判定。
- **否决方案：不检测包名、维持原弹窗** [X]——用户明确要求加检测。不要再试。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

- [V] **本次：`isNpatchInstalled` 用 `getInstalledPackages` 全量遍历** — 慢且触发包可见性风险；改 `getPackageInfo(NPATCH_PKG)` 单包精确查询（`<queries>` 已声明）。不要再试。
- [X] **整体 `graphicsLayer` scale + `layout` 缩放整个响应曲线 Card** — 缩小了文字、线宽、卡片背景。改为 `heightIn` 钳制正方形高度。从旧 HANDOFF 搬运。
- [X] **`heightIn` 把图表改成扁矩形** — 用户要正方形。改为 `heightIn + aspectRatio(1f)`。从旧 HANDOFF 搬运。
- [V] **3s 轮询等 `App.xposedService` 异步绑定** — 超时 → INACTIVE → 误弹免 Root 窗；改为事件驱动 StateFlow。从旧 HANDOFF 搬运。
- [V] **`clearAll` 不清内存中的 `App.xposedService`** — 进程不重启时 service 残留；改为调 `App.clearService()`。从旧 HANDOFF 搬运。
- [V] **CHUNK_SIZE = 256K 字符** — TransactionTooLargeException。从旧 HANDOFF 搬运。
- [V] **Thread.sleep 在 requestFreshLogs 里** — ANR/黑屏；改为 delay + IO。从旧 HANDOFF 搬运。
- [V] **固定 sleep 等待广播往返** — 改为轮询缓存文件 lastModified。从旧 HANDOFF 搬运。
- [V] **先设 IsUnlocked=true 再检查 has_unlocked_before()** — SetUnlocked 被跳过；改为正确顺序。从旧 HANDOFF 搬运。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** — 弹窗收不到返回键；用 ComponentActivity 自带。从旧 HANDOFF 搬运。
- [V] **`mqqopensdkapi://...` + universal-share authKey** — QQ 接住 scheme 但解析失败。从旧 HANDOFF 搬运。
- [V] **intro hooks 只装在 15s 延迟路径** — 开场 ~2s 触发；改早期安装路径。从旧 HANDOFF 搬运。
- [V] **LSPosed 下 ContentProvider 跨进程 IPC** — `Unknown authority`。从旧 HANDOFF 搬运。
- [V] **LSPosed 下定向广播 setPackage** — 包不可见丢弃。从旧 HANDOFF 搬运。
- [V] **LSPosed 下非定向广播** — flyme IntentFirewall 拦截。从旧 HANDOFF 搬运。
- [V] **Remote Preferences 在 Hook 进程写日志** — `commit()` 抛 `UnsupportedOperationException`。从旧 HANDOFF 搬运。
- [V] **广播 extras 传 300KB+ 日志** — Binder 溢出风险。从旧 HANDOFF 搬运。
- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定。从旧 HANDOFF 搬运。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 高度被算两次。从旧 HANDOFF 搬运。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算。从旧 HANDOFF 搬运。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp`。从旧 HANDOFF 搬运。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐 — spring 不跟随 fraction。从旧 HANDOFF 搬运。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)`。从旧 HANDOFF 搬运。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。用户需手动加白。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable`** [?]——不跟随 `collapsedFraction` 即时变化。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [?]——外层加 Spacer 会重复计算。从旧 HANDOFF 搬运。
- ⚠️ **广播 JSON 不含 position 字段** [?]——解析后必须从本地 externalFilesDir 合并 position。从旧 HANDOFF 搬运。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 Text 显示百分比替代。从旧 HANDOFF 搬运。
- ⚠️ **lint NewApi 拦 minSdk 26 下的高版本 API** [?]——照搬 KernelSU 时注意 minSdk 差异。从旧 HANDOFF 搬运。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` 实为 `TweenVolume.set_volume`** [?]——introSound 必须用真 `AudioSource.set_volume` (0x325040C)。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛异常。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——`ContentResolver.call` 返回 Unknown authority；用 setComponent 显式组件广播绕过。从旧 HANDOFF 搬运+更新。
- ⚠️ **BillingHook 在 NPatch 模式下永远失败** [V]——`onPackageLoaded` 只对 GMS/WebView 触发；解锁靠 native hook。从旧 HANDOFF 搬运。

## 6. 下一步（有序）

1. **真机验证 NPatch 场景** — 本次只在 release 真机装了模块，未验证"未装 NPatch 点卡片弹 Toast / 装了 NPatch 自动弹窗"两条路径；下一会话有真机时走一遍确认。
2. **真机验证日志导出后清理无用代码** — `ConfigProvider` 里的 `pushGameLog`/`readGameLog` 已被广播方案替代，可清理。
3. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`、`NativeBridge.kt`、`OverlayManager.kt`、`MusicPlayer.kt`、`IntroSoundPlayer.kt`、`App.kt` 等仍用裸 log。
4. **继续排查 janky 根因** — R8 映射文件对比。
5. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- NPatch 未安装时点击激活卡片走 Toast 提示"未检测到 LSPosed 或 NPatch 框架"——是否同时引导用户跳转 NPatch 安装页/文档？
- 是否计划近期发 stable release？
- V10 引擎声浪是否需要继续实现"游戏内引擎声浪"（第二阶段）？
