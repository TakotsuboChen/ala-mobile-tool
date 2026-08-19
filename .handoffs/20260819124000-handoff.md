# HANDOFF — 读全文再开始干活

生成时间: 2026-08-19T02:55:00+08:00 · Git HEAD: `2419b36`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `2419b36` (2026-08-19)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `2419b36`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：日志功能 — logEnabled 开关生效 + 统一 Java/native 文件日志 + 导出分享**。已完成并提交，NPatch + LSPosed 双模式真机验证通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **日志功能提交** — `ccaa91b`：22 文件，859 insertions。新建 Logger.kt + native_log.h/.c + LogExporter.kt + LogReceiver.kt；修改 17 文件。已 push。
- [V] **持久文档更新提交** — `2419b36`：CLAUDE.md 架构图 + Files to Know 添加日志组件。已 push。
- [V] **构建通过** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL。
- [V] **NPatch 真机验证** — 导出 103KB 日志（Java 31KB + native 72KB），ShareSheet 弹出，内容完整含 hook 安装/换挡操作/V10 触发。设备 `381QYFCN22B9A`。
- [V] **LSPosed 真机验证** — 导出 160KB 日志（Java 80KB + native 80KB，各截断保留最近部分），ShareSheet 弹出，内容完整。setComponent 显式广播 + FLAG_RECEIVER_INCLUDE_BACKGROUND 绕过 flyme 包可见性限制。
- [V] **logEnabled 开关生效** — logcat 确认 `native log enabled=1`，关闭后日志文件不再增长。
- 工作区: 干净（所有改动已提交）。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 6s (UP-TO-DATE)
adb install -r app-release.apk → Success
NPatch 导出: LogExporter: exported 103400 bytes
LSPosed 导出: LogExporter: exported 160083 bytes
logcat: LogReceiver: received game logs (java=80021 native=79952)
logcat: ConfigProvider.pushGameLog failed: Unknown authority (LSPosed 下 ContentProvider 不可达)
logcat: Push game logs via remote prefs failed: Read only implementation (Hook 进程 Remote Preferences 只读)
```

## 3. 决策与理由

- **日志路径用游戏 externalFilesDir + 模块 filesDir** [V]——用户选择。游戏进程写 `/sdcard/Android/data/<pkg>/files/ala_tool.log`，模块进程写 `filesDir/ala_tool.log`，导出时合并。
- **native_log 从 unlock_hook.c 提取为共享工具** [V]——所有 6 个 native 文件复用同一套日志宏（NLOGI/NLOGW/NLOGE/NLOGD），logcat + 文件双写，受 logEnabled 控制。
- **logcat 不受 logEnabled 控制** [V]——开关只控制文件写入，logcat 始终输出。方便 adb 调试不因关日志而盲。
- **跨进程日志推送用 setComponent 显式广播** [V]——LSPosed 下 ContentProvider `Unknown authority`（包不可见）、定向广播 setPackage 被丢弃（包不可见）、非定向广播被 flyme IntentFirewall 静默拦截。`setComponent(ComponentName("tools.alamobile.mod", "tools.alamobile.mod.config.LogReceiver"))` + `FLAG_RECEIVER_INCLUDE_BACKGROUND`(0x0020) 绕过包可见性 + AOSP 隐式广播跳过后台静态 receiver 逻辑。flyme IntentFirewall 放行显式组件广播。
- **日志截断到 80KB/段** [V]——Binder transaction buffer 进程级 1MB 共享，实际崩溃在 ~500KB。两段合计 160KB 安全。截断保留最近日志（最有诊断价值）。
- **保留 xposedInterface.log() 路径** [V]——NPatch 导出日志时带 npatch/log/ 目录，保留这条路径让 NPatch 用户多一个获取渠道。
- **Remote Preferences 在 Hook 进程只读** [V]——`getRemotePreferences().edit().commit()` 抛 `Read only implementation`。libxposed API 设计：App 写 → Hook 读，单向。
- **Remote Files 在 Hook 进程只读** [V]——`XposedInterface.openRemoteFile()` javadoc 明示 "read-only mode"。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定：每条应只描述自己那条轴。
- [?] 胶囊放在 LazyColumn 首项 — 用户要求放在大标题上方的空白处。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 状态栏高度被算两次。改用 `Box` 叠加。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算 `WindowInsets.statusBars.getTop(density)`。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp` 常量。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐/渐显 — spring 动画不跟随 `fraction` 即时变化。
- [?] `fraction` 阈值分段 — spring 动画完成时机不可从 `fraction` 推断。
- [?] `translationY` 物理移出视区 + alpha 渐隐 — spring 动画滞后导致不同步。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)` 即时隐藏。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** — 弹窗收不到系统返回事件，直接 finish 退桌面。改用 `ComponentActivity` 自带的。
- [X] **`mqqopensdkapi://bizAgent/qm/qr` + universal-share authKey 作为 key** — QQ 接住 scheme 但解析失败。authKey ≠ 官方加群组件的 idkey。
- [V] **intro hooks 只装在 15s 延迟路径** — 开场在 ~2s 触发，15s 后 hook 装上时开场已过。改为早期安装路径。
- [V] **LSPosed 下 ContentProvider 跨进程 IPC（`contentResolver.call`）** — 返回 `Unknown authority tools.alamobile.mod.config`。LSPosed 不绕过包可见性，游戏进程 resolve 不到模块 provider。不要再试。
- [V] **LSPosed 下定向广播 setPackage** — 包不可见，系统丢弃定向广播。`setPackage("tools.alamobile.mod")` 从游戏进程发不达。不要再试。
- [V] **LSPosed 下非定向广播（无 setPackage）** — flyme IntentFirewall 静默拦截非系统应用间非定向广播。IntentFirewall 只 CHECK 不 ALLOW/DENY，广播消失。不要再试。
- [V] **Remote Preferences 在 Hook 进程写日志** — `edit().commit()` 抛 `UnsupportedOperationException: Read only implementation`。libxposed API 设计限制，Hook 进程只读。不要再试。
- [V] **广播 extras 传 300KB+ 日志** — Binder transaction buffer 溢出风险。截断到 80KB/段后仍可能被 flyme 拦（非定向广播时）。setComponent 方案下 160KB 合计可用。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——flyme 的 `checkAllowBackgroundLocked` 对非白名单应用返回 DISABLED，连显式广播的静态 receiver 也会被跳过。用户需手动把模块加入 flyme 后台管理白名单（手机管家 → 权限管理 → 后台管理 → 允许后台运行）。模块进程被杀且不在白名单时，LogReceiver 无法被唤醒。
- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable` 而非线性公式** [?]——不跟随 `collapsedFraction` 即时变化。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [?]——外层 `Column` 加 `Spacer` 会导致状态栏高度被算两次。
- ⚠️ **daemon 配置写入滞后于广播** [?]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。daemon 异步绑定可能延迟。
- ⚠️ **广播 JSON 不含 position 字段** [?]——ConfigActivity 不管 position，用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 `Text` 显示进度百分比替代。
- ⚠️ **lint NewApi 检查拦 minSdk 26 下的高版本 API** [?]——照搬 KernelSU 代码时注意 minSdk 差异。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` (0x18100E8) 实为 `TweenVolume.set_volume`** [?]——主菜单音乐走 TweenVolume 驱动所以能用，但开场 `introSound` 必须用真 `AudioSource.set_volume` (0x325040C)。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛 `UnsupportedOperationException`。`openRemoteFile()` 只读模式。App→Hook 单向设计。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——`ContentResolver.call` 返回 `Unknown authority`，`setPackage` 定向广播被丢弃。`<queries>` 声明在模块 manifest 里管不到游戏进程。用 `setComponent` 显式组件广播绕过。

