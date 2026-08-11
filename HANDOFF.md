# HANDOFF — 读全文再开始干活

生成时间: 2026-08-12T00:41:00+08:00 · Git HEAD: `eb9fc09`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `eb9fc09` (2026-08-12)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `eb9fc09`；变了说明快照可能过期。
- 工作区: 干净（本会话改动已全部提交 push）。
- 先读: `CLAUDE.md` + `README.md` + 本文件。

## 1. 当前目标
**Beta 3 已发布闭环完成**（本会话完成）。待办：① 调查 NPatch 注入时序依赖（用户报告"必须先开一次 NPatch 才生效"）；② 修 CI workflow 的 `generate_release_notes: true` 覆盖 body 的坑（下个版本发布前）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **版本号已 bump 到 Beta 3**——commit `93140a0`（`chore(release): bump to 1.0.0 Beta 3 (versionCode 100230)`）。`app/build.gradle.kts` + `module.prop` 均已同步 `versionName=1.0.0 Beta 3` / `versionCode=100230`。
- [V] **Git tag 已建并推送**——`v1.0.0-Beta-3`（annotated tag）。
- [V] **README 版本历史已更新**——commit `eb9fc09`（`docs: 更新 README 版本历史 Beta 3`，Beta 3 条目从"开发中"改为"2026-08-12"并补全变更）。
- [V] **CI 全绿**——`gh run list` 显示 main + v1.0.0-Beta-3 两个 run 均 `completed/success`。
- [V] **Release 已发布**——`v1.0.0-Beta-3` Pre-release，asset `Ala.Mobile.Tool.v1.0.0.Beta.3.apk` 已上传。
- [V] **Release body 已修正为完整 Release Notes**——`gh release edit v1.0.0-Beta-3 --notes-file` 覆盖了 GitHub 自动生成的占位 body。含 Features / Bug Fixes / Performance / Known Issues / 安装。
- [V] **工作区干净**——`git status --short --branch` 输出 `## main...origin/main`，无未提交改动。

### 构建输出（本次交接 run）
```
本会话未本地构建（Bump 后直接用 CI 构建 release），CI run 全绿即验证。
```

## 3. 决策与理由
- **直接用 CI 构建 release，不本地 assembleRelease** [V]——用户明确要求"用 CI"。本地构建被用户拒绝，改走 CI workflow 自动构建 tag。
- **Release body 用 `gh release edit --notes-file` 后置覆盖** [V]——CI workflow 的 `Upload to Release` 步骤带 `generate_release_notes: true`，GitHub 自动生成 changelog **覆盖** `body` 字段，导致手工写的 Release Notes 变占位。发布后手动 `gh release edit` 修正。
- **Release Notes 按代码实况重写** [V]——用户指出初稿不实（"原生 TC/ABS 不对，ABS 没实现被注释；图标自定义哪有这个"）。派 Explore Agent 逐个 commit 核对代码，确认 ABS 开关、手动换挡开关在 UI 被注释（ConfigurePage.kt `/* */`），TC 开关生效；"图标自定义"实际是配置页**开关图标**自定义（CustomIcons.kt 5 个 SVG 图标），不是应用图标。据此重写。

