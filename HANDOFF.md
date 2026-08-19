# HANDOFF — 读全文再开始干活

生成时间: 2026-08-19T12:45:00+08:00 · Git HEAD: `f4ab25b`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `f4ab25b` (2026-08-19)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f4ab25b`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：完整日志传输（不截断）+ 解锁 PlayerPrefs 持久化修复 + 导出体验修复**。已完成并提交，三条路径真机验证通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **工作提交** — `f4ab25b`：6 文件，286 insertions，116 deletions。已 push。
- [V] **构建通过** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL (UP-TO-DATE)。
- [V] **日志完整传输** — 三份导出日志（626KB / 375KB / 270KB），0 截断标记。分片广播 128K 字符/片，接收端拼接完整日志。
- [V] **REQUEST_LOGS 机制** — 导出前发广播触发游戏进程推送最新日志，轮询等待缓存更新（delay 不阻塞主线程）。logcat 确认广播 500ms 往返。
- [V] **解锁修复三路径验证** — LSPosed+官版 pid=18212 `12:38:02` SetUnlocked called successfully；LSPosed+共存版 pid=6487 `12:24:20` SetUnlocked called successfully；NPatch+共存版 pid=29163 `12:12:04` SetUnlocked called successfully。
- [V] **UI 不卡顿** — 用户确认导出流畅，无黑屏。withContext(Dispatchers.IO) + delay 挂起。
- [V] **TransactionTooLargeException 修复** — CHUNK_SIZE 从 256K 降到 128K 字符（~256KB parcel），不再闪退。
- 工作区: 干净（所有改动已提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 3s (UP-TO-DATE)
adb install -r app-release.apk → Success
导出日志 626KB (LSPosed+官版) — 0 截断标记，含 hook_awake + SetUnlocked
导出日志 375KB (NPatch+共存版) — 0 截断标记，含 hook_awake + SetUnlocked
logcat: LogReceiver: assembled java/native log → game_java.log/game_native.log
logcat: ConfigReceiver: REQUEST_LOGS received — pushing fresh logs
```

## 3. 决策与理由

- **分片广播 CHUNK_SIZE = 128K 字符** [V]——256K 字符经 UTF-16 编码后 parcel 达 527KB，超过 Binder 限制触发 TransactionTooLargeException 闪退。128K 字符 → ~256KB parcel，留足余量。
- **requestFreshLogs 用轮询而非固定 sleep** [V]——分片广播延迟不固定（flyme 后台调度），固定 sleep 太短用旧缓存、太长卡 UI。轮询每 200ms 检查缓存文件 lastModified，更新后立即继续，最多 10 秒超时。
- **requestFreshLogs 用 delay 而非 Thread.sleep** [V]——Thread.sleep 阻塞主线程导致 ANR/黑屏。改为 suspend 函数 + delay，配合 withContext(Dispatchers.IO) 在 IO 线程执行。
- **解锁修复：先调 SetUnlocked(true) 再设字段** [V]——之前先设 IsUnlocked=true 再检查 has_unlocked_before() → true → 跳过 SetUnlocked → PlayerPrefs 没被写入。切换框架时 PlayerPrefs 丢失但标记文件残留 → 解锁失效。修复后先调 SetUnlocked(true)（不预设字段），让 SetUnlocked 内部 if(!IsUnlocked) 决定是否写 PlayerPrefs。has_unlocked_before() 只控制 OnAlreadyOwned 调用。
- **REQUEST_LOGS 广播用 setPackage 而非 setComponent** [V]——ConfigReceiver 是动态注册的（运行在游戏进程），setComponent 需要静态注册的组件名。setPackage 定向到游戏包，模块进程通过 `<queries>` 声明可见游戏包。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

