# HANDOFF — 读全文再开始干活

生成时间: 2026-08-25T16:36:27+00:00 · Git HEAD: `d85b9da`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `d85b9da` (2026-08-25)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `d85b9da`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**踏板优先级升级**。已完成：双踏板模式的"刹车过渡点"滑块 → `PedalPriority` 枚举下拉（6 选项），新增油门过渡点滑块（条件显示），`arbitrateDual()` 扩展 4→6 种仲裁策略，旧版无 `pedal_priority` key 天然迁移到 `BRAKE_VALUE`。真机验证全部通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **`PedalPriority` 枚举** — `ModConfig.kt`：6 值枚举 `FIRST_PRESSED / LAST_TOUCHED / ALWAYS_THROTTLE / ALWAYS_BRAKE / THROTTLE_VALUE / BRAKE_VALUE`。`from()` 默认返回 `BRAKE_VALUE`。`420a427` 已 push。
- [V] **Settings 新增字段** — `pedalPriority: PedalPriority`、`throttleTransition: Float`（默认 0.2f）。`brakeTransition` 默认值 0.1→0.2。`read()/fromJson()/write()/defaultSettingsPublic()` 全覆盖。
- [V] **ConfigViewModel 同步** — `ConfigUiState` + setter + `toSettings()` 新增两字段。
- [V] **UI: 滑块→下拉 + 条件滑块** — `ConfigurePagerMiuix.kt`：`OverlayDropdownPreference` 6 选项，选 `THROTTLE_VALUE`→油门过渡点滑块（1-99%），选 `BRAKE_VALUE`→刹车过渡点滑块（1-99%）。Icon 用 `PriorityHigh`（与过渡点 `SwapVert` 区分）。
- [V] **`PedalOverlayView` 仲裁扩展** — `arbitrateDual()` 6 策略；`FIRST_PRESSED`/`LAST_TOUCHED` 用 companion `sharedFirstPressed`/`sharedLastTouched` 时序状态；`ACTION_UP` 清理时序状态。
- [V] **LAST_TOUCHED 修复** — 只在 raw 0→>0（按下瞬间）更新 `sharedLastTouched`，保存 `prevThrottle`/`prevBrake` 判定。MOVE 中不更新，防止先按的手指微动夺回优先。
- [V] **README.md 同步** — 配置表格更新。`d85b9da` 已 push。
- [V] **编译 + release 构建 + 真机验证** — 用户确认"全部测试通过"。
- 工作区: 干净。

