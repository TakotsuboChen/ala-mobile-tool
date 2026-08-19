# HANDOFF — 读全文再开始干活

生成时间: 2026-08-19T13:16:00+08:00 · Git HEAD: `3ced7d4`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `3ced7d4` (2026-08-19)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `3ced7d4`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：LSPosed 激活检测改为事件驱动 + CI lint WrongConstant 修复**。已完成并提交，真机验证通过，CI 全绿。

## 2. 已验证状态 — 工作实际停在哪

- [V] **CI 修复提交** — `5d49825`：LogExporter.kt:61 + LogReceiver.kt:101 的 `addFlags(0x0020)` 替换为 `Intent.FLAG_INCLUDE_STOPPED_PACKAGES`。lint 通过（0 errors）。
- [V] **激活检测修复提交** — `3ced7d4`：App.kt + LsposedStatus.kt + OverviewPagerMiuix.kt，74 insertions，1 deletion。已 push。
- [V] **CI 全绿** — `gh run watch 32218362741` → build job 5m11s，所有 step ✓（lint、assembleRelease、rename、upload artifact）。
- [V] **真机验证** — 用户确认"多种组合尝试都快速稳定"：清除激活标记→重启→LSPosed 开着时自动检测到 LSPOSED；关掉 LSPosed→重启→重新弹免 Root 窗。
- [V] **release 构建+安装** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL；`adb install -r` → Success。
- 工作区: 干净（所有改动已提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:lintDebug → BUILD SUCCESSFUL (0 errors, 35 warnings, 3 hints)
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 28s
adb install -r app-release.apk → Success
gh run watch 32218362741 → ✓ build in 5m11s (all steps passed)
```

## 3. 决策与理由

- **事件驱动取代固定时长轮询** [V]——3s 轮询等 `App.xposedService` 异步绑定有固定超时窗口，daemon 推 binder 延迟超过 3s 时轮询已结束 → INACTIVE → 弹窗。改用 `App.lsposedServiceBound: StateFlow<Boolean>`，`onServiceBind` 时 emit true，UI 用 `collectAsState` 订阅，service 无论多晚绑上都触发重新 evaluate。否决方案：延长轮询到 10s——治标不治本，仍有超时可能且阻塞更久。
- **`clearAll` 新增 `App.clearService()`** [V]——进程不重启时 `xposedService` 仍在内存，下次 evaluate 立即命中 LSPOSED，"清除标记"形同虚设。清掉内存引用后 evaluate 才走完整检测流程。不调 `onServiceDied`：那会向框架注销死亡回调，副作用超出"清激活标记"语义。
- **弹窗点"是"后二次检测改 `awaitService=true`** [V]——若 LSPosed service 在 3s 窗口内绑上，优先返回 LSPOSED 并清掉刚写的 nonroot flag，体现"LSPosed 状态高于一切"语义。
- **`0x0020` → `Intent.FLAG_INCLUDE_STOPPED_PACKAGES`** [V]——`0x0020` = 32 = `FLAG_INCLUDE_STOPPED_PACKAGES`，注释误标为 `FLAG_RECEIVER_INCLUDE_BACKGROUND`（实际值 `0x01000000`）。裸 hex 触发 lint WrongConstant，改用 SDK 常量通过。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

- [V] **3s 轮询等 `App.xposedService` 异步绑定** — daemon 推 binder 延迟可能超过 3s，轮询超时 → INACTIVE → 弹免 Root 窗；用户点"是"后 service 才绑上 → LSPOSED 覆盖 NONROOT，体验混乱。改为事件驱动 StateFlow。不要再试。
- [V] **`clearAll` 不清内存中的 `App.xposedService`** — 进程不重启时 service 残留，下次 evaluate 立即 LSPOSED，"清除标记"无效。改为调 `App.clearService()`。不要再试。
- [V] **CHUNK_SIZE = 256K 字符** — 527KB parcel 触发 TransactionTooLargeException，模块进程被系统 kill → 闪退。不要再试。从旧 HANDOFF 搬运。
- [V] **Thread.sleep 在 requestFreshLogs 里** — 阻塞主线程 → ANR/黑屏。改为 delay + withContext(Dispatchers.IO)。不要再试。从旧 HANDOFF 搬运。
- [V] **固定 sleep 等待广播往返** — 3 秒不够（LSPosed 下 flyme 后台延迟），10 秒太长。改为轮询缓存文件 lastModified。不要再试。从旧 HANDOFF 搬运。
- [V] **先设 IsUnlocked=true 再检查 has_unlocked_before()** — SetUnlocked 被跳过 → PlayerPrefs 不写入 → 切换框架后解锁失效。不要再试。从旧 HANDOFF 搬运。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** — 弹窗收不到系统返回事件，直接 finish 退桌面。改用 `ComponentActivity` 自带的。不要再试。从旧 HANDOFF 搬运。
- [V] **`mqqopensdkapi://bizAgent/qm/qr` + universal-share authKey** [X] — QQ 接住 scheme 但解析失败。authKey ≠ 官方加群组件的 idkey。不要再试。从旧 HANDOFF 搬运。
- [V] **intro hooks 只装在 15s 延迟路径** — 开场在 ~2s 触发，15s 后 hook 装上时开场已过。改为早期安装路径。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下 ContentProvider 跨进程 IPC** — 返回 `Unknown authority`。LSPosed 不绕过包可见性。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下定向广播 setPackage** — 包不可见，系统丢弃定向广播。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下非定向广播** — flyme IntentFirewall 静默拦截。不要再试。从旧 HANDOFF 搬运。
- [V] **Remote Preferences 在 Hook 进程写日志** — `edit().commit()` 抛 `UnsupportedOperationException: Read only implementation`。不要再试。从旧 HANDOFF 搬运。
- [V] **广播 extras 传 300KB+ 日志** — Binder transaction buffer 溢出风险。不要再试。从旧 HANDOFF 搬运。
- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定。从旧 HANDOFF 搬运。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 状态栏高度被算两次。从旧 HANDOFF 搬运。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算。从旧 HANDOFF 搬运。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp` 常量。从旧 HANDOFF 搬运。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显 — spring 动画不跟随 `fraction`。从旧 HANDOFF 搬运。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)`。从旧 HANDOFF 搬运。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——flyme `checkAllowBackgroundLocked` 对非白名单应用返回 DISABLED。用户需手动把模块加入 flyme 后台管理白名单。LSPosed 下 REQUEST_LOGS 分片广播回到模块进程可能因 flyme 后台限制延迟超过 10 秒轮询超时，导出用旧缓存。从旧 HANDOFF 搬运。
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