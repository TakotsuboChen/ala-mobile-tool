# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T14:08:23+08:00 · Git HEAD: `ef53c4c`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `ef53c4c` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `ef53c4c`；变了说明快照可能过期。
- 工作区: clean（所有改动已 commit + push）。
- 先读: `CLAUDE.md` M24 条目 + 本文件。

## 1. 当前目标
**多人模式 AI 车油门误控修复** 已完成并真机验证（用户确认"不会覆盖 AI 车了"）。根因是 `is_player_controller`（读 `playerControls`@0x108）对 AI 车也可能返回 true，旧 `g_last_controller` 被 AI 车 setter 污染；改用 `g_player_controller`，只由挂在玩家车 GameObject 上的 `IRDSPlayerControls.Update` 设置。

## 2. 已验证状态 — 工作实际停在哪
- [V] **构建全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`（exit 0）。
- [V] **APK 已安装**——`adb install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`（设备 381QYFCN22B9A）。
- [V] **真机验证通过**——用户确认 AI 车不再被模块油门误控。多人模式实测正常。
- [V] **分支 `main` 已 push**——`git status --short --branch` 显示 `## main...origin/main`（无 ahead/behind）。
- [V] **诊断日志已移除**——`grep DIAG native/src/pedal_hook.c` 无残留（chore commit `212be5b`）。
- 工作区: clean。

### 构建输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 2s / 39 actionable tasks: 5 executed, 34 up-to-date
```

## 3. 决策与理由
- **`g_player_controller` 替代 `g_last_controller` 作为玩家车 controller 单一来源** [V]——`g_last_controller` 由 `proxy_set_throttle`/`proxy_set_brake`/`proxy_fixed_update` 无条件捕获，这些 hook 跑在所有车的实例上，AI 车的 `is_player_controller` 误判（0x108 非空）会污染它。`g_player_controller` 只由 `proxy_player_controls_update` 设置——`IRDSPlayerControls` 组件只挂玩家车 GameObject（Unity 机制，天然身份过滤），从 `this+0x60` 读 carInputs。
- **`proxy_set_throttle`/`proxy_set_brake` 吞输入加 `is_player` 条件** [V]——旧条件 `if (g_throttle_active) return;` 会把 AI 车 setter 的输入也吞掉，AI 车油门卡在用户踩下那一刻的值。改为 `if (is_player && g_throttle_active) return;`，AI 车透传 orig。
- **`proxy_fixed_update` 中 orig 后 `apply_inputs_to_controller` 已注释** [V]——`is_player` 判据不可靠时写 AI 车；单靠 writer 线程（2ms）是否会被 FixedUpdate（50Hz）覆盖待后续诊断决定恢复。注释里有说明。
- **WSL2 Kotlin 编译卡死修复：`gradle.properties` 加 `kotlin.compiler.execution.strategy=in-process`** [V]——Kotlin 编译守护进程 RMI loopback socket 卡死（jstack: `LoopbackNetworkInterface.socketCreate` TIMED_WAITING）。`kotlin.daemon.enabled=false` 无效（KGP 2.4.0 仍会启动守护进程）；改为在 Gradle daemon 内联编译绕过。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M14/M23 前全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile` 读 LSPosed daemon 目录、模块进程写公共 `/sdcard/`（scoped storage EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式——均不再试。
- **（本会话）只给 `proxy_set_throttle`/`proxy_set_brake` 加 `is_player` 条件** → AI 车仍被误控 [V]——根因不在吞输入，而在 writer 线程/`apply_inputs_to_controller` 写了 AI 车字段（`g_last_controller` 被 AI 车污染）。必须同时换 controller 来源，单改吞输入不够。
- **（本会话）`kotlin.daemon.enabled=false` 不能禁用 Kotlin 编译守护进程** [V]——KGP 2.4.0 会无视它仍启动守护进程，RMI 通信依旧卡死。改用 `kotlin.compiler.execution.strategy=in-process`。

## 5. 已知坑
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——AI 车的 `IRDSCarControllInput.playerControls` 可能非空，不能作为玩家车判据。玩家车判据走 `IRDSPlayerControls.Update`（只挂玩家车）。若将来要恢复 `proxy_fixed_update` 写输入，必须用 `g_player_controller` 而非 `is_player`。
- **⚠️ Release 构建会 R8 重命名 res/raw 下的 mp3** [V]——音乐类资源一律放 `assets/`。
- **⚠️ 游戏进程 ClassLoader 取不到 APK 内资源** [V]——取 APK 内文件（.so、assets、raw）走 `NativeBridge.resolveModuleApkPath()` + ZipFile。
- **⚠️ WSL2 下 Kotlin 编译守护进程 RMI loopback 卡死** [V]——已在 `gradle.properties` 用 `kotlin.compiler.execution.strategy=in-process` 规避。若该设置被移除/失效，构建会在 `:app:compileDebugKotlin` 卡住无 CPU 占用。
- **⚠️ HandleABS 是死代码** [V]——全 so 无 `bl 0x1a5763c` 调用。ABS 真正实现位置未知。
- **⚠️ DoGearShifting 不能整段跳过** [V]——游戏起步需要其离合器结合/挂挡逻辑。
- **⚠️ 油门＞0 时 AI 车被误控** —— 本次已修复（`g_player_controller`），多人实测正常。若将来重现，检查 writer 线程是否又写了非 `g_player_controller` 来源。
- **⚠️ 横屏 `displayMetrics.heightPixels` 返回短边** [V]。
- **⚠️ versionCode 用 CLAUDE.md M8 表格锚点反推** [V]——Beta 3=`100230`。
- **⚠️ ConfigActivity 进程不被 LSPosed 注入** [V]。
- **⚠️ PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **⚠️ ConfigProvider.kt 现在非废弃** [V]——NPatch 回退路径，不可删。manifest provider 声明必须保留。
- **⚠️ 共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。

## 6. 下一步（有序）
1. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true` + tag `v1.0.0-Beta-3`。

## 7. 留给用户的开放问题
- `proxy_fixed_update` orig 后写 `apply_inputs_to_controller` 被注释——若真机发现油门响应迟滞（writer 线程 2ms vs FixedUpdate 50Hz 覆盖），需要恢复并改用 `g_player_controller`。当前真机反馈正常，未观察到迟滞。
- 320kbps 替换曲是否够用？若需换曲，直接替换 `app/src/main/assets/f1_music.mp3` 重建即可。