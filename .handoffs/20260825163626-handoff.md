# HANDOFF — 读全文再开始干活

生成时间: 2026-08-25T15:59:28+00:00 · Git HEAD: `74533ec`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `74533ec` (2026-08-25)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `74533ec`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**踏板方向反转升级**。已完成：`brakeInvert: Boolean` → `PedalInvert` 枚举（关闭/仅油门/仅刹车/全部），开关改下拉，新增油门踏板方向反转，旧 `brake_invert: true` 迁移到"仅刹车踏板"。真机验证全部通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **`PedalInvert` 枚举** — `ModConfig.kt`：4 值枚举 + `invertThrottle`/`invertBrake` 便利属性。`KEY_PEDAL_INVERT = "pedal_invert"`，旧 key 保留为 `KEY_LEGACY_BRAKE_INVERT = "brake_invert"`。`1c6c375` 已 push。
- [V] **迁移函数 `migratePedalInvert`** — 先查新 key，找不到从旧 boolean 迁移（`true → BRAKE`，`false → OFF`）。`read()` 和 `fromJson()` 都调用。logcat 验证：`legacy brake_invert=true -> BRAKE`。
- [V] **`ConfigViewModel` 字段升级** — `brakeInvert: Boolean` → `pedalInvert: PedalInvert`，`setBrakeInvert` → `setPedalInvert`。已 push。
- [V] **UI: SwitchPreference → OverlayDropdownPreference** — `ConfigurePagerMiuix.kt`：标题"踏板方向反转"，描述"反转踏板的行程填充方向"，4 选项（关闭/仅油门/仅刹车/全部）。`invertName()` 辅助函数。已 push。
- [V] **`PedalOverlayView` 油门反转** — `onDraw` THROTTLE + `updateDedicatedThrottle` 新增 `pedalInvert.invertThrottle` 分支，与刹车反转对称。BRAKE 分支从 `brakeInvert` 改为 `pedalInvert.invertBrake`。已 push。
- [V] **README.md 同步** — 功能列表 + 配置表格更新。`74533ec` 已 push。
- [V] **编译 + release 构建 + 真机验证** — 用户确认"全部测试通过"。
- 工作区: 干净。

### 测试/build 输出
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL
./gradlew :app:assembleRelease → BUILD SUCCESSFUL
adb install -r app-release.apk → Success
迁移验证: 旧版 brake_invert=true → 新版自动显示"仅刹车踏板"（logcat: migratePedalInvert: legacy brake_invert=true -> BRAKE）
用户验证: 全部测试通过
```

## 3. 决策与理由

- **`PedalInvert` 枚举而非两个 boolean** [V]——一个枚举表达 4 种组合（关闭/仅油门/仅刹车/全部），比 `throttleInvert + brakeInvert` 两个 boolean 更清晰，UI 下拉天然映射 `entries`。`invertThrottle`/`invertBrake` 便利属性封装 `== THROTTLE || == BOTH` 判断。
- **迁移：旧 `brake_invert: true` → `PedalInvert.BRAKE`** [V]——用户要求"之前打开了刹车方向反转的用户升级上来保持在仅刹车踏板"。`migratePedalInvert` 先查新 key，找不到从旧 boolean 迁移，与项目已有 `migratePedalMode`/`PedalCurve.from` 模式一致。
- **油门反转与刹车反转对称** [V]——`updateDedicatedThrottle` 的改动与 `updateDedicatedBrake` 完全对称：默认 `raw = 1f - t`（顶满），反转 `raw = t`（底满）。`onDraw` 绘制也对称。
- **"全部"而非"油门和刹车踏板"** [V]——用户要求选项名简化。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。完整历史见 `.handoffs/20260825155928-handoff.md`。

**本次新增：**
- [V] **`adb install -r` 覆盖安装时旧版仍运行** → 旧版 ConfigActivity 在前台时安装新版，新版打开后配置可能不对（用户初次报告"显示关闭"）。正确测试流程：旧版开启刹车反转 → force-stop → 安装新版 → 打开。不是代码 bug，是测试方法问题。

**从旧 HANDOFF 搬运（详见 `.handoffs/20260825155928-handoff.md`）：**
- [V] `awaitLsposedSettled` 等 Connected(LSPosed) + 2s 确认窗口 → NPatch 用户等 2s 太慢。不要再试。
- [V] 等"非 Connecting" + 删事件驱动 → App 1.5s 兜底置 Disconnected，service 晚于 1.5s 绑上时过早检测固定 INACTIVE。不要再试。
- [V] 等 Connected 5s 超时 → NPatch binder 先到 → INACTIVE 固定。不要再试。
- [V] NONROOT 立即写缓存 → LSPosed 后到补不上，永远 NPatch。改为只有 LSPOSED 立即写。不要再试。
- [V] `clearAll` 调 `App.clearService()` → connectionState 变化触发弹窗。删掉。不要再试。
- [V] 保留 onResume 重新检测 → 与"状态固定"矛盾。删除。不要再试。
- [V] `onServiceDied` 不检查 service 身份 → NPatch binder 死亡误触。改为检查 `===`。
- [V] 去掉 `evaluate()` service 检查 → onResume 恒 INACTIVE。恢复路径 2。
- [V] `onServiceBind` 不区分框架 → LSPosed 开着但 NPatch 先到显示 NPatch。改为 LSPosed 优先。
- [V] 弹窗并行 → npatchInstalled 竞态。恢复顺序执行。
- [V] `hasShownDialog` 只弹一次 → 关 LSPosed 后再打开不弹窗。去掉。
- [V] `kkgithub.com` 404 / `mirror.ghproxy.com` DNS 失败 / OkHttp 不走系统代理。改用 `gh-proxy.com`。
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