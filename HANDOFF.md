# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T12:21:30+08:00 · Git HEAD: `6a1fbd4`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `6a1fbd4` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `6a1fbd4`；变了说明快照可能过期。
- 工作区: 本交接结束时为 clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M23/M23-后续 条目 + 本文件。

## 1. 当前目标
**主菜单音乐替换"静音不播放"修复 + 320kbps 音质升级** 已完成并真机验证。根因是游戏进程 ClassLoader 取不到 APK 内资源，改为拿 APK 绝对路径用 ZipFile 直接解压；MP3 从 `res/raw` 移到 `assets`（R8 会把 raw 重命名为短随机名）；音质从 96kbps 升到 320kbps。

## 2. 已验证状态 — 工作实际停在哪
- [V] **构建全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`（exit 0）；`./gradlew :app:assembleRelease` → `BUILD SUCCESSFUL`（exit 0）。
- [V] **Debug + Release 双版真机验证通过**——用户确认"可以正常播放了""成功了"（设备 381QYFCN22B9A / MEIZU 20 / Android 16）。
- [V] **新 MP3 已替换**——`ffprobe` 确认 `app/src/main/assets/f1_music.mp3` = 320 kb/s, 44100 Hz, stereo, 3:02，7,285,086 字节（源 `D:\Downloads\Hans Zimmer - F1.mp3`）。
- [V] **release APK 含 `assets/f1_music.mp3`**——`unzip -l` 确认 7,285,086 字节（R8 后 assets 不重命名）。
- [V] **分支 `main` 已 push**——`git log --oneline -3` 显示 `6a1fbd4` (docs) → `121c61a` (feat) → `ffcaa97` (旧 handoff)。
- 工作区: clean。

## 3. 决策与理由
- **`MusicPlayer` 改用 `NativeBridge.resolveModuleApkPath()` + `ZipFile` 解压** [V]——根因：游戏进程 ClassLoader 的 `dexElements[].path` 指向优化后的 dex 而非原 APK，`getResourceAsStream` 找不到 raw 资源（真机 logcat `MusicPlayer: resource not found in ClassLoader` + `alternative path also not found`）。`resolveModuleApkPath` 优先级：Context → ClassLoader 反射 → codeSource location（与 M19 forceLoad 提取 .so 同一思路）。否决方案：继续用 ClassLoader 资源流（已证伪）。
- **MP3 从 `res/raw` 移到 `assets`** [V]——release `isMinifyEnabled=true`，R8 资源缩减把 raw 里的 mp3 重命名为短随机名（实测 debug=`res/raw/f1_music.mp3`，release=`res/sL.mp3`），按名解压失败。`assets/` 下的文件 R8 不重命名，`ZipFile` 按 `assets/f1_music.mp3` 固定路径读取稳定。APK 条目路径 `assets/` 前缀（Android 打包约定）。
- **`MusicPlayer.poll` 每次补一次 `extractMusicFile`** [V]——init 时 APK 路径可能还没解析成功（Context 拿不到模块包、反射时机早），主菜单轮询时补提兜住。
- **`getResourceAsStream` 兜底保留但路径改为 `assets/`** [V]——部分环境 ClassLoader 可解析 assets；主路径已真机验证，兜底仅保险。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M22/M23 前全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile` 读模块 filesDir（实际读 LSPosed daemon 目录）、模块进程写公共 `/sdcard/`（scoped storage EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式——均不再试。
- **（本会话）游戏进程 ClassLoader.getResourceAsStream 提取 MP3** → 静音不播放 [V]——`dexElements[].path` 是优化 dex 路径，zip 查找落空。改为 APK 绝对路径 + ZipFile。不要再试 ClassLoader 资源流作为主路径。
- **（本会话）MP3 放 `res/raw` 做 release 包** → Release 解压失败 [V]——R8 资源缩减把 raw mp3 重命名为 `res/sL.mp3`，按名解压失败。移 `assets/` 解决。不要再把 MP3 放 res/raw。

## 5. 已知坑
- **⚠️ Release 构建会 R8 重命名 res/raw 下的 mp3** [V]——`isMinifyEnabled=true` 时 raw 资源被重命名为短随机名；assets 不受影响。加音乐类资源一律放 `assets/`。
- **⚠️ 游戏进程 ClassLoader 取不到 APK 内资源** [V]——`dexElements[].path` 指向优化 dex。取 APK 内文件（.so、assets、raw）一律走 `NativeBridge.resolveModuleApkPath()` + ZipFile。
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要其离合器结合/挂挡逻辑。
- **⚠️ FixedUpdate 每帧覆盖 automatic** [V]——`0x1a5de94: strb w9,[x19,#0xbc]`。
- **⚠️ 游戏每帧覆盖 tclEnable/absEnable** [V]——logcat `before=1 after=1`。
- **油门＞0 时 AI 车被误控** [?]——M18 遗留，未在本会话验证。
- **横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **versionCode 用 CLAUDE.md M8 表格锚点反推** [V]——Beta 3=`100230`。
- **ConfigActivity 进程不被 LSPosed 注入** [V]。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **ConfigProvider.kt 现在非废弃** [V]——NPatch 回退路径，不可删。manifest provider 声明必须保留。
- **共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。

## 6. 下一步（有序）
1. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true` + tag `v1.0.0-Beta-3`。
2. **可选**：M18 AI 误控真机多人验证。

## 7. 留给用户的开放问题
- M18 AI 车误控是否已由 `proxy_player_controls_update` 天然玩家车过滤根治？需真机多人验证。
- 320kbps 替换曲是否够用？若需要换曲，直接替换 `app/src/main/assets/f1_music.mp3` 重建即可。
