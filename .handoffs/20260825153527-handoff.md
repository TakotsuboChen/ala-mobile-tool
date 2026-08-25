# HANDOFF — 读全文再开始干活

生成时间: 2026-08-24T04:33:00+00:00 · Git HEAD: `b8f88f8`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `b8f88f8` (2026-08-24)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `b8f88f8`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：LSPosed 激活检测稳定性大修**。已完成：参照 AdClose/inxlocker/Phoenix 三个参考项目的激活检测模式，引入 `ConnectionState` 三态 + `onServiceDied` service 身份检查 + 去轮询。真机验证通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **`ConnectionState` 三态密封接口** — `App.kt`：`Connecting` / `Connected(service)` / `Disconnected`，初始值 `Connecting`（参照 AdClose）。`b8f88f8` 已 push。
- [V] **`onServiceDied` 检查 service 身份** — `App.kt`：`currentState.service === deadService` 才设 Disconnected。修复 NPatch binder 后台死亡误触 Disconnected。已 push。
- [V] **`onServiceBind` LSPosed 优先** — `App.kt`：已有 LSPosed → 忽略新 binder；当前 NPatch + 新来 LSPosed → 升级覆盖；NPatch→NPatch → 忽略。已 push。
- [V] **`evaluate()` 去轮询保 service 检查** — `LsposedStatus.kt`：去掉 `awaitService` 参数和 3s `Thread.sleep` 轮询；保留路径 2（`App.xposedService` + `isLsposedService`）。已 push。
- [V] **弹窗逻辑恢复顺序执行** — `OverviewPagerMiuix.kt`：`LaunchedEffect(Unit)` 里先设 `npatchInstalled` → 等 `connectionState` 稳定（`withTimeoutOrNull(2000) { first { !is Connecting } }`）→ `evaluate()` → 弹窗。`LaunchedEffect(connectionState)` 用 `firstCheckDone` 守护跳过首次。已 push。
- [V] **onResume 重新检测** — `OverviewPagerMiuix.kt`：`DisposableEffect` 监听 `ON_RESUME`，调 `evaluate()` 刷新 status。已 push。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL in 14s。
- [V] **release 构建通过** — `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL in 1m 34s。
- [V] **真机安装 + 验证** — `adb install -r` → Success；用户确认"ok 了"（切后台不掉激活、弹窗正常）。
- 工作区: 干净（仅未提交的本次 handoff 归档文件）。

### 测试/build 输出
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 14s
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 34s
adb install -r app-release.apk → Success
用户验证: 切后台不掉激活、关 LSPosed 弹窗正常、开 LSPosed 显示已激活
```

## 3. 决策与理由

- **`ConnectionState` 三态替代 `StateFlow<Boolean>`** [V]——参照 AdClose `ServiceManager.ConnectionState`。`Connected(service)` 存 service 实例支持 `onServiceDied` 身份比较。初始值 `Connecting`（非 `Disconnected`）让 UI 首次组合直接看到"检测中"。
- **`onServiceDied` 检查 `deadService === currentService`** [V]——**本次最关键修复**。`bindNpatchRemoteService` 引入第二个 service binder（NPatch），NPatch 管理器进程后台被杀时 binder 死亡触发 `onServiceDied`，无条件 Disconnected 导致即使 LSPosed 还活着也掉激活。检查身份后只有当前 service 死了才 Disconnected。
- **`onServiceBind` LSPosed 优先于 NPatch** [V]——`bindNpatchRemoteService` 在 `doServiceBinding` 里同步调，NPatch binder 可能先于 LSPosed daemon 到达。LSPosed 后到时需覆盖 NPatch（升级），但 NPatch 后到时不能覆盖 LSPosed。
- **`evaluate()` 去轮询但保留 service 检查路径** [V]——去掉 `awaitService` 参数和 3s `Thread.sleep` 轮询（不稳定根源），但保留路径 2（`App.xposedService` + `isLsposedService`），否则 `onResume` 调 `evaluate()` 恒返回 INACTIVE → 切后台一秒就掉。
- **NPatch service 绑定不算激活** [V]——NPatch service 只是配置读写通道（`bindNpatchRemoteService` 拿可写 binder 写 remote prefs），激活检测只认 `frameworkName == "LSPosed"`。NPatch 走 Non-root 手动确认（弹窗点"是"写 `nonroot_confirmed` flag）。
- **弹窗逻辑保留在 `LaunchedEffect(Unit)` 顺序执行** [V]——`npatchInstalled` 在弹窗前已设置，无竞态。否决方案：移到 `LaunchedEffect(connectionState)` 并行执行 [X]——`npatchInstalled` 可能未就绪导致不弹窗。
- **超时用协程 `scope.launch { delay }` 而非 `Handler.postDelayed`** [V]——协程在 `Dispatchers.Default` 线程，主线程阻塞时超时仍准时触发。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

