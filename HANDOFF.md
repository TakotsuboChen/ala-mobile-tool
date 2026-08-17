# HANDOFF — 读全文再开始干活

生成时间: 2026-08-17T23:50:32+08:00 · Git HEAD: `3a4e3f5`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `3a4e3f5` (2026-08-17)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `3a4e3f5`；变了说明快照可能过期
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**设置页 UI 重组**：去除"日志"/"调试"两个小标题、去除"关于"卡片，保留 4 个功能项重组为两个 Card 组（启用日志+导出并分享日志 / 清除激活标记+用户协议），两组间保持 12dp 间隔。已完成并提交、已 adb 安装。遗留：真机验证待用户确认；M45 的 position 合并修复、janky 根因仍待排查。

## 2. 已验证状态 — 工作实际停在哪

- [V] **设置页 UI 重组** — `41ec5ee` 改动 `SettingsPagerMiuix.kt`（删两个 `SmallTitle`、删"关于" `ArrowPreference`、拆两个 Card）、`SettingsPager.kt`（删 `onOpenAbout`/`navigator` 参数）、`MainScreen.kt`（调用点同步）。清理 import：`PaddingValues`/`SmallTitle`/`Icons.Rounded.Info`。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL（9s）；`./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（3s）。
- [V] **adb 安装成功** — `adb install -r app/build/outputs/apk/debug/app-debug.apk` → Success（设备 `381QYFCN22B9A`）。注意：`./gradlew :app:installDebug` 报 "No connected devices!"，但系统 adb 能看到设备——Gradle 用 SDK 自带 adb 与 PATH 里 adb 不一致，直接用系统 adb 装 APK 绕过。
- [V] **README 同步** — `3a4e3f5` 设置页表格删除"关于"行。
- [V] **工作区干净** — `git status` 无未提交改动，`main` 与 `origin/main` 同步。
- [V] **保留项** — `Route.About` 路由定义与 `AboutScreen.kt` 保留（删除设置页入口后成为不可达代码，但删除页面文件是不可逆操作，且与其他 pager 驱动的 Route 保留模式一致）。
- [?] **M45 移除"显示悬浮窗"开关 + 响应曲线收回** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — 上一会话 `resolveLatestSettings()` 从本地 externalFilesDir 合并 position，`mergePositionFromLocalPublic()` 公开化。用户未确认是否生效。
- [?] **切换模式后位置丢失** — 用户反馈"启动时调过位置大小的单踏板，切双踏板再切回单踏板，位置/大小变成默认状态"。上述 position 合并修复已尝试解决，用户未确认。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 9s
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 3s
adb install -r app-debug.apk → Success（设备 381QYFCN22B9A）
./gradlew :app:installDebug → FAILED: No connected devices!（Gradle adb 与系统 adb 不一致）
```

## 3. 决策与理由

- **删"关于"项而非保留** [V]——用户明确要求去除关于卡片。`Route.About` 唯一入口就是设置页"关于"项，删除后成为不可达代码；但保留路由定义与 `AboutScreen.kt`，避免不可逆删除，且与其他 pager 驱动的 Route 保留模式一致。
- **删 `onOpenAbout`/`navigator` 参数** [V]——`navigator` 在 `SettingsPager` 的唯一用途就是 push About 页，删除后成为死参数。`MainScreen` 里 `navController` 还有其他用途（OverviewPager/ConfigurePager/BackHandler），不受影响。
- **两个 Card 组间距用外层 `spacedBy(12.dp)`** [V]——与之前 Section 间间距一致，无需额外调整。
- **JSON 兼容无需迁移** [V]——旧配置残留 `show_overlay` key 无害（read/fromJson 不再读），`write()` 全新构造 JSONObject，写一次后旧 key 自然消失。

## 4. 失败的尝试 — 不要再试

