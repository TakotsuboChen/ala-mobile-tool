# HANDOFF — 读全文再开始干活

生成时间: 2026-08-26T01:45+00:00 · Git HEAD: `dcea4b9`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `dcea4b9` (2026-08-26)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `dcea4b9`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**v1.0.1 正式版发布完成**。README 更新（6 处修正）+ 版本号同步 + Release Notes + CI 发布 + 镜像同步全部验证通过。

## 2. 已验证状态 — 工作实际停在哪

- [V] **README.md 6 处改动** — badge `1.0.1` + 踏板优先级仲裁(6 策略) + Overlay 视觉属性条目 + LSPosed 激活检测(会话级固定) + 清除激活标记(下次冷启动生效) + 版本历史 v1.0.1。`dcea4b9` 已 push。
- [V] **版本号文件同步** — `build.gradle.kts` versionCode `101300`/versionName `"1.0.1"`，`module.prop` version `1.0.1`/versionCode `101300`。
- [V] **Release Notes** — `RELEASE_NOTES.md` 写入 3 Features + 2 Bug Fixes，格式与 v1.0.0 一致（含 Version Code 行）。
- [V] **CI 构建 + 发布** — tag `v1.0.1` push 触发 CI（run 32878915265），Build + Upload to Release + sync-lsposed 全绿。
- [V] **源 Release 验证** — body=手工 Notes，asset `Ala.Mobile.Tool.v1.0.1.apk`(10.4MB)，isPrerelease=false。
- [V] **镜像 Release 验证** — body 与源一致，tag `101300-1.0.1`，isPrerelease=false。
- [V] **镜像 README 同步** — badge `version-1.0.1`（第 6 行）+ 版本历史 v1.0.1 条目（第 348 行）。
- 工作区: 干净。

### 测试/build 输出
```
CI run 32878915265 (tag v1.0.1 push) → 全绿
  ✓ Build release APK
  ✓ Rename APK to project naming convention
  ✓ Upload to Release
  ✓ sync-lsposed (sync releases + sync README)
gh release view v1.0.1 → isPrerelease=false, body=手工Notes, asset uploaded
gh release view 101300-1.0.1 (镜像) → body=手工Notes, isPrerelease=false
镜像 README → version-1.0.1 badge + v1.0.1 版本历史条目 ✅
```

## 3. 决策与理由

- **README 全面修正** [V]——subagent 发现 3 处过时描述（刹车优先仲裁默认 10% 应为 20%、LSPosed 激活检测"实时显示"与会话级固定冲突、缺 Overlay 视觉属性条目），全部修正而非仅改 badge。否决方案：仅改 badge+版本历史，否决原因：过时描述误导用户。
- **1.0.1 正式版** [V]——1.0.0 是正式版，1.0.1 沿用正式版。versionCode=101300。
- **Release Notes 格式含 Version Code 行** [V]——与 v1.0.0 既有惯例一致（template.md 正式版模板无此行，项目惯例优先）。
- **"Notes 先于 CI 存在"策略** [V]——`RELEASE_NOTES.md` 与代码一起 commit，CI 用 `body_path` 读取。否决方案：事后 `gh release edit`，否决原因：镜像同步晚于 CI 内 sync 会拿到默认说明。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。本次新增：无（文档和发布操作，无代码调试）。