**本次新增：**
- [V] **`onServiceDied` 不检查 service 身份** → NPatch binder 后台死亡误触 Disconnected → LSPosed 还活着也掉激活。改为检查 `===`。不要再试。
- [V] **去掉 `evaluate()` 的 service 检查路径** → `onResume` 调 evaluate 恒返回 INACTIVE（模块进程 `hasModuleLoadedFlag` 恒 false）→ 切后台一秒就掉。恢复路径 2。不要再试。
- [V] **`onServiceBind` 不区分框架都算已激活** → LSPosed 开着但 NPatch binder 先到 → 显示"NPatch 已激活"。改为 LSPosed 优先。不要再试。
- [V] **弹窗移到 `LaunchedEffect(connectionState)` 并行执行** → `npatchInstalled` 竞态未就绪 → 不弹窗。恢复 `LaunchedEffect(Unit)` 顺序执行。不要再试。
- [V] **`hasShownDialog` 只弹一次** → 关 LSPosed 后再打开不弹窗。去掉 `hasShownDialog`，每次 Disconnected + INACTIVE 都弹。不要再试。

**从旧 HANDOFF 搬运（详见 `.handoffs/20260824043256-handoff.md`）：**
- [V] `kkgithub.com` 用于 release asset 下载 → 404。改用 `gh-proxy.com`。
- [V] `kkgithub.com/api/v3/` → 404。改用 `gh-proxy.com` 代理 `api.github.com`。
- [V] OkHttp 不显式配置 `ProxySelector` → TUN/HTTP 代理模式下绕过系统代理。
- [V] `mirror.ghproxy.com` 代理 → DNS 失败/连接重置。可用：`gh-proxy.com`/`ghproxy.net`/`ghproxy.com`。
- [V] `isNpatchInstalled` 用 `getInstalledPackages` 全量遍历 → 改 `getPackageInfo` 单包查询。
- [X] 整体 `graphicsLayer` scale + `layout` 缩放整个响应曲线 Card → 改 `heightIn`。
- [X] `heightIn` 把图表改成扁矩形 → 改 `heightIn + aspectRatio(1f)`。
- [V] 3s 轮询等 `App.xposedService` 异步绑定 → 超时 → INACTIVE → 误弹窗。改为事件驱动。
- [V] `clearAll` 不清内存中的 `App.xposedService` → 改调 `App.clearService()`。
- [V] CHUNK_SIZE = 256K 字符 → TransactionTooLargeException。
- [V] Thread.sleep 在 requestFreshLogs 里 → ANR/黑屏。
- [V] 固定 sleep 等待广播往返 → 改为轮询缓存文件 lastModified。
- [V] 先设 IsUnlocked=true 再检查 has_unlocked_before() → SetUnlocked 被跳过。
- [V] 手动 `rememberNavigationEventDispatcherOwner(parent=null)` → 弹窗收不到返回键。
- [V] `mqqopensdkapi://...` + universal-share authKey → QQ 接住 scheme 但解析失败。
- [V] intro hooks 只装在 15s 延迟路径 → 开场 ~2s 触发；改早期安装路径。
- [V] LSPosed 下 ContentProvider 跨进程 IPC → `Unknown authority`。
- [V] LSPosed 下定向广播 setPackage → 包不可见丢弃。
- [V] LSPosed 下非定向广播 → flyme IntentFirewall 拦截。
- [V] Remote Preferences 在 Hook 进程写日志 → `commit()` 抛 `UnsupportedOperationException`。
- [V] 广播 extras 传 300KB+ 日志 → Binder 溢出风险。
- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 高度被算两次。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp`。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐 — spring 不跟随 fraction。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)`。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。用户需手动加白。
- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable`** [?]——不跟随 `collapsedFraction` 即时变化。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [?]——外层加 Spacer 会重复计算。
- ⚠️ **广播 JSON 不含 position 字段** [?]——解析后必须从本地 externalFilesDir 合并 position。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 Text 显示百分比替代。
- ⚠️ **lint NewApi 拦 minSdk 26 下的高版本 API** [?]——照搬 KernelSU 时注意 minSdk 差异。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` 实为 `TweenVolume.set_volume`** [?]——introSound 必须用真 `AudioSource.set_volume` (0x325040C)。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛异常。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——`ContentResolver.call` 返回 Unknown authority；用 setComponent 显式组件广播绕过。
- ⚠️ **BillingHook 在 NPatch 模式下永远失败** [V]——`onPackageLoaded` 只对 GMS/WebView 触发；解锁靠 native hook。
- ⚠️ **GitHub 代理镜像可用性会变** [V]——`gh-proxy.com`/`ghproxy.net`/`ghproxy.com` 可用（2026-08-19），`mirror.ghproxy.com`/`gh.h233.eu.org`/`ghps.cc` 不可用。

## 6. 下一步（有序）

1. **真机验证 NPatch 场景** — 未装 NPatch 点卡片弹 Toast / 装了 NPatch 自动弹窗两条路径仍未验证。
2. **真机验证日志导出后清理无用代码** — `ConfigProvider` 里的 `pushGameLog`/`readGameLog` 已被广播方案替代，可清理。
3. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`、`NativeBridge.kt`、`OverlayManager.kt`、`MusicPlayer.kt`、`IntroSoundPlayer.kt`、`App.kt` 等仍用裸 log。
4. **继续排查 janky 根因** — R8 映射文件对比。
5. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- NPatch 未安装时点击激活卡片走 Toast 提示——是否同时引导用户跳转 NPatch 安装页/文档？
- V10 引擎声浪是否需要继续实现"游戏内引擎声浪"（第二阶段）？
- GitHub 代理镜像是否需要做成用户可配置（设置页自填代理 URL）？