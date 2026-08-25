# HANDOFF — 读全文再开始干活

生成时间: 2026-08-25T15:35:27+00:00 · Git HEAD: `10a3117`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `10a3117` (2026-08-25)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `10a3117`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**激活状态会话级持久化**。已完成：冷启动检测一次后固定到下次冷启动，清除激活标记本次不变下次冷启动重新检测，NPatch→LSPosed 不切换。真机验证"非常快速且精准稳定"。

## 2. 已验证状态 — 工作实际停在哪

- [V] **会话级缓存 `detectOnce`/`evaluate`/`forceSettle`** — `LsposedStatus.kt`：`cachedStatus`（进程级）+ `detectionDone`。`detectOnce` 只有 LSPOSED 立即写缓存（最高优先级确定不变）；NONROOT/INACTIVE 不写但返回给 UI 显示。`evaluate` 只读缓存。`forceSettle(context)` 5s 超时调 `evaluateInternal` 写缓存。`10a3117` 已 push。
- [V] **`clearAll` 只清持久标记** — `LsposedStatus.kt`：删 `App.clearService()` 调用，不动 `cachedStatus`/`connectionState`。清除结果下次冷启动重新检测才生效。已 push。
- [V] **`App.clearService()` 已删除** — `App.kt`：无调用方，且是"清除标记当场弹窗"的触发源。已 push。
- [V] **UI 恢复 2s 等待 + 事件驱动** — `OverviewPagerMiuix.kt`：`LaunchedEffect(Unit)` 2s 等待 → `detectOnce` → 弹窗 → `delay(3000)` → `forceSettle`。`LaunchedEffect(connectionState)` 用 `isDetectionDone` 守护（缓存写入后跳过）。删除 `onResume` DisposableEffect。已 push。
- [V] **编译 + release 构建 + 真机验证** — 用户确认"非常快速且精准稳定"。
- 工作区: 干净（仅未提交的本次 handoff 归档文件）。

