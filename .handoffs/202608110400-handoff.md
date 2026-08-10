# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T03:30:16+08:00 · Git HEAD: 最近提交 `062bd05`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `062bd05` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `062bd05`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M23 条目 + 本文件。

## 1. 当前目标
M23 两个任务：替换主菜单音乐（已完成并真机验证 ✓）+ **NPatch 下游戏不运行时改配置不生效（待修复）**。

## 2. 已验证状态 — 工作实际停在哪
- [V] **分支 `main` 已 push 到 origin**——`git log --oneline -3` 显示 `062bd05` (docs M23) + `1147209` (feat music) + `8a66041` (fix 描述)。
- [V] **build 全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`（39 tasks: 8 executed, 31 up-to-date，exit 0）。lint 本次未单独跑。
- [V] **音乐替换已真机验证**——用户确认"音乐工作完好"。APK installed（381QYFCN22B9A / MEIZU 20 / Android 16）。MP3 已打包进 `res/raw/f1_music.mp3`（2185567 字节，`unzip -l` 确认在 APK 内）。
- [V] **LSPosed 下配置不生效已解决**——M22 remote prefs + flush 修复，真机验证"游戏不运行时改配置→启动生效"。
- [V] **NPatch 下配置不生效仍存在**——用户本次报告：LSPosed 生效，NPatch 不生效。**根因未定位，待修复**。

## 3. 决策与理由
- **音乐替换用 native hook 主菜单心跳 + Java MediaPlayer** [V]——`handleMusicVolume.Update()`（RVA 0x1A45100）只在主菜单场景每帧调用，作为"主菜单心跳"信号；`AudioSource.set_volume()`（RVA 0x324246C）静音游戏原生音乐；Java `MusicPlayer` 提取内置 MP3 → MediaPlayer 循环 → 每 1s 轮询 `isInMainMenu()`。否决：hook `AudioSource.Play()` 替换 clip（需创建 IL2CPP AudioClip，复杂）+ hook `SplashscreenManager.Awake`（只启动画面一次，不覆盖主菜单）。
- **MP3 从 ClassLoader 提取而非 R 资源** [V]——`BitmapFactory.decodeResource` 在游戏进程返 null（游戏 Resources 不识模块 R），`PackageManager.getResourcesForApplication` 抛 NameNotFoundException。用 `ClassLoader.getResourceAsStream("res/raw/f1_music.mp3")` 绕过包可见性（BaseDexClassLoader 把 APK 当 zip）。
- **保存 MP3 放 cacheDir 而非 internalFilesDir** [V]——游戏进程对游戏 cacheDir 有写权限，MediaPlayer.setDataSource 需要真实文件路径。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M22）** 只 hook Awake 预设 IsUnlocked=true 不挡 OnOwnedNone、`tclEnable=0` 关 TC、`absEnable=0` 关 ABS、hook HandleABS、hook DoGearShifting 整段跳过、FixedUpdate 写 `automatic=false`、`OnAlreadyOwned` 手写 IL2CPP string、`dlopen("libil2cpp.so")`、`forceUnlockNow` 15s 调 `get_Instance()`、只 defer `onPackageReady` 不 defer `onPackageLoaded`、ShadowHook SHARED 模式、`carPilot`(0x68) 作玩家判据、`System.getProperty(MODULE_LOADED_FLAG)` 作激活判定、`openRemoteFile` 读模块 filesDir、legacy `XSharedPreferences`、模块进程写公共 `/sdcard/`、ContentProvider 跨进程、`createPackageContext`、`by lazy` 只改缓存、`applyCurve` 作用单字段、BRAKE 从底向上画水位式、M12 OverlayEditView 传 settings.*Position 作 defaultPosition、SINGLE/DUAL 共用 pedal_position 字段、手写 ImageVector.Builder + PathBuilder 转换 SVG、Inkscape object-stroke-to-path 把 stroke 转 fill、Gearbox 单 path evenOdd、Gearbox 单 path NonZero + 反向缠绕、ABS "ABS" 文字 fill/stroke 渲染、仅凭 `App.xposedService != null` 判激活、`openRemoteFile` 读 LSPosed daemon 目录当模块 filesDir——均不再试。

## 5. 已知坑
- **⚠️ NPatch 无 daemon 无管理器，config-sync 断链** [?]——NPatch embedded/local 模式（patcher 打包模块进游戏）无 daemon、无 `top.nkbe.npatch` 管理器 ContentProvider。`getRemotePreferences` 和 `bindNpatchRemoteService` 都不可用。`ModConfig.write` 只写模块 filesDir + 广播；游戏没运行时广播被系统丢弃 → 下次启动 `readFromTargetProcess` 读不到 remote prefs（无 daemon），本地 externalFilesDir 也读不到（模块 filesDir 与游戏文件隔离）。**这是本次待修问题的根因假设，未验证。**
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要其离合器结合/挂挡逻辑。
- **⚠️ FixedUpdate 每帧覆盖 automatic** [V]——`0x1a5de94: strb w9,[x19,#0xbc]`。
- **⚠️ 游戏每帧覆盖 tclEnable/absEnable** [V]——logcat `before=1 after=1`。
- **油门＞0 时 AI 车被误控** [?]——M18 遗留，未在本会话验证。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 用 CLAUDE.md M8 表格锚点反推** [V]——Beta 3=`100230`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **ConfigProvider.kt 已废弃** [?]——待清理。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。
- **ClassLoader.getResourceAsStream 在隔离 ClassLoader 下可能读不到 raw** [?]——音乐替换真机已验证走的是主路径，未确认备选路径是否触发。

## 6. 下一步（有序）
1. **修复 NPatch 配置不生效**——根因假设：NPatch embedded/local 无 daemon，`getRemotePreferences`/`bindNpatchRemoteService` 不可用，配置只写模块 filesDir + 广播，游戏不运行时广播丢失。需研究：NPatch 是否有可用的跨进程配置机制（如 patcher 注入的 shared dir、或 `openRemoteFile` 在 NPatch 下的路径、或让 `ModConfig.write` 同时写一个游戏进程可读的公共位置）。**先抓 NPatch 的 logcat 确认 `readFromTargetProcess` 走哪条分支（remote 空 / local 空 / 哪种 fallback），再定方案。**
2. **验证 NPatch 下音乐替换是否也受影响**——音乐开关走的也是 `enableMusicReplace` 配置链路，若 NPatch 配置读不到则音乐开关同样不生效。需一并确认。
3. **清理诊断日志 + HandleABS hook**——HandleABS hook 无用可删。
4. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步 + CI workflow `prerelease: true` + tag `v1.0.0-Beta-3`。**等 NPatch 配置修复完再发。**
5. **可选：清理 ConfigProvider.kt**——已废弃。

## 7. 留给用户的开放问题
- NPatch embedded/local 模式（无 daemon 无管理器）下，模块配置如何跨进程同步到游戏进程？是否有官方机制？
- M18 AI 车误控是否已由 `proxy_player_controls_update` 天然玩家车过滤根治？需真机多人验证。
- 音乐替换的 ClassLoader.getResourceAsStream 备选路径是否触发过？（真机日志 `MusicPlayer:` 可确认）