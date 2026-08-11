# HANDOFF — 读全文再开始干活

生成时间: 2026-08-11T16:00:00+08:00 · Git HEAD: `8339a3f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `8339a3f` (2026-08-11)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `8339a3f`；变了说明快照可能过期。
- 工作区: 已 commit + push（含 `.handoffs/` 归档），`HANDOFF.md` 新写待提交。
- 先读: `CLAUDE.md` M25 条目 + 本文件。

## 1. 当前目标
**配置页 UI 视觉大改（M25）** 已完成并真机验证：底栏切 tab 卡顿/错乱修复（KernelSU 式 animateScrollBy + isNavigating 守卫）、下滑顶栏折叠标题居中（补 nestedScroll）、顶栏+底栏毛玻璃（miuix-blur，blurRadius=12f 约 KernelSU 一半）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **构建全绿**——`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`（exit 0）。
- [V] **APK 已安装**——`adb install -r app/build/outputs/apk/debug/app-debug.apk` → `Success`（设备 381QYFCN22B9A）。
- [V] **真机验证通过**——用户确认：底栏连点不卡不错乱（"已修复"）、下滑顶栏标题居中（"好了"）、顶栏模糊正确、底栏"只有半透明没有模糊"→补 pager `layerBackdrop` 后修复（"可以了"）。
- [V] **工作 commit `9a09f22` 已 push**——`feat: 照搬 KernelSU 模糊顶栏/底栏实现`。
- [V] **持久文档 commit `8339a3f` 已 push**——CLAUDE.md + README.md 更新 M25。
- 工作区: 仅剩新 HANDOFF.md + `.handoffs/` 归档待提交。

### 构建输出（本次交接 run 的真实输出）
```
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 7s / 39 actionable tasks: 6 executed
```

## 3. 决策与理由
- **底栏切 tab 动画照搬 KernelSU `BottomBar.kt` 的 `animateScrollBy`** [V]——旧 `animateScrollToPage` 连点不取消前一个动画、高亮与页面错乱；新实现 `scrollPixels` 显式像素距离 + `tween(EaseInOut, 100*dist+100)` + `isNavigating`/`navJob==myJob` 守卫，连点先 cancel。
- **三个页面 LazyColumn 补 `nestedScroll(scrollBehavior.nestedScrollConnection)`** [V]——旧代码只创建 `MiuixScrollBehavior()` 没连 scroll 事件，顶栏永远展开；KernelSU `HomePagerMiuix` 的 LazyColumn 有这行。
- **毛玻璃用 miuix-blur 独立 artifact + manifest overrideLibrary** [V]——`miuix-ui` 不包含 blur 类；KernelSU 同样 `miuix-blur-android:0.9.3` + `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur"/>`（库 minSdk=33 vs 项目 26，`isRenderEffectSupported()` 运行时降级安全）。
- **blurRadius 25f→12f（约 1/2）** [V]——用户要求"模糊度调低大约 1/2"，背景叠 `surface.copy(0.87f)` 保留。
- **底栏模糊需要 pager 侧 `Box(layerBackdrop)`** [V]——`BlurredBar` 的 `textureBlur` 从共享 backdrop 的 layer 读内容，缺 `layerBackdrop` 端则无内容可模糊（只显示半透明叠色）。顶栏无需这步（页面内容本身有 layerBackdrop）。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M14/M23/M24 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile` 读 LSPosed daemon 目录、模块进程写公共 `/sdcard/`（scoped storage EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式、只给 setter 加 `is_player` 条件（AI 车仍被误控）、`kotlin.daemon.enabled=false`（KGP 2.4.0 无视仍卡死）——均不再试。
- **（本会话）底栏只包 `BlurredBar` + `NavigationBar color=Transparent`** → 只有半透明无模糊 [V]——根因 pager 内容缺 `layerBackdrop`，backdrop 的 graphics layer 为空。必须 `pagerContent` 的 Box 加 `Modifier.layerBackdrop(backdrop)`。

## 5. 已知坑
- **⚠️ miuix-blur minSdk=33 而项目 minSdk=26** [V]——manifest 用 `tools:overrideLibrary` 绕过（照搬 KernelSU）。库内部 `isRenderEffectSupported()` = SDK>=S，Android 12 以下返回 false 自动降级。
- **⚠️ `BlurredBar` 的模糊层依赖对端 `layerBackdrop`** [V]——`rememberLayerBackdrop` 捕获内容靠 `layerBackdrop` 修饰符，两端必须用同一个 backdrop 实例。
- **⚠️ `MiuixScrollBehavior()` 不自动生效** [V]——必须同时在 LazyColumn 挂 `.nestedScroll(scrollBehavior.nestedScrollConnection)`。
- **⚠️ miuix-blur 0.9.3 的 blurRadius 已 clamp 到 [0,150dp]** [V]——KernelSU 的 25f / 我们的 12f 都在范围内。
- **⚠️ WSL2 下 Kotlin 编译守护进程 RMI loopback 卡死** [V]——`gradle.properties` `kotlin.compiler.execution.strategy=in-process` 规避。
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——玩家车判据走 `g_player_controller`（IRDSPlayerControls.Update 设置）。
- **⚠️ Release 构建 R8 重命名 res/raw 下 mp3** [V]——音乐放 `assets/`。
- **⚠️ 游戏进程 ClassLoader 取不到 APK 内资源** [V]——走 `NativeBridge.resolveModuleApkPath()` + ZipFile。
- **⚠️ HandleABS 是死代码** [V]；**⚠️ DoGearShifting 不能整段跳过** [V]；**⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）；**⚠️ versionCode Beta 3=`100230`** [V]。
- **⚠️ 共存版双 ClassLoader** [?]——`markNativeInstalled()` 守卫拦第二个。

## 6. 下一步（有序）
1. **发 Beta 3**——versionCode `100230`，versionName `1.0.0 Beta 3`。三文件同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）+ CI workflow `prerelease: true`。M25 的 UI 改动会包含进 Beta 3。

## 7. 留给用户的开放问题
- `proxy_fixed_update` orig 后写 `apply_inputs_to_controller` 被注释——若油门迟滞再出现需恢复（用 `g_player_controller`）。当前无问题。
- miuix-blur 毛玻璃在 Android 12 以下设备自动降级为纯色底——是否需要为老设备显式设 `LocalEnableBlur` false 可后续讨论。
- 320kbps 替换曲是否够用？换曲直接替换 `app/src/main/assets/f1_music.mp3`。