- [V] **CHUNK_SIZE = 256K 字符** — 527KB parcel 触发 TransactionTooLargeException，模块进程被系统 kill → 闪退。不要再试。
- [V] **Thread.sleep 在 requestFreshLogs 里** — 阻塞主线程 → ANR/黑屏。改为 delay + withContext(Dispatchers.IO)。不要再试。
- [V] **固定 sleep 等待广播往返** — 3 秒不够（LSPosed 下 flyme 后台延迟），10 秒太长。改为轮询缓存文件 lastModified。不要再试。
- [V] **先设 IsUnlocked=true 再检查 has_unlocked_before()** — SetUnlocked 被跳过 → PlayerPrefs 不写入 → 切换框架后解锁失效。不要再试。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** [V] — 弹窗收不到系统返回事件，直接 finish 退桌面。改用 `ComponentActivity` 自带的。从旧 HANDOFF 搬运。
- [V] **`mqqopensdkapi://bizAgent/qm/qr` + universal-share authKey** [X] — QQ 接住 scheme 但解析失败。authKey ≠ 官方加群组件的 idkey。从旧 HANDOFF 搬运。
- [V] **intro hooks 只装在 15s 延迟路径** [V] — 开场在 ~2s 触发，15s 后 hook 装上时开场已过。改为早期安装路径。从旧 HANDOFF 搬运。
- [V] **LSPosed 下 ContentProvider 跨进程 IPC** [V] — 返回 `Unknown authority`。LSPosed 不绕过包可见性。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下定向广播 setPackage** [V] — 包不可见，系统丢弃定向广播。不要再试。从旧 HANDOFF 搬运。
- [V] **LSPosed 下非定向广播** [V] — flyme IntentFirewall 静默拦截。不要再试。从旧 HANDOFF 搬运。
- [V] **Remote Preferences 在 Hook 进程写日志** [V] — `edit().commit()` 抛 `UnsupportedOperationException: Read only implementation`。不要再试。从旧 HANDOFF 搬运。
- [V] **广播 extras 传 300KB+ 日志** [V] — Binder transaction buffer 溢出风险。从旧 HANDOFF 搬运。
- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定。从旧 HANDOFF 搬运。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 状态栏高度被算两次。从旧 HANDOFF 搬运。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算。从旧 HANDOFF 搬运。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp` 常量。从旧 HANDOFF 搬运。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显 — spring 动画不跟随 `fraction`。从旧 HANDOFF 搬运。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)`。从旧 HANDOFF 搬运。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——flyme `checkAllowBackgroundLocked` 对非白名单应用返回 DISABLED。用户需手动把模块加入 flyme 后台管理白名单（手机管家 → 权限管理 → 后台管理 → 允许后台运行）。模块进程被杀且不在白名单时，LogReceiver 无法被唤醒。LSPosed 下 REQUEST_LOGS 分片广播回到模块进程可能因 flyme 后台限制延迟超过 10 秒轮询超时，导出用旧缓存。
- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable`** [?]——不跟随 `collapsedFraction` 即时变化。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [?]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次。从旧 HANDOFF 搬运。
- ⚠️ **广播 JSON 不含 position 字段** [?]——ConfigActivity 不管 position，用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。从旧 HANDOFF 搬运。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 `Text` 显示进度百分比替代。从旧 HANDOFF 搬运。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [?]——照搬 KernelSU 代码时注意 minSdk 差异。从旧 HANDOFF 搬运。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` (0x18100E8) 实为 `TweenVolume.set_volume`** [?]——主菜单音乐走 TweenVolume 驱动所以能用，但开场 `introSound` 必须用真 `AudioSource.set_volume` (0x325040C)。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛 `UnsupportedOperationException`。`openRemoteFile()` 只读模式。App→Hook 单向设计。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——`ContentResolver.call` 返回 `Unknown authority`，`setPackage` 定向广播被丢弃。用 `setComponent` 显式组件广播绕过（LogReceiver 方向）。REQUEST_LOGS 方向用 `setPackage` 到游戏包（模块进程可见游戏包）。从旧 HANDOFF 搬运+更新。
- ⚠️ **BillingHook 在 NPatch 模式下永远失败** [V]——`onPackageLoaded` 只对 GMS/WebView 触发，从不对游戏包触发。BillingBridge 类在游戏 dex 里，GMS/WebView ClassLoader 找不到。解锁靠 native hook 不靠 BillingHook。

## 6. 下一步（有序）

1. **真机验证日志导出后清理无用代码** — `ConfigProvider` 里的 `pushGameLog`/`readGameLog` 方法已被广播方案替代，可清理。
2. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`(15处)、`NativeBridge.kt`(10处)、`OverlayManager.kt`(5处)、`MusicPlayer.kt`(16处)、`IntroSoundPlayer.kt`(18处)、`App.kt`(9处) 等仍用裸 `android.util.Log`，不写文件。替换后这些日志也受 logEnabled 控制。
3. **继续排查 janky 根因** — R8 映射文件对比。
4. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- LSPosed 下 REQUEST_LOGS 分片广播可能因 flyme 后台限制延迟超时，导出用旧缓存。是否需要引导用户加入 flyme 后台白名单？
- 是否计划近期发 stable release？
- V10 引擎声浪是否需要继续实现"游戏内引擎声浪"（第二阶段）？