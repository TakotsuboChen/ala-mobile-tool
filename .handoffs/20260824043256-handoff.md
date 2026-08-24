# HANDOFF — 读全文再开始干活

生成时间: 2026-08-19T21:35:00+08:00 · Git HEAD: `d9b1dab`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `d9b1dab` (2026-08-19)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `d9b1dab`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**本次切片：v1.0.0 正式版发布 + 更新下载修复**。已完成发布（源仓库 + LSPosed 镜像仓库）、真机验证下载修复、撤回重发。

## 2. 已验证状态 — 工作实际停在哪

- [V] **README 同步正式版** — `71f1df6`：badge 版本→1.0.0、新增日志系统功能条目、解锁 PlayerPrefs 持久化说明、设置页表格更新、v1.0.0 版本历史条目、技术名词风格统一（Root/Overlay/Hook/Non-root）。已 push。
- [V] **版本号同步** — `app/build.gradle.kts` + `module.prop` → `1.0.0 / 100300`（正式版 stage=3, D=0）。已 push。
- [V] **CI prerelease 自动推导** — `71f1df6`：`build.yml` 从 versionName 提取阶段（含 Beta/Alpha → prerelease=true，否则 false），替换原硬编码 `prerelease: true`。已 push。
- [V] **首次 v1.0.0 发布** — tag push → CI `completed/success` → `gh release view v1.0.0` 确认 `isPrerelease: false`、APK 已上传、body=手工 RELEASE_NOTES.md。镜像 `100300-1.0.0` 同步确认。
- [V] **更新下载修复** — `d9b1dab`：`UpdateDownloader.kt` + `UpdateChecker.kt` 修复。已 push。
- [V] **真机验证下载修复** — 临时 versionCode 100299 assembleRelease + `adb install -r` → Success；真机打开 App 检查更新→发现 v1.0.0→下载成功。验证后 versionCode 恢复 100300。
- [V] **撤回重发** — 删源+镜像 release → 删本地+远程 tag → commit 修复 → 重新 tag push → CI `completed/success` → `gh release view v1.0.0` 确认 `isPrerelease: false`、body 含下载修复条目。镜像 `100300-1.0.0` 同步确认。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL in 8s。
- 工作区: 干净（仅未提交的本次 handoff 归档文件）。

### 下载修复详情
- `UpdateDownloader.kt`：镜像从 `kkgithub.com`（release asset 404）改为 `gh-proxy.com`/`ghproxy.net`/`ghproxy.com` 三代理 fallback；OkHttp 显式配置 `ProxySelector.getDefault()`（修复 TUN/HTTP 代理模式下不生效）；超时 15s→30s/60s。
- `UpdateChecker.kt`：API 镜像从 `kkgithub.com/api/v3/`（404）改为 `gh-proxy.com` 代理 `api.github.com`；同样加 `ProxySelector`；超时 10/15s→15/20s。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 8s
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 24s
adb install -r app-release.apk → Success
gh release view v1.0.0 → isPrerelease: false, assets: 1, body: 手工 Notes ✓
gh release view 100300-1.0.0 --repo Xposed-Modules-Repo/tools.alamobile.mod → isPrerelease: false, body 同步 ✓
```

## 3. 决策与理由

- **CI prerelease 从 versionName 自动推导** [V]——硬编码 `prerelease: true` 在正式版发布时会误发成 Pre-release（CLAUDE.md 发布阶段策略已标注此坑）。从 versionName 提取阶段（含 Beta/Alpha → prerelease，否则正式）一劳永逸。否决方案：手动改 `prerelease: false` [X]——以后发 Beta 又要改回，容易忘（本次就是忘了的案例）。
- **下载镜像用 `gh-proxy.com` 前缀拼接** [V]——`kkgithub.com` 只镜像 git/页面内容，不镜像 release asset 下载（404）；也不镜像 `/api/v3/`（404）。`gh-proxy.com` 实测 200 + 正确 content-length。前缀拼接方式：`https://gh-proxy.com/{原始URL}`。
- **OkHttp 显式 `ProxySelector.getDefault()`** [V]——不显式配置时 OkHttp 在 Clash/Surge TUN 模式下可能绕过系统代理，导致"挂全局梯子也下不动"。这是用户实测报告的症状。否决方案：不配代理等 TUN 接管 [X]——HTTP 代理模式（端口 7890）下 OkHttp 走 `NO_PROXY` 直连。
- **README 技术名词风格统一** [V]——用户手动改了部分（root→Root、overlay→Overlay、vivo/Android 16→vivo/iQOO OriginOS），我按同一规则补齐遗漏（9 处）。`Non-root` 是固定术语保持小写 root（用户明确纠正）。

## 4. 失败的尝试 — 不要再试

> 从旧 HANDOFF 前向搬运 + 本次新增，标 [V] 的已验证。

