# HANDOFF — 读全文再开始干活

生成时间: 2026-08-18T01:46:43+08:00 · Git HEAD: `2626344`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `2626344` (2026-08-18)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `2626344`；变了说明快照可能过期
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**修复"清除激活标记"联动清除 EULA 同意状态的 bug**：`LsposedStatus.clearAll` 原本显式调用 `EulaManager.clear`，导致用户只想重置激活状态时协议同意标记被一并清掉、下次启动重新弹协议。已修复并提交、已 adb 安装。遗留：真机验证待用户确认；M45-M47 的真机验证项仍待确认。

## 2. 已验证状态 — 工作实际停在哪

- [V] **根因定位** — `LsposedStatus.kt:177`（旧行号）`clearAll` 内 `EulaManager.clear(context)` 显式调用，把激活清除与 EULA 重置耦合。存储层早已分离（`nonroot_confirmed.flag` vs `eula_accepted_version.flag`），逻辑层仍耦合。
- [V] **修复** — `b99f83b` 改动 `LsposedStatus.kt`：移除 `clearAll` 里的 `EulaManager.clear(context)` 调用，补注释说明两者语义独立。`EulaManager.kt`：更新 `clear` 的 KDoc，去掉"由 clearAll 调用"的过时说明。
- [V] **编译通过** — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL（2s），EXIT_CODE=0。
- [V] **构建通过** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL（1s）。
- [V] **adb 安装成功** — `adb install -r app/build/outputs/apk/debug/app-debug.apk` → Success。
- [V] **工作区干净** — `git status` 无未提交改动，`main` 与 `origin/main` 同步。
- [?] **M47 EULA 启动门控** — `d115618` 已提交，真机验证未确认。
- [?] **M46 设置页 UI 重组** — `41ec5ee` 已提交，真机验证未确认。
- [?] **M45 移除"显示悬浮窗"开关 + 响应曲线收回** — `7912ac3` 已提交，真机验证未确认。
- [?] **position 合并修复** — `resolveLatestSettings()` 的 `mergePositionFromLocalPublic()` 公开化，用户未确认是否生效。
- [?] **切换模式后位置丢失** — 用户反馈"切双踏板再切回单踏板，位置/大小变成默认状态"。上述修复已尝试解决，用户未确认。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL in 2s, EXIT_CODE=0
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 1s
adb install -r app/build/outputs/apk/debug/app-debug.apk → Success
```

## 3. 决策与理由

- **从 `clearAll` 移除 `EulaManager.clear` 而非保留联动** [V]——激活标记与 EULA 同意是两个语义独立的设置项，存储层早已分离（各自 filesDir flag 文件），设置页也有独立的"用户协议"入口处理 EULA 重置。`clearAll` 联动清 EULA 是早期单入口时代的遗留，无设计意图支撑。否决方案：保留联动但在 UI 上拆提示——增加复杂度且语义仍混乱。
- **同步更新 README 描述** [V]——README L193 原写"删除 Non-root 确认标记与 EULA 标记"，修复后不准确，改为"不碰 EULA 同意状态"。

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

1. **真机验证本次修复** — 用户确认：设置页点"清除激活标记" → Toast"已清除激活标记" → 重开模块**不应弹协议**（EULA 仍已同意）；点"用户协议"入口才会重新弹协议确认。
2. **真机验证 M47 EULA 启动门控** — 用户确认：首次/未同意状态打开模块 → 概览页弹协议（非白屏）；EULA 弹窗不被激活弹窗抢盖；"同意"按钮初始灰显"请先阅读协议"，滑到底变"同意"可点击；点同意后 EULA 消失，激活弹窗（如 INACTIVE）随后出现。
3. **真机验证设置页"不同意"后重开** — 用户确认：设置页点"不同意"退出后重新打开模块 → 概览页弹 EULA。
4. **真机验证 M46 设置页 UI 重组** — 用户确认：设置页无"日志"/"调试"小标题、无"关于"卡片，两组功能项布局正确。
5. **真机验证 M45 改动** — 用户确认："显示悬浮窗"开关已移除；线性踏板关闭时响应曲线收起。
6. **验证 position 合并修复** — 用户确认"切双踏板再切回单踏板，位置/大小是否保持"。
7. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB）。

## 7. 留给用户的开放问题

- 本次修复（清除激活标记不再清 EULA）的真机表现是否满意？
- M47 EULA 启动门控 + 滚到底才能同意的真机表现是否满意？
- 设置页点"不同意"后退出重开是否正确弹协议？
- M46 设置页 UI 重组（两组无小标题）的表现是否满意？
- M45 改动（移除"显示悬浮窗"开关 + 响应曲线收回）的表现是否满意？
- 切换踏板模式后单踏板位置丢失问题是否已修复？
- 1970/置顶问题等正式版解决——是否计划近期发 stable release？