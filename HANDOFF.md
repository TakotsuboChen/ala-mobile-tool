# HANDOFF — 读全文再开始干活

生成时间: 2026-08-19T13:39:00+08:00 · Git HEAD: `8ff4075`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `8ff4075` (2026-08-19)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `8ff4075`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：修复响应曲线图表在平板/横屏上撑爆卡片高度**。已完成并提交，真机验证通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **修复提交** — `8ff4075`：`ConfigurePagerMiuix.kt` 12 insertions, 2 deletions。已 push（`e4c0315..8ff4075 main -> main`）。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL，仅 1 个既有 warning（`Offset.getDistance()` 阴影，非本次引入）。
- [V] **lint 通过** — `./gradlew :app:lintDebug` → 0 errors, 36 warnings, 3 hints（与改动前 35/3 持平）。
- [V] **release 构建+安装** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL in 1m 32s；`adb install -r` → Success。
- [V] **真机验证** — 用户确认"完美"：平板/横屏下响应曲线图表高度钳制到屏幕 50%、保持正方形居中，文字线宽不缩小；手机竖屏下原样满宽正方形。
- 工作区: 干净（所有改动已提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 4s (1 warning, 既有非本次)
./gradlew :app:lintDebug → BUILD SUCCESSFUL, 36 warnings, 3 hints (0 errors)
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 32s
adb install -r app-release.apk → Success
```

## 3. 决策与理由

- **`heightIn(max = screenHeightDp * 0.5f) + aspectRatio(1f)` 钳制正方形高度** [V]——`ChartCanvas` 原 `Modifier.aspectRatio(1f)` 让正方形边长 = 父容器宽度，平板/横屏宽度大 → 高度轻易超过屏幕一半，撑爆卡片。加 `heightIn(max=屏幕高度50%)` 让 `aspectRatio` 取 `min(可用宽度, 高度上限)`：窄屏仍满宽正方形，宽屏变居中较小正方形。`Box` 加 `contentAlignment = Alignment.Center` 让缩小后的正方形居中。内部文字/线宽/padding 全部保持原 dp，只改布局约束。
- **否决方案：整体 `graphicsLayer` + `layout` 等比 scale 整个 Card** [X]——第一次理解需求为"按比例缩小整个卡片"，用 `graphicsLayer` scale + `layout` 改占位实现整体缩放。用户明确否定：这会缩小文字、线宽、卡片背景，违背"内部文字线条不能缩小"的要求；且作用对象错——需求只针对油门/刹车的 `CurveEditor` 图表，不是整个含下拉选项的 Card。不要再试。
- **否决方案：`heightIn` 让图表变扁矩形（非正方形）** [X]——第二版理解需求为"图表变扁矩形、宽度仍满宽"。用户明确否定："还是保持正方形啊"。正确做法是正方形居中、边长受限，不是改长宽比。不要再试。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

- [X] **整体 `graphicsLayer` scale + `layout` 缩放整个响应曲线 Card** — 缩小了文字、线宽、卡片背景，违背"内部不能缩小"要求；且作用对象应是 `CurveEditor` 图表而非整个 Card。改为 `heightIn` 钳制正方形高度。不要再试。
- [X] **`heightIn` 把图表改成扁矩形（宽度满宽、高度受限）** — 用户要正方形，不是扁矩形。改为 `heightIn + aspectRatio(1f)` 保持正方形居中。不要再试。
- [V] **3s 轮询等 `App.xposedService` 异步绑定** — daemon 推 binder 延迟可能超过 3s，轮询超时 → INACTIVE → 弹免 Root 窗；改为事件驱动 StateFlow。不要再试。从旧 HANDOFF 搬运。
- [V] **`clearAll` 不清内存中的 `App.xposedService`** — 进程不重启时 service 残留，下次 evaluate 立即 LSPOSED；改为调 `App.clearService()`。不要再试。从旧 HANDOFF 搬运。
- [V] **CHUNK_SIZE = 256K 字符** — 527KB parcel 触发 TransactionTooLargeException。不要再试。从旧 HANDOFF 搬运。
- [V] **Thread.sleep 在 requestFreshLogs 里** — 阻塞主线程 → ANR/黑屏；改为 delay + withContext(Dispatchers.IO)。不要再试。从旧 HANDOFF 搬运。
- [V] **固定 sleep 等待广播往返** — 3 秒不够，10 秒太长；改为轮询缓存文件 lastModified。不要再试。从旧 HANDOFF 搬运。
- [V] **先设 IsUnlocked=true 再检查 has_unlocked_before()** — SetUnlocked 被跳过 → PlayerPrefs 不写入；改为正确顺序。不要再试。从旧 HANDOFF 搬运。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** — 弹窗收不到系统返回事件；改用 `ComponentActivity` 自带的。不要再试。从旧 HANDOFF 搬运。
- [V] **`mqqopensdkapi://bizAgent/qm/qr` + universal-share authKey** [X] — QQ 接住 scheme 但解析失败。不要再试。从旧 HANDOFF 搬运。
- [V] **intro hooks 只装在 15s 延迟路径** — 开场在 ~2s 触发；改为早期安装路径。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下 ContentProvider 跨进程 IPC** — 返回 `Unknown authority`。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下定向广播 setPackage** — 包不可见，系统丢弃。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下非定向广播** — flyme IntentFirewall 静默拦截。不要再试。从旧 HANDOFF 搬运。
- [V] **Remote Preferences 在 Hook 进程写日志** — `edit().commit()` 抛 `UnsupportedOperationException`。不要再试。从旧 HANDOFF 搬运。
- [V] **广播 extras 传 300KB+ 日志** — Binder transaction buffer 溢出风险。不要再试。从旧 HANDOFF 搬运。
- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定。从旧 HANDOFF 搬运。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 状态栏高度被算两次。从旧 HANDOFF 搬运。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算。从旧 HANDOFF 搬运。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp` 常量。从旧 HANDOFF 搬运。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显 — spring 动画不跟随 `fraction`。从旧 HANDOFF 搬运。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)`。从旧 HANDOFF 搬运。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——flyme `checkAllowBackgroundLocked` 对非白名单应用返回 DISABLED。用户需手动把模块加入 flyme 后台管理白名单。LSPosed 下 REQUEST_LOGS 分片广播回到模块进程可能因后台限制延迟超过 10 秒轮询超时，导出用旧缓存。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable`** [?]——不跟随 `collapsedFraction` 即时变化。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [?]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次。从旧 HANDOFF 搬运。
- ⚠️ **广播 JSON 不含 position 字段** [?]——ConfigActivity 用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。从旧 HANDOFF 搬运。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 `Text` 显示进度百分比替代。从旧 HANDOFF 搬运。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [?]——照搬 KernelSU 代码时注意 minSdk 差异。从旧 HANDOFF 搬运。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` (0x18100E8) 实为 `TweenVolume.set_volume`** [?]——主菜单音乐走 TweenVolume 驱动所以能用，但开场 `introSound` 必须用真 `AudioSource.set_volume` (0x325040C)。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛 `UnsupportedOperationException`。`openRemoteFile()` 只读模式。App→Hook 单向设计。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——`ContentResolver.call` 返回 `Unknown authority`，`setPackage` 定向广播被丢弃。用 `setComponent` 显式组件广播绕过（LogReceiver 方向）。REQUEST_LOGS 方向用 `setPackage` 到游戏包（模块进程可见游戏包）。从旧 HANDOFF 搬运+更新。
- ⚠️ **BillingHook 在 NPatch 模式下永远失败** [V]——`onPackageLoaded` 只对 GMS/WebView 触发，从不对游戏包触发。BillingBridge 类在游戏 dex 里，GMS/WebView ClassLoader 找不到。解锁靠 native hook 不靠 BillingHook。从旧 HANDOFF 搬运。

## 6. 下一步（有序）

1. **真机验证日志导出后清理无用代码** — `ConfigProvider` 里的 `pushGameLog`/`readGameLog` 方法已被广播方案替代，可清理。
2. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`(15处)、`NativeBridge.kt`(10处)、`OverlayManager.kt`(5处)、`MusicPlayer.kt`(16处)、`IntroSoundPlayer.kt`(18处)、`App.kt`(9处) 等仍用裸 `android.util.Log`，不写文件。替换后这些日志也受 logEnabled 控制。
3. **继续排查 janky 根因** — R8 映射文件对比。
4. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- LSPosed 下 REQUEST_LOGS 分片广播可能因 flyme 后台限制延迟超时，导出用旧缓存。是否需要引导用户加入 flyme 后台白名单？
- 是否计划近期发 stable release？
- V10 引擎声浪是否需要继续实现"游戏内引擎声浪"（第二阶段）？