## 6. 下一步（有序）

1. **真机验证日志导出后清理无用代码** — `ConfigProvider` 里的 `pushGameLog`/`readGameLog` 方法已被广播方案替代，可清理。`chunkString` 在 `AlaMobileModule.kt` 也未使用。
2. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`(15处)、`NativeBridge.kt`(10处)、`OverlayManager.kt`(5处)、`MusicPlayer.kt`(16处)、`IntroSoundPlayer.kt`(18处)、`App.kt`(9处) 等仍用裸 `android.util.Log`，不写文件。替换后这些日志也受 logEnabled 控制。
3. **真机验证弹窗返回修复** — SupportDialog/UpdateDialog/NonRootConfirmDialog/EULA。
4. **真机确认文案修改** — 弹窗标题、响应曲线 summary。
5. **真机验证 M50 胶囊** — 官版/共存版三态、亮暗色、滑动手势。
6. **真机验证 M49 各项** — 弹窗退出动画、检查更新、支持开发卡片。
7. **真机验证 M47 EULA 启动门控**。
8. **验证 position 合并修复** — 切双踏板再切回单踏板，位置/大小是否保持。
9. **继续排查 janky 根因** — R8 映射文件对比。
10. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- 日志截断到 80KB/段是否够用？完整日志在游戏 externalFilesDir 里存在，但导出的 txt 只有最近 80KB。
- 是否需要引导 flyme 用户把模块加入后台白名单？（否则模块进程被杀后 LogReceiver 收不到广播）
- 弹窗返回修复真机表现是否满意？
- 文案修改（弹窗标题、两条响应曲线 summary）真机表现是否满意？
- M49 各项真机表现是否满意？
- M47 EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 是否计划近期发 stable release？
- V10 引擎声浪是否需要继续实现"游戏内引擎声浪"（第二阶段）？