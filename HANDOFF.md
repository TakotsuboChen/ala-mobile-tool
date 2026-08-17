# HANDOFF — 读全文再开始干活

生成时间: 2026-08-18T00:30:00+08:00 · Git HEAD: `d115618`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `d115618` (2026-08-18)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `d115618`；变了说明快照可能过期
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**修复 EULA 启动门控缺失 + 协议弹窗体验改进**：重构时丢失的 EULA 启动检查恢复到概览页 popupHost；协议弹窗需滚到底才能点同意；激活弹窗不再抢盖 EULA。已完成并提交、已 adb 安装。遗留：真机验证待用户确认；M45 的 position 合并修复、janky 根因仍待排查。

## 2. 已验证状态 — 工作实际停在哪

- [V] **EULA 启动门控恢复** — `d115618` 改动 `OverviewPagerMiuix.kt`：加 `eulaAccepted` state（启动时 `EulaManager.isAccepted(context)`），在 `Scaffold.popupHost` 里先渲染 `EulaDialog` 再渲染 `MiuixPopupHost()`，未同意时 `ActivationCard` 不弹激活弹窗（`eulaAccepted` 门控），点同意后 `LaunchedEffect(eulaAccepted)` 补弹激活弹窗。
- [V] **协议弹窗滚到底才能同意** — `d115618` 改动 `EulaDialog.kt`：`rememberScrollState()` + `derivedStateOf` 计算 `hasScrolledToBottom`（`maxValue==0 || value>=maxValue`），"同意"按钮 `enabled = hasScrolledToBottom`，未到底文字"请先阅读协议"、到底变"同意"。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL（1s），EXIT_CODE=0。
- [V] **构建通过** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（2s）。
- [V] **adb 安装成功** — `adb install -r app-debug.apk` → Success（设备 `381QYFCN22B9A`）。
- [V] **工作区干净** — `git status` 无未提交改动，`main` 与 `origin/main` 同步。
- [?] **M46 设置页 UI 重组** — `41ec5ee` 已提交，真机验证未确认。
- [?] **M45 移除"显示悬浮窗"开关 + 响应曲线收回** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — `resolveLatestSettings()` 的 `mergePositionFromLocalPublic()` 公开化，用户未确认是否生效。
- [?] **切换模式后位置丢失** — 用户反馈"切双踏板再切回单踏板，位置/大小变成默认状态"。上述修复已尝试解决，用户未确认。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 1s, EXIT_CODE=0
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 2s
adb install -r app-debug.apk → Success（设备 381QYFCN22B9A）
```

## 3. 决策与理由

- **EULA 门控放概览页 popupHost 而非 ConfigActivity 白屏阻断** [V]——用户明确要求"进去在概览页弹窗，不要白屏"。`OverviewPagerMiuix` 的 `Scaffold.popupHost` 先渲染 `EulaDialog` 再渲染 `MiuixPopupHost()`，EULA 叠在主界面之上。否决方案：ConfigActivity 里 `if (!eulaAccepted) EulaDialog else navDisplay`（白屏阻断，用户否决）。
- **用 `eulaAccepted` 门控激活弹窗而非靠 zIndex** [V]——miuix `OverlayDialog` 的 zIndex 按 `nextZIndex++` 递增，后加入 dialogStates 的弹窗 zIndex 更高。`ActivationCard` 的 `LaunchedEffect` 在 EULA 之后完成检测，激活弹窗后加入、zIndex 反超盖住 EULA。用 `eulaAccepted` 参数传给 `ActivationCard`，未同意时不设 `showNonRootDialog = true`，从源头阻断。
- **`LaunchedEffect(eulaAccepted)` 补弹激活弹窗** [V]——`LaunchedEffect(Unit)` 只执行一次，EULA 未同意时检测已完成但没弹窗。用户点同意后 `eulaAccepted` 变 true，此 effect 补弹激活弹窗（如果 status 已是 INACTIVE）。
- **"同意"按钮文字"请先阅读协议"** [V]——6 字，不换行。试过"请先阅读完协议"（7 字，"议"挤到第二行）、"请先读完协议"（6 字）、"滑到底部解锁"（6 字），最终用户定"请先阅读协议"。

## 4. 失败的尝试 — 不要再试

- [X] **ConfigActivity 白屏阻断式 EULA 门控** — 在 `MiuixTheme` 内 `Scaffold` content 里 `if (!eulaAccepted) EulaDialog else navDisplay`。功能正确但用户否决：要"进去在概览页弹窗"而非白屏不进去。不要再做白屏阻断。
- [X] **靠 popupHost 渲染顺序保证 EULA zIndex 高于激活弹窗** — 先写 `EulaDialog` 再写 `MiuixPopupHost()` 不保证 EULA 在上。miuix `nextZIndex++` 让后加入的弹窗（激活弹窗）zIndex 更高。必须用 `eulaAccepted` 门控激活弹窗触发。
- [X] **`useLegacyPackaging = true`** — M40 照搬 KernelSU 加的，导致 `libshadowhook.so not found` 的 native 加载失败。不要再加。
- [X] **`readFromTargetProcess` 作为 rebuild 唯一配置来源** — daemon 写入滞后于广播，rebuild 读到旧 pedalMode/curve。`rebuildFromConfigChange` 和 `toggleOverlays` 必须优先用广播 JSON。不要再单用。
- [X] **手写 SwitchRow/SliderRow → miuix preference 组件** — M38 M39 已验证，换了仍 22-38% janky。
- [X] **关 blur / 移除 rememberContentReady / ModConfig.read 异步 / 各种 janky 优化** — 所有 M38-M40 的 janky 修复尝试均无效，详见旧 HANDOFF.md。
- [X] **手工 `gh release edit` 事后覆盖镜像 body** — 永远晚于 CI 内 sync。不要再依赖事后覆盖，必须 Notes 先于 CI 存在。
- [X] **`gh release edit --name`** — flag 不存在，用 `--title`。
- [X] **`./gradlew :app:installDebug`** — 报 "No connected devices!"，Gradle adb 与系统 adb 不一致。直接用系统 `adb install -r <apk>`。

## 5. 已知坑

- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。daemon 异步绑定可能延迟，广播比 remote 先到。`readFromTargetProcess` 读 remote（daemon 旧值）≠ 刚写入的配置。解决方案：`rebuildFromConfigChange` 和 `toggleOverlays` 优先用广播 JSON。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position（游戏进程拖拽时 `saveOverlayPosition` 写本地）。用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position，否则重建后位置/大小丢回默认。`resolveLatestSettings()` 已处理此逻辑。
- ⚠️ **AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错。
- ⚠️ **NDK 29 下载失败** [V]——Clash TUN TLS 干扰，用本地 NDK 26 替代。
- ⚠️ **miuix SwitchPreference 在我们的 app 中 22% janky，KSU 同版本 0.10%** [V]——仍待排查，R8 优化差异可能。
- ⚠️ **lint 的 NewApi 检查会拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 KernelSU minSdk 更高，其 API 调用可能超出我们的 minSdk。新增高版本 API 调用时用 `values-vNN` 拆分或 `SDK_INT` 守卫。
- ⚠️ **LSPosed `latestReleaseTime` 只认 stable** [V]——Beta/Alpha 阶段 `latestReleaseTime` 永远是 `1970-01-01T00:00:00Z`，列表排最后。只有发 stable release 才能置顶。

## 6. 下一步（有序）

1. **真机验证 EULA 启动门控** — 用户确认：首次/未同意状态打开模块 → 概览页弹协议（非白屏）；EULA 弹窗不被激活弹窗抢盖；"同意"按钮初始灰显"请先阅读协议"，滑到底变"同意"可点击；点同意后 EULA 消失，激活弹窗（如 INACTIVE）随后出现。
2. **真机验证设置页"不同意"后重开** — 用户确认：设置页点"不同意"退出后重新打开模块 → 概览页弹 EULA。
3. **真机验证 M46 设置页 UI 重组** — 用户确认：设置页无"日志"/"调试"小标题、无"关于"卡片，两组功能项布局正确。
4. **真机验证 M45 改动** — 用户确认："显示悬浮窗"开关已移除；线性踏板关闭时响应曲线收起。
5. **验证 position 合并修复** — 用户确认"切双踏板再切回单踏板，位置/大小是否保持"。
6. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB）。

## 7. 留给用户的开放问题

- EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 设置页点"不同意"后退出重开是否正确弹协议？
- M46 设置页 UI 重组（两组无小标题）的表现是否满意？
- M45 改动（移除"显示悬浮窗"开关 + 响应曲线收回）的表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 1970/置顶问题等正式版解决——是否计划近期发 stable release？