- [X] **`useLegacyPackaging = true`** — M40 照搬 KernelSU 加的，导致 `libshadowhook.so not found` 的 native 加载失败。不要再加。
- [X] **`readFromTargetProcess` 作为 rebuild 唯一配置来源** — daemon 写入滞后于广播，rebuild 读到旧 pedalMode/curve。`rebuildFromConfigChange` 和 `toggleOverlays` 必须优先用广播 JSON。不要再单用 `readFromTargetProcess`。
- [X] **手写 SwitchRow/SliderRow → miuix preference 组件** — M38 M39 已验证，换了仍 22-38% janky。
- [X] **关 blur / 移除 rememberContentReady / ModConfig.read 异步 / 各种 janky 优化** — 所有 M38-M40 的 janky 修复尝试均无效，详见旧 HANDOFF.md。
- [X] **手工 `gh release edit` 事后覆盖镜像 body** — 永远晚于 CI 内 sync（`sync-lsposed needs: build`），镜像拿到默认安装说明。不要再依赖事后覆盖，必须 Notes 先于 CI 存在。
- [X] **`gh release edit --name`** — flag 不存在，全量同步时 edit 分支报 `unknown flag: --name`。用 `--title`。
- [X] **`./gradlew :app:installDebug`** — 报 "No connected devices!"，但系统 adb 能看到设备。Gradle 用 SDK 自带 adb 与 PATH 里 adb 不一致。直接用系统 `adb install -r <apk>`。

## 5. 已知坑

- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。但 daemon 异步绑定可能延迟，广播比 remote 先到。`readFromTargetProcess` 读 remote（daemon 旧值）≠ 刚写入的配置。解决方案：`rebuildFromConfigChange` 和 `toggleOverlays` 优先用广播 JSON。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position（游戏进程拖拽时 `saveOverlayPosition` 写本地）。用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position，否则重建后位置/大小丢回默认。`resolveLatestSettings()` 已处理此逻辑。
- ⚠️ **AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错。
- ⚠️ **NDK 29 下载失败** [V]——Clash TUN TLS 干扰，用本地 NDK 26 替代。
- ⚠️ **miuix SwitchPreference 在我们的 app 中 22% janky，KSU 同版本 0.10%** [V]——仍待排查，R8 优化差异可能。
- ⚠️ **lint 的 NewApi 检查会拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 KernelSU minSdk 更高，其 API 调用可能超出我们的 minSdk。新增高版本 API 调用时用 `values-vNN` 拆分或 `SDK_INT` 守卫。
- ⚠️ **LSPosed `latestReleaseTime` 只认 stable** [V]——Beta/Alpha 阶段 `latestReleaseTime` 永远是 `1970-01-01T00:00:00Z`，列表排最后。只有发 stable release 才能置顶。这是 LSPosed 生态规则，不是我们的 bug。

## 6. 下一步（有序）

1. **真机验证设置页 UI 重组** — 用户确认：设置页无"日志"/"调试"小标题、无"关于"卡片；"启用日志+导出并分享日志"一组、"清除激活标记+用户协议"一组，两组间有间隔。
2. **真机验证 M45 改动** — 用户确认：配置页"Overlay 控件"下不再有"显示悬浮窗"开关；"线性踏板"设为"关闭"时单/双踏板调节项和整个"响应曲线" Section 一起收起；切回单/双踏板时响应曲线重新展开。
3. **验证 position 合并修复** — 用户确认"切双踏板再切回单踏板，位置/大小是否保持"。如果仍丢回默认，需排查 `resolveLatestSettings()` 的 `mergePositionFromLocalPublic` 是否正确合并了 position。
4. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB），见 M40 HANDOFF。

## 7. 留给用户的开放问题

- 设置页 UI 重组（两组无小标题）的表现是否满意？需要真机验证。
- 移除"显示悬浮窗"开关 + 响应曲线收回的 UI 表现是否满意？需要真机验证。
- 切换模式后单踏板位置丢失问题是否已修复？需要用户真机验证。
- 自定义曲线图表布局（分隔线/卡片结构/轴标签位置）是否满意？之前用户说"不改了"，但交互细节可继续调整。
- 1970/置顶问题等正式版解决——是否计划近期发 stable release？