- [V] `alphaOf(ratio) = ratio*255` → 100%=不透明，语义反了。改为 `(1-ratio)*255`。不要再试。
- [V] `borderPaint` 用 `Color.WHITE` 不读 alpha → 边框不受透明度控制。改为 `Color.argb(...)`。不要再试。
- [V] `drawRoundRect(0,0,w,h,...)` 画边框不内缩 → 圆角处比直线处粗。改为内缩 `strokeWidth/2`。不要再试。
- [V] `clipPath` 裁剪到 `(0,0,w,h)` → 填充延伸到边框下面。改为裁剪内缩 `strokeWidth`。不要再试。
- [V] `LAST_TOUCHED` 每次 MOVE 更新 → 先按的手指微动夺回优先。改为只在按下瞬间更新。不要再试。
- [V] `adb install -r` 覆盖安装时旧版运行 → force-stop 再安装。不要再试。
- [V] `awaitLsposedSettled` 等 Connected(LSPosed)+2s → NPatch 太慢。不要再试。
- [V] 等"非 Connecting"+删事件驱动 → App 1.5s 兜底 Disconnected。不要再试。
- [V] 等 Connected 5s 超时 → NPatch binder 先到 → INACTIVE 固定。不要再试。
- [V] NONROOT 立即写缓存 → LSPosed 后到补不上。只有 LSPOSED 立即写。不要再试。
- [V] `clearAll` 调 `App.clearService()` → connectionState 变化触发弹窗。删掉。不要再试。
- [V] 保留 onResume 重新检测 → 与"状态固定"矛盾。不要再试。
- [V] `onServiceDied` 不检查 service 身份 → NPatch binder 死亡误触。改为 `===`。不要再试。
- [V] 去掉 `evaluate()` service 检查 → onResume 恒 INACTIVE。恢复路径 2。不要再试。
- [V] `onServiceBind` 不区分框架 → LSPosed 开着但 NPatch 先到。改为 LSPosed 优先。不要再试。
- [V] 弹窗并行 → npatchInstalled 竞态。恢复顺序执行。不要再试。
- [V] `hasShownDialog` 只弹一次 → 关 LSPosed 后再打开不弹窗。去掉。不要再试。
- [V] `kkgithub.com` 404 / `mirror.ghproxy.com` DNS 失败。改用 `gh-proxy.com`。不要再试。
- [V] `CHUNK_SIZE=256K` → TransactionTooLargeException / Thread.sleep → ANR。不要再试。
- [V] 手动 `rememberNavigationEventDispatcherOwner` → 弹窗收不到返回键。不要再试。
- [V] LSPosed 下 ContentProvider IPC → Unknown authority / 定向广播 → 包不可见 / 非定向广播 → flyme IntentFirewall。不要再试。
- [V] Remote Preferences `commit()` → UnsupportedOperationException / 广播传 300KB+ → Binder 溢出风险。不要再试。

## 5. 已知坑

- ⚠️ flyme 后台白名单限制 [?]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。
- ⚠️ miuix `TopAppBar` spring 不跟随 fraction [?]——小标题不即时变化。
- ⚠️ miuix `TopAppBar` 内部自带状态栏 inset [?]——外层加 Spacer 重复计算。
- ⚠️ 广播 JSON 不含 position 字段 [?]——从本地 externalFilesDir 合并。
- ⚠️ miuix 无 `LinearProgressIndicator` [?]——用 Text 显示百分比。
- ⚠️ lint NewApi 拦 minSdk 26 下高版本 API [?]——照搬 KernelSU 注意 minSdk 差异。
- ⚠️ `OffsetTable.AUDIO_SOURCE_SET_VOLUME` 实为 `TweenVolume.set_volume` [?]——introSound 用真 `AudioSource.set_volume` (0x325040C)。
- ⚠️ LSPosed 下 Remote Preferences/Files 在 Hook 进程只读 [V]——`getRemotePreferences().edit()` 抛异常。
- ⚠️ LSPosed 下游戏进程对模块包不可见 [V]——用 setComponent 显式组件广播绕过。
- ⚠️ BillingHook 在 NPatch 模式下永远失败 [V]——解锁靠 native hook。
- ⚠️ GitHub 代理镜像可用性会变 [V]——`gh-proxy.com`/`ghproxy.net`/`ghproxy.com` 可用（2026-08-19）。
- ⚠️ NPatch binder 同步先于 LSPosed daemon [V]——detectOnce 不能立即写 NONROOT 缓存。

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
- CLAUDE.md 的 IL2CPP 逆向工程部分用 `il2cpp-dumps/v8.0.0` 路径，但项目已适配 8.0.4——是否需要更新路径？