### 测试/build 输出
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 2m
adb install -r app-release.apk → Success
用户验证: 非常快速且精准稳定
```

## 3. 决策与理由

- **只有 LSPOSED 立即写缓存** [V]——LSPosed 最高优先级，确定后不降级。NONROOT 不写：NPatch binder 同步先到 → detectOnce 返回 NONROOT，但 LSPosed daemon 可能 2s 后才推 binder，立即写缓存会导致 LSPosed 后到补不上（用户实测：永远显示 NPatch）。NONROOT 不写但 UI 显示 → 2s 出结果（快），事件驱动补 LSPOSED（可靠），5s forceSettle 兜底（无感知）。
- **恢复 2s 等待 + 事件驱动（上次会话逻辑）** [V]——用户明确说"上次会话改完后 LSPosed 和 NPatch 都反应迅速且从未出错"。只加"运行时单次持久化"和"清除标记下次冷启动"。否决方案：`awaitLsposedSettled` 等 Connected(LSPosed) 5s + 2s 确认窗口——NPatch 用户等 2s 确认太慢（用户实测）。
- **`clearAll` 不调 `clearService`** [V]——`clearService` 把 `connectionState` 置 Disconnected → `LaunchedEffect(connectionState)` 事件驱动 → 当场弹窗（问题 1）。删掉后清除标记不触发 connectionState 变化 → 不弹窗，下次冷启动重新检测。
- **删除 `onResume` 重新检测** [V]——状态固定后不需要。上次会话的 onResume 是"切后台回来重新检测"→ 与"状态固定"矛盾。
- **`forceSettle` 调 `evaluateInternal` 而非硬编码 INACTIVE** [V]——NPatch 激活用户 5s 超时时应写 NONROOT（有 nonroot flag），不是 INACTIVE。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

**本次新增：**
- [V] **`awaitLsposedSettled` 等 Connected(LSPosed) + 2s 确认窗口** → NPatch 用户等 2s 才出结果，用户说"NPatch 检测卡太久"。改为恢复 2s 等待 + 事件驱动，只有 LSPOSED 立即写缓存。不要再试。
- [V] **等"非 Connecting"（`first { it !is Connecting }`）+ 删事件驱动** → App 侧 1.5s 兜底置 Disconnected，service 晚于 1.5s 绑上时过早检测固定 INACTIVE（用户实测：清除标记 → 杀进程 → 重启 → 检测不到 LSPosed）。不要再试。
- [V] **等 Connected（`first { it is Connected }`）5s 超时** → NPatch binder 同步先到 → Connected(NPatch) → detectOnce 路径 2 不命中 → INACTIVE 固定（用户实测：重复杀进程重启掉激活）。不要再试。
- [V] **NONROOT 立即写缓存** → NPatch 激活用户有 nonroot flag → detectOnce 返回 NONROOT → 立即写缓存 → LSPosed 后到事件驱动跳过 → 永远 NPatch（用户实测：开 LSPosed 重启多少次还是 NPatch）。改为只有 LSPOSED 立即写。不要再试。
- [V] **`clearAll` 调 `App.clearService()`** → connectionState 置 Disconnected → 事件驱动弹窗（用户实测：清除标记当场弹窗）。删掉调用。不要再试。
- [V] **保留 onResume 重新检测** → 状态固定后多余，且"切后台回来重新检测"与"状态固定"矛盾。删除。不要再试。

**从旧 HANDOFF 搬运（详见 `.handoffs/20260825153527-handoff.md`）：**
- [V] `onServiceDied` 不检查 service 身份 → NPatch binder 死亡误触 Disconnected。改为检查 `===`。
- [V] 去掉 `evaluate()` 的 service 检查路径 → onResume 恒返回 INACTIVE。恢复路径 2。
- [V] `onServiceBind` 不区分框架 → LSPosed 开着但 NPatch 先到显示 NPatch。改为 LSPosed 优先。
- [V] 弹窗移到 `LaunchedEffect(connectionState)` 并行 → npatchInstalled 竞态。恢复顺序执行。
- [V] `hasShownDialog` 只弹一次 → 关 LSPosed 后再打开不弹窗。去掉。
- [V] `kkgithub.com` 404 / `mirror.ghproxy.com` DNS 失败 / OkHttp 不走系统代理。改用 `gh-proxy.com`。
- [V] CHUNK_SIZE=256K → TransactionTooLargeException / Thread.sleep → ANR / 固定 sleep 等广播。
- [V] 手动 `rememberNavigationEventDispatcherOwner` → 弹窗收不到返回键。
- [V] LSPosed 下 ContentProvider IPC → Unknown authority / 定向广播 setPackage → 包不可见 / 非定向广播 → flyme IntentFirewall。
- [V] Remote Preferences `commit()` → UnsupportedOperationException / 广播传 300KB+ → Binder 溢出风险。
- [?] 响应曲线 summary 复用 / `Column+Spacer` 推开状态栏 / 硬编码 padding / `onSizeChanged` 测量 / 线性 alpha / spring 渐隐。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。
- ⚠️ **miuix `TopAppBar` spring 不跟随 fraction** [?]——小标题不即时变化。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset** [?]——外层加 Spacer 重复计算。
- ⚠️ **广播 JSON 不含 position 字段** [?]——从本地 externalFilesDir 合并。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 Text 显示百分比。
- ⚠️ **lint NewApi 拦 minSdk 26 下高版本 API** [?]——照搬 KernelSU 注意 minSdk 差异。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` 实为 `TweenVolume.set_volume`** [?]——introSound 用真 `AudioSource.set_volume` (0x325040C)。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛异常。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——用 setComponent 显式组件广播绕过。
- ⚠️ **BillingHook 在 NPatch 模式下永远失败** [V]——解锁靠 native hook。
- ⚠️ **GitHub 代理镜像可用性会变** [V]——`gh-proxy.com`/`ghproxy.net`/`ghproxy.com` 可用（2026-08-19）。
- ⚠️ **NPatch binder 同步先于 LSPosed daemon** [V]——bindNpatchRemoteService 同步调用，LSPosed 推送异步。detectOnce 不能立即写 NONROOT 缓存，否则 LSPosed 后到补不上。

## 6. 下一步（有序）

1. **真机验证 NPatch 未安装路径** — 未装 NPatch 点卡片弹 Toast 路径仍未验证。
2. **真机验证日志导出后清理无用代码** — `ConfigProvider` 里的 `pushGameLog`/`readGameLog` 已被广播方案替代，可清理。
3. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`、`NativeBridge.kt`、`OverlayManager.kt`、`MusicPlayer.kt`、`IntroSoundPlayer.kt`、`App.kt` 等仍用裸 log。
4. **继续排查 janky 根因** — R8 映射文件对比。
5. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- NPatch 未安装时点击激活卡片是否引导跳转安装页/文档？
- V10 游戏内引擎声浪是否继续实现？
- GitHub 代理镜像是否做成用户可配置？