### 测试/build 输出
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL
./gradlew :app:assembleRelease → BUILD SUCCESSFUL
adb install -r app-release.apk → Success
用户验证: 全部测试通过
```

## 3. 决策与理由

- **`PedalPriority` 枚举 6 值** [V]——一个枚举表达所有仲裁策略，UI 下拉天然映射 `entries`。`BRAKE_VALUE` 作为默认值保持旧行为（旧版无 key → `from("")` → `BRAKE_VALUE`）。
- **`brake_transition` key 名不变，无需迁移函数** [V]——旧 key 保留，旧值保留，新增 `pedal_priority`/`throttle_transition` 用 `optString`/`optDouble` fallback 默认值。比 `PedalInvert` 的 `migratePedalInvert()` 简单。
- **`ALWAYS_THROTTLE`/`ALWAYS_BRAKE` 只在有值时屏蔽** [V]——`if (sharedRawThrottle > 0f) arbitratedBrake = 0f`，不是无条件屏蔽。否则选了"始终油门优先"后刹车踏板完全失效。
- **LAST_TOUCHED 只在按下瞬间更新** [V]——每次 MOVE 都更新会导致先按的手指微动夺回优先，与 FIRST_PRESSED 表现一致。修复：保存 `prevRaw`，只在 `raw > 0f && prevRaw <= 0f` 时更新 `sharedLastTouched`。
- **油门过渡点在刹车过渡点前面（UI 顺序）** [V]——用户要求"油门放在刹车前面"。枚举顺序 `THROTTLE_VALUE` 在 `BRAKE_VALUE` 前面，UI 条件滑块顺序一致。
- **Icon `PriorityHigh` 而非 `SwapVert`** [V]——过渡点滑块已用 `SwapVert`，踏板优先级用 `PriorityHigh`（感叹号）区分。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。完整历史见 `.handoffs/20260825163626-handoff.md`。

**本次新增：**
- [V] **LAST_TOUCHED 每次 MOVE 都更新 `sharedLastTouched`** → 先按的 view 手指微动产生 MOVE 事件夺回优先，与 FIRST_PRESSED 表现完全一致。改为只在 raw 0→>0（按下瞬间）更新，保存 `prevThrottle`/`prevBrake` 判定。不要再试。

**从旧 HANDOFF 搬运（详见 `.handoffs/20260825163626-handoff.md`）：**
- [V] `adb install -r` 覆盖安装时旧版仍运行 → 配置可能不对。正确测试：force-stop 旧版 → 安装新版。不要再试。
- [V] `awaitLsposedSettled` 等 Connected(LSPosed) + 2s → NPatch 用户等 2s 太慢。不要再试。
- [V] 等"非 Connecting" + 删事件驱动 → App 1.5s 兜底置 Disconnected。不要再试。
- [V] 等 Connected 5s 超时 → NPatch binder 先到 → INACTIVE 固定。不要再试。
- [V] NONROOT 立即写缓存 → LSPosed 后到补不上。只有 LSPOSED 立即写。不要再试。
- [V] `clearAll` 调 `App.clearService()` → connectionState 变化触发弹窗。删掉。不要再试。
- [V] 保留 onResume 重新检测 → 与"状态固定"矛盾。不要再试。
- [V] `onServiceDied` 不检查 service 身份 → NPatch binder 死亡误触。改为检查 `===`。
- [V] 去掉 `evaluate()` service 检查 → onResume 恒 INACTIVE。恢复路径 2。
- [V] `onServiceBind` 不区分框架 → LSPosed 开着但 NPatch 先到。改为 LSPosed 优先。
- [V] 弹窗并行 → npatchInstalled 竞态。恢复顺序执行。
- [V] `hasShownDialog` 只弹一次 → 关 LSPosed 后再打开不弹窗。去掉。
- [V] `kkgithub.com` 404 / `mirror.ghproxy.com` DNS 失败。改用 `gh-proxy.com`。
- [V] CHUNK_SIZE=256K → TransactionTooLargeException / Thread.sleep → ANR。
- [V] 手动 `rememberNavigationEventDispatcherOwner` → 弹窗收不到返回键。
- [V] LSPosed 下 ContentProvider IPC → Unknown authority / 定向广播 → 包不可见 / 非定向广播 → flyme IntentFirewall。
- [V] Remote Preferences `commit()` → UnsupportedOperationException / 广播传 300KB+ → Binder 溢出风险。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [?]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。
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
- ⚠️ **NPatch binder 同步先于 LSPosed daemon** [V]——detectOnce 不能立即写 NONROOT 缓存。

## 6. 下一步（有序）

1. **真机验证 NPatch 未安装路径** — 未装 NPatch 点卡片弹 Toast 路径仍未验证。
2. **清理 ConfigProvider 无用代码** — `pushGameLog`/`readGameLog` 已被广播方案替代。
3. **阶段 2：全量替换裸 `Log.*` 为 `Logger.*`** — `ModConfig.kt`、`NativeBridge.kt`、`OverlayManager.kt`、`MusicPlayer.kt`、`IntroSoundPlayer.kt`、`App.kt` 等仍用裸 log。
4. **继续排查 janky 根因** — R8 映射文件对比。
5. **V10 第二阶段（可选）** — 游戏内引擎声浪。

## 7. 留给用户的开放问题

- NPatch 未安装时点击激活卡片是否引导跳转安装页/文档？
- V10 游戏内引擎声浪是否继续实现？
- GitHub 代理镜像是否做成用户可配置？