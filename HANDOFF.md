# HANDOFF — 读全文再开始干活

生成时间: 2026-08-25T17:25:37+00:00 · Git HEAD: `afdc840`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `afdc840` (2026-08-25)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `afdc840`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**Overlay 控件视觉属性**。已完成：新增三个可配置滑块——控件透明度（0-100%，默认 50%）、边框粗细（0-10dp，默认 5dp）、边框圆角（0-100%，默认 50%）。`clipPath` 圆角裁剪 + 边框内缩描边。真机验证全部通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **ModConfig.kt 7 处同步** — `overlayAlpha`(0.5f)、`overlayBorderWidth`(5.0f)、`overlayCornerRadius`(0.5f)。JSON key: `overlay_alpha`/`overlay_border_width`/`overlay_corner_radius`。`f81336c` 已 push。
- [V] **ConfigViewModel.kt 5 处同步** — ConfigUiState + init + defaultUiState + 3 setter + toSettings。
- [V] **ConfigurePagerMiuix.kt UI** — 3 个 `SliderPreference`（DUAL 块后、Card 内），icon: `Opacity`/`BorderOuter`/`RoundedCorner`。
- [V] **PedalOverlayView.kt 绘制改造** — Paint alpha 从 `settings.overlayAlpha` 读（`(1-transparency)*255`，反转语义）；`borderPaint` alpha 也从 settings 读；`clipPath` 裁剪填充到边框内边缘以内（`fillInset = strokeWidth`）；边框内缩 `strokeWidth/2` 画 `drawRoundRect`。
- [V] **GearShiftView.kt 同步改造** — 构造函数加 `settings` 参数；Paint/clipPath/onDraw 同 PedalOverlayView。
- [V] **OverlayManager.kt** — `GearShiftView(appContext, settings)` 传 settings。
- [V] **README.md 同步** — 配置表格新增三行。`afdc840` 已 push。
- [V] **编译 + release 构建 + 真机验证** — 用户确认"全部测试通过"。
- 工作区: 干净。

### 测试/build 输出
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL (1m 31s)
adb install -r app-release.apk → Success
用户验证: 全部测试通过
```

## 3. 决策与理由

- **透明度语义反转** [V]——`alphaOf(transparency) = (1 - transparency) * 255`。100% = 完全透明，0% = 完全不透明。首次实现用了 `ratio * 255`（100% = 不透明），用户反馈"透明度反了"。
- **边框内缩 strokeWidth/2 画** [V]——`drawRoundRect(inset, inset, w-inset, h-inset, ...)` 使 stroke 外边缘对齐控件边界。首次实现用 `(0,0,w,h)` 画，圆角处比直线处粗（外弧周长 > 内弧）。
- **填充裁剪内缩 strokeWidth** [V]——`clipPath` 裁剪到 `(fillInset, ..., w-fillInset, ...)`，使填充边缘与边框内边缘重合。首次实现裁剪到 `(0,0,w,h)`，填充延伸到边框下面，半透明边框透出填充色。
- **`borderPaint` alpha 从 settings 读** [V]——首次实现用 `Color.WHITE`（alpha=255 固定），边框不受透明度控制。改为 `Color.argb(alphaOf(...), 255, 255, 255)`。
- **GearShiftView 构造函数加 settings** [V]——与 PedalOverlayView 对齐，配置变更靠销毁重建生效。
- **默认值 50%/5dp/50%** [V]——用户指定。初始实现用 50%/2dp/25%，用户改为 50%/5dp/50%。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。完整历史见 `.handoffs/20260825172537-handoff.md`。

**本次新增：**
- [V] **透明度 `alphaOf(ratio) = ratio * 255`** → 100% = 不透明，语义反了。改为 `(1-ratio) * 255`。不要再试。
- [V] **`borderPaint` 用 `Color.WHITE` 不读 alpha** → 边框不受透明度控制。改为 `Color.argb(alphaOf(...), 255, 255, 255)`。不要再试。
- [V] **`drawRoundRect(0,0,w,h,...)` 画边框不内缩** → 圆角处比直线处粗。改为内缩 `strokeWidth/2`。不要再试。
- [V] **`clipPath` 裁剪到 `(0,0,w,h)`** → 填充延伸到边框下面，半透明边框透填充色。改为裁剪内缩 `strokeWidth`（`fillInset`）。不要再试。

**从旧 HANDOFF 搬运（详见 `.handoffs/20260825172537-handoff.md`）：**
- [V] `LAST_TOUCHED` 每次 MOVE 更新 → 先按的手指微动夺回优先。改为只在按下瞬间更新。
- [V] `adb install -r` 覆盖安装时旧版运行 → 配置不对。force-stop 再安装。
- [V] `awaitLsposedSettled` 等 Connected(LSPosed)+2s → NPatch 太慢。
- [V] 等"非 Connecting"+删事件驱动 → App 1.5s 兜底 Disconnected。
- [V] 等 Connected 5s 超时 → NPatch binder 先到 → INACTIVE 固定。
- [V] NONROOT 立即写缓存 → LSPosed 后到补不上。只有 LSPOSED 立即写。
- [V] `clearAll` 调 `App.clearService()` → connectionState 变化触发弹窗。删掉。
- [V] 保留 onResume 重新检测 → 与"状态固定"矛盾。
- [V] `onServiceDied` 不检查 service 身份 → NPatch binder 死亡误触。改为 `===`。
- [V] 去掉 `evaluate()` service 检查 → onResume 恒 INACTIVE。恢复路径 2。
- [V] `onServiceBind` 不区分框架 → LSPosed 开着但 NPatch 先到。改为 LSPosed 优先。
- [V] 弹窗并行 → npatchInstalled 竞态。恢复顺序执行。
- [V] `hasShownDialog` 只弹一次 → 关 LSPosed 后再打开不弹窗。去掉。
- [V] `kkgithub.com` 404 / `mirror.ghproxy.com` DNS 失败。改用 `gh-proxy.com`。
- [V] `CHUNK_SIZE=256K` → TransactionTooLargeException / Thread.sleep → ANR。
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