## 4. 失败的尝试 — 不要再试
- **（前向搬运 M14/M23-29 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile`、模块进程写公共 `/sdcard/`（EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式、只给 setter 加 `is_player` 条件（AI 车仍误控）、`kotlin.daemon.enabled=false`（KGP 2.4.0 无视仍卡死）、EULA 存 remote prefs（pm clear 清不掉）、EulaDialog 在 Scaffold 外渲染（灰屏）、只用 `System.getProperty(MODULE_LOADED_FLAG)` 判激活（ConfigActivity 永远 false）——均不再试。
- **（M28/M29）`getScope()` 判激活** [V]——NPatch 返回记忆的 scope（关开关不清），误判 LSPOSED。改用 frameworkName。
- **（M29）`getRunningTargets()` 判激活** [V]——游戏没跑时返回空数组，误判未激活。改用 frameworkName。
- **（M29）Non-root 标记写 remote prefs** [V]——pm clear 清不掉，清数据后仍显示 Non-root 已激活。改只存 filesDir。
- **（本会话）`gh release edit --body-file`** [V]——该 flag 不存在（`unknown flag: --body-file`），正确 flag 是 `--notes-file`/`-F`。
- **（本会话）靠 AI 记忆写 Release Notes** [V]——初稿把被注释的 ABS/手动换挡写成已实现、把开关图标自定义写成应用图标自定义。必须逐个 commit 对照当前 HEAD 代码核实，不能凭 commit message 或记忆。

## 5. 已知坑
- **⚠️ CI `generate_release_notes: true` 覆盖 body** [V]——workflow `build.yml` 第 110 行 `Upload to Release` 带 `generate_release_notes: true`，自动 changelog 覆盖 `body` 字段，手工 Release Notes 变占位。下个版本发布前应去掉 `generate_release_notes`（或删掉 body 让 GitHub 自动生成），再考虑 CI 是否直接读版本号的 Release Notes 文件。
- **⚠️ NPatch 注入时序依赖** [V]——用户实测：清模块+游戏数据 + 保持 NPatch 不打开 → 开模块再开游戏模块不生效；一旦点开 NPatch 一次就永久生效。疑似 NPatch 管理器首次启动才注册模块。（见下一节待调查）
- **⚠️ LSPosed 共存版双 ClassLoader** [V]——`System.setProperty(NATIVE_INSTALLED_FLAG)` 进程级标记避免双注入。
- **⚠️ `OverlayDialog` 必须在 miuix Scaffold `popupHost` 槽位内渲染** [V]。
- **⚠️ EULA 只存 filesDir** [V]——remote prefs 清不掉；改 `EULA_SECTIONS` 必须递增 `EulaManager.EULA_VERSION`（当前 = 2）。
- **⚠️ ABS/手动换挡开关在 UI 被注释** [V]——ConfigurePage.kt `/* */`。ABS: HandleABS 是死代码；手动换挡: DoGearShifting 导致出不了 P 房。都待找到正确入口点后恢复。
- **⚠️ miuix-blur minSdk=33 而项目 minSdk=26** [V]；**⚠️ `MiuixScrollBehavior()` 不自动生效** [V]；**⚠️ WSL2 Kotlin 编译守护进程 RMI loopback 卡死** [V]（`gradle.properties` in-process 规避）。
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——玩家车判据走 `g_player_controller`。
- **⚠️ Release R8 重命名 res/raw 下 mp3** [V]——音乐放 `assets/`；**⚠️ 游戏进程 ClassLoader 取不到 APK 资源** [V]——走 `resolveModuleApkPath()` + ZipFile。
- **⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）；**⚠️ versionCode Beta 3=`100230`** [V]。
- **⚠️ README 的 QQ 群链接是占位符** [?]——`qunpro/share?appKey=...` 是假的，需替换为 OverviewPage.kt 里真实的 `qun.qq.com/universal-share` 链接。
- **⚠️ `App.KEY_NONROOT_CONFIRMED` 常量已无引用** [V]——confirmNonRoot 只写 filesDir，App.kt:61 的常量可删（无害残留）。

## 6. 下一步（有序）
1. **修 CI workflow 的 `generate_release_notes` 坑**（下个版本发布前）：`build.yml` 第 110 行去掉 `generate_release_notes: true`，或改为 CI 直接读版本号对应的 Release Notes 文件，避免手工 Notes 被覆盖。
2. **调查 NPatch 注入时序依赖**（用户报告）：清模块+游戏数据 + 不开 NPatch → 模块不生效；点开 NPatch 一次 → 永久生效。假设：NPatch 管理器第一次启动时才向 patched 游戏 APK 注册模块/写注入配置。验证路径：`references/NPatch` 的 `patch-loader` / `meta-loader` 源码。
3. **补 README `[image-N]` 占位符素材**：image-1 单踏板录屏、image-2 编辑模式录屏、image-3 三页设置截图。并入 `assets/videos/` 或 `assets/screenshots/`。
4. **清理 `App.KEY_NONROOT_CONFIRMED` 残留常量**（低优先级，无害）。

## 7. 留给用户的开放问题
- NPatch 注入时序依赖根因待查（见第 6 节）——用户需确认 NPatch 管理器"第一次打开才注册"是否符合预期。
- `proxy_fixed_update` 的 `apply_inputs_to_controller` 已注释——油门迟滞需恢复时用 `g_player_controller`。当前无问题。
- miuix-blur 在老设备（Android 12 以下）自动降级纯色底——是否需要显式 `LocalEnableBlur` false 待讨论。
- 320kbps 替换曲是否够用？换曲直接替换 `assets/f1_music.mp3`。
- 「关于」Toast 读 `BuildConfig.VERSION_NAME`（Beta 3）——发布时自动跟随。