- [V] **本次：`kkgithub.com` 用于 release asset 下载** — 返回 404，该镜像不镜像 release asset。改用 `gh-proxy.com` 等代理。不要再试。
- [V] **本次：`kkgithub.com/api/v3/` 用于 API 镜像** — 返回 404，该镜像不镜像 GitHub API。改用 `gh-proxy.com` 代理 `api.github.com`。不要再试。
- [V] **本次：OkHttp 不显式配置 `ProxySelector`** — TUN/HTTP 代理模式下绕过系统代理，挂梯子也下不动。不要再试。
- [V] **本次：`mirror.ghproxy.com` 代理** — DNS 解析失败/连接重置（HTTP 000）。`gh.h233.eu.org` 403、`ghps.cc` 404 也不可用。可用：`gh-proxy.com`、`ghproxy.net`、`ghproxy.com`。
- [V] **本次：`isNpatchInstalled` 用 `getInstalledPackages` 全量遍历** — 慢且触发包可见性风险；改 `getPackageInfo(NPATCH_PKG)` 单包精确查询。不要再试。
- [X] **整体 `graphicsLayer` scale + `layout` 缩放整个响应曲线 Card** — 缩小了文字、线宽、卡片背景。改为 `heightIn` 钳制正方形高度。从旧 HANDOFF 搬运。
- [X] **`heightIn` 把图表改成扁矩形** — 用户要正方形。改为 `heightIn + aspectRatio(1f)`。从旧 HANDOFF 搬运。
- [V] **3s 轮询等 `App.xposedService` 异步绑定** — 超时 → INACTIVE → 误弹免 Root 窗；改为事件驱动 StateFlow。从旧 HANDOFF 搬运。
- [V] **`clearAll` 不清内存中的 `App.xposedService`** — 进程不重启时 service 残留；改为调 `App.clearService()`。从旧 HANDOFF 搬运。
- [V] **CHUNK_SIZE = 256K 字符** — TransactionTooLargeException。从旧 HANDOFF 搬运。
- [V] **Thread.sleep 在 requestFreshLogs 里** — ANR/黑屏；改为 delay + IO。从旧 HANDOFF 搬运。
- [V] **固定 sleep 等待广播往返** — 改为轮询缓存文件 lastModified。从旧 HANDOFF 搬运。
- [V] **先设 IsUnlocked=true 再检查 has_unlocked_before()** — SetUnlocked 被跳过；改为正确顺序。从旧 HANDOFF 搬运。
- [V] **手动 `rememberNavigationEventDispatcherOwner(parent=null)`** — 弹窗收不到返回键；用 ComponentActivity 自带。从旧 HANDOFF 搬运。
- [V] **`mqqopensdkapi://...` + universal-share authKey** — QQ 接住 scheme 但解析失败。从旧 HANDOFF 搬运。
- [V] **intro hooks 只装在 15s 延迟路径** — 开场 ~2s 触发；改早期安装路径。从旧 HANDOFF 搬运。
- [V] **LSPosed 下 ContentProvider 跨进程 IPC** — `Unknown authority`。从旧 HANDOFF 搬运。
- [V] **LSPosed 下定向广播 setPackage** — 包不可见丢弃。从旧 HANDOFF 搬运。
- [V] **LSPosed 下非定向广播** — flyme IntentFirewall 拦截。从旧 HANDOFF 搬运。
- [V] **Remote Preferences 在 Hook 进程写日志** — `commit()` 抛 `UnsupportedOperationException`。从旧 HANDOFF 搬运。
- [V] **广播 extras 传 300KB+ 日志** — Binder 溢出风险。从旧 HANDOFF 搬运。
- [?] 响应曲线 summary 复用同一句贴到两条 — 用户明确否定。从旧 HANDOFF 搬运。
- [?] `Column` + `Spacer(windowInsetsTopHeight)` 推开状态栏 — 高度被算两次。从旧 HANDOFF 搬运。
- [?] 硬编码 `padding(top = 8.dp)` 定位胶囊 — 改用动态计算。从旧 HANDOFF 搬运。
- [?] `onSizeChanged` 测量 `TopAppBar` 展开态总高度 — 改用 miuix `CollapsedHeight = 52.dp`。从旧 HANDOFF 搬运。
- [?] 线性 `alpha = (1 - fraction * 3)` 驱动胶囊渐隐 — spring 不跟随 fraction。从旧 HANDOFF 搬运。
- [?] `spring` 动画 `animateTo(0)` 渐隐 — 改用 `snapTo(0)`。从旧 HANDOFF 搬运。

## 5. 已知坑

- ⚠️ **flyme 后台白名单限制** [V]——非白名单应用 `checkAllowBackgroundLocked` 返回 DISABLED。用户需手动加白。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 小标题用 spring `Animatable`** [?]——不跟随 `collapsedFraction` 即时变化。从旧 HANDOFF 搬运。
- ⚠️ **miuix `TopAppBar` 内部自带状态栏 inset 处理** [?]——外层加 Spacer 会重复计算。从旧 HANDOFF 搬运。
- ⚠️ **广播 JSON 不含 position 字段** [?]——解析后必须从本地 externalFilesDir 合并 position。从旧 HANDOFF 搬运。
- ⚠️ **miuix 无 `LinearProgressIndicator`** [?]——用 Text 显示百分比替代。从旧 HANDOFF 搬运。
- ⚠️ **lint NewApi 拦 minSdk 26 下的高版本 API** [?]——照搬 KernelSU 时注意 minSdk 差异。从旧 HANDOFF 搬运。
- ⚠️ **`OffsetTable.AUDIO_SOURCE_SET_VOLUME` 实为 `TweenVolume.set_volume`** [?]——introSound 必须用真 `AudioSource.set_volume` (0x325040C)。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下 Remote Preferences/Files 在 Hook 进程只读** [V]——`getRemotePreferences().edit()` 抛异常。从旧 HANDOFF 搬运。
- ⚠️ **LSPosed 下游戏进程对模块包不可见** [V]——`ContentResolver.call` 返回 Unknown authority；用 setComponent 显式组件广播绕过。从旧 HANDOFF 搬运。
- ⚠️ **BillingHook 在 NPatch 模式下永远失败** [V]——`onPackageLoaded` 只对 GMS/WebView 触发；解锁靠 native hook。从旧 HANDOFF 搬运。
- ⚠️ **GitHub 代理镜像可用性会变** [V]——本次实测 `gh-proxy.com`/`ghproxy.net`/`ghproxy.com` 可用（2026-08-19），`mirror.ghproxy.com`/`gh.h233.eu.org`/`ghps.cc` 不可用。代理站可能随时失效，后续需定期验证。

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