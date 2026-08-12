# HANDOFF — 读全文再开始干活

生成时间: 2026-08-12T23:07:47+08:00 · Git HEAD: `8276c7b`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `8276c7b` (2026-08-12)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `8276c7b`；变了说明快照可能过期。
- 待重探的 [?]: 见第 5 节。
- 先读: `CLAUDE.md` + `README.md` + 本文件。

## 1. 当前目标
**把本仓库 release 全量同步到 LSPosed 官方模块镜像仓库** `Xposed-Modules-Repo/tools.alamobile.mod`，全自动、官方规范 tag、官网顺序正确（本会话完成）。待办：验证「打新 tag → build.yml 自动串联同步」整条链一次（尚未触发过）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **镜像仓库全部就位**——5 个 release（`100-1.0.0-Alpha-1`/`100120-1.0.0-Alpha-2`/`100210-1.0.0-Beta-1`/`100220-1.0.0_Beta_2`/`100230-1.0.0_Beta_3`），tag 全用官方规范 `<versionCode>-<versionName>`（空格转 `_`），每个带 Release Note + APK，`published_at` 2:40 系列（Alpha-1 最早 2:40:29 → Beta-3 最晚 2:40:46），**官网 modules.lsposed.org/module/tools.alamobile.mod Releases 顺序正确**（Beta-3 最上、Alpha-1 最下）。
- [V] **镜像仓库无垃圾**——无残留 `v1.0.0-*` 旧 tag、无 Draft、无多余 tag（`git ls-remote` + `gh api releases` 双确认）。
- [V] **workflow 已落地**——`.github/workflows/sync-lsposed-mirror.yml` 改为官方 tag 命名 + 按 versionCode 升序；`build.yml` 新增 `sync-lsposed` job（tag push 时 `workflow_call` 串联）。本地已 push（commit `c712787`→`8276c7b` 共 4 个 CI 修复 commit）。
- [V] **workflow 实际执行验证**——`workflow_dispatch` 全量同步成功（run #4 绿），5 个 release 由它建成（脚本读源 tag 的 `build.gradle.kts` 提取 VC/VN 拼 tag）。
- [V] **TARGET_REPO_TOKEN secret 已配**——用户确认已设置，workflow 跨仓库写镜像成功。
- [V] **官网 CDN 缓存特性确认**——官网页面显示的时间是它自己抓取时的时间戳，不是 release 真实时间；改镜像后需等约 10-20 分钟官网才刷新（用户实测"过十几分钟才行"），**不是顺序 bug**。
- [V] **工作区干净**——`git status --short --branch -uall` 输出 `## main...origin/main`，无未提交改动。

### 测试/build 输出（本次交接 run）
```
本会话只改 .github/workflows/*.yml（CI 配置），无代码构建。
workflow run #4 (workflow_dispatch, 760e834) = completed/success，5 个 release 建成。
```

## 3. 决策与理由
- **镜像 release 用官方规范 tag `<versionCode>-<versionName>`（空格转 `_`）** [V]——实测官方 bot 会主动把镜像 tag 重命名为该格式（quotelock 源 `v1.4.0`→镜像 `11-1.4.0`；coderstory 源 `v4.9`→镜像 `2047-4.9`）。workflow 直接按规范建，避免 bot 事后重命名导致顺序不可控。否决方案：沿用源 tag `v1.0.0-*` 让 bot 改，顺序依赖 bot 行为。
- **同步按 versionCode 升序** [V]——保证官网按 `published_at` 倒序显示时 Beta-3 在最前、Alpha-1 在最后。workflow 从源 tag 的 `build.gradle.kts` 提取 VC/VN 排序。
- **跨仓库写用 PAT `TARGET_REPO_TOKEN`** [V]——GitHub Actions 内置 token 只能写当前仓库；hyperisland 的 `sync-release.yml` 同款 `secrets.TARGET_REPO_TOKEN`。
- **workflow_call 必须显式声明 `secrets:`** [V]——调用方 `secrets:` 传参而定义方漏声明会 startup_failure（实测踩过）。

## 4. 失败的尝试 — 不要再试
- **（本会话全量）镜像 tag 直接用源 tag `v1.0.0-*`** [V]——官方 bot 会把镜像 release tag 重命名为 `<versionCode>-<versionName>`（quotelock/coderstory 实证），tag 名与源不一致是 bot 规范而非 bug，不要误判为 workflow 生成错误。
- **（本会话）依赖 release `created_at` 排序** [V]——官网按 `published_at`（或自身 crawl 时间）排序，且所有 release `created_at` 常同刻（同一次同步），排序不可控。改用「按 versionCode 升序逐个 create」让 `published_at` 递增。
- **（本会话）靠记忆判断官网时间戳** [X]——官网显示 `2:24 PM` 是它 CDN 缓存快照时间，不是 release 真实时间（真实 `published_at=14:40`）。刷新浏览器无效，等 10-20 分钟官网自然重抓。不要据此删重建镜像。
- **（本会话）手动本地 `gh release create` 建镜像 release** [V]——`gh release download` + `${APKS[0]}` 的 glob 在变量赋值时不展开，报 `stat : no such file or directory`，5 个全失败。改用 workflow（Actions 的 bash 里 glob 正常）。
- **（前向搬运 M14/M23-29 全部死路）** `XSharedPreferences`（API 102 禁止）、`openRemoteFile`、模块进程写公共 `/sdcard/`（EACCES）、`createPackageContext` 跨进程、ContentProvider 跨进程（LSPosed 下包不可见）、`getRemotePreferences` 用于 NPatch（无 daemon）、`bindNpatchRemoteService` 用于 embedded/local 模式、只给 setter 加 `is_player` 条件（AI 车仍误控）、`kotlin.daemon.enabled=false`（KGP 2.4.0 无视仍卡死）、EULA 存 remote prefs（pm clear 清不掉）、EulaDialog 在 Scaffold 外渲染（灰屏）、只用 `System.getProperty(MODULE_LOADED_FLAG)` 判激活（ConfigActivity 永远 false）、`getScope()`/`getRunningTargets()` 判激活（NPatch 记忆 scope / 游戏未跑返回空）、Non-root 标记写 remote prefs（pm clear 清不掉）、`gh release edit --body-file`（正确是 `--notes-file`/`-F`）、靠 AI 记忆写 Release Notes（必须对照代码）、`generate_release_notes: true` 搭配手工 `body:`（被覆盖）、自作主张裁剪 GIF/降帧率/改 README 结构（用户明令最小改动）——均不再试。

## 5. 已知坑
- **⚠️ 官网 CDN 缓存** [V]——改镜像后官网约 10-20 分钟才更新（用户实测）。刷新浏览器无效，别误判为同步失败。**不是 bug，是官网抓取延迟。**
- **⚠️ 官方 bot 会重命名镜像 release tag** [V]——改成 `<versionCode>-<versionName>`（空格转 `_`）。workflow 已直接按规范建，无需担心；但若手动建 release 时 tag 用源 tag，会被 bot 改名。
- **⚠️ `gh release view --json` 字段名** [V]——是 `isPrerelease`，不是 `prerelease`（踩过）。
- **⚠️ NPatch 注入时序依赖** [V]——清模块+游戏数据 + 不开 NPatch → 模块不生效；点开一次 NPatch 永久生效（上会话遗留，仍待查）。
- **⚠️ ABS/手动换挡开关在 UI 被注释** [V]——ConfigurePage.kt `/* */`。ABS: HandleABS 死代码；手动换挡: DoGearShifting 出不了 P 房。待找到正确入口后恢复。
- **⚠️ LSPosed 共存版双 ClassLoader** [V]——`System.setProperty(NATIVE_INSTALLED_FLAG)` 进程级标记避免双注入。
- **⚠️ `OverlayDialog` 必须在 miuix Scaffold `popupHost` 槽位内渲染** [V]。
- **⚠️ EULA 只存 filesDir** [V]——改 `EULA_SECTIONS` 必须递增 `EulaManager.EULA_VERSION`（当前 = 2）。
- **⚠️ miuix-blur minSdk=33 而项目 minSdk=26** [V]；`MiuixScrollBehavior()` 不自动生效 [V]；WSL2 Kotlin 编译守护进程 RMI loopback 卡死 [V]。
- **⚠️ `is_player_controller`（读 0x108）不可靠** [V]——玩家车判据走 `g_player_controller`。
- **⚠️ Release R8 重命名 res/raw 下 mp3** [V]——音乐放 `assets/`；游戏进程 ClassLoader 取不到 APK 资源 [V]——走 `resolveModuleApkPath()` + ZipFile。
- **⚠️ ConfigProvider.kt 不可删** [V]（NPatch 回退路径）；versionCode Beta 3=`100230` [V]。

## 6. 下一步（有序）
1. **验证「打新 tag → build.yml 自动串联 sync」整条链**（本会话只验证了 `workflow_dispatch` 手动全量，未验证自动触发）：下个版本打 tag 发版后，确认 `build.yml` 的 `sync-lsposed` job 自动跑成功、镜像自动新增对应 release。若失败优先查 `secrets: TARGET_REPO_TOKEN` 传参链。
2. **（可选）README 镜像仓库更新**：若源码 README 有大的措辞改动，需手动 push 到镜像仓库（workflow 只同步 release，不同步 README 文件）。当前镜像 README 已是最新（含 GIF）。
3. **清理 `App.KEY_NONROOT_CONFIRMED` 残留常量**（低优先级，无害）。
4. **NPatch 注入时序依赖根因**（用户报告）：假设 NPatch 管理器第一次启动才注册模块，验证 `references/NPatch` 的 `patch-loader`/`meta-loader` 源码。
5. **ABS/手动换挡正确入口点**：找到未被内联的入口后恢复 UI 开关并更新 README。

## 7. 留给用户的开放问题
- 「打新 tag 自动同步」整条链尚未实测（见第 6 节第 1 项）——下次发版时确认。
- 官网 CDN 有 10-20 分钟延迟，是否可接受？（平台特性，无解）
- `TARGET_REPO_TOKEN` 是长期有效 PAT 还是短期？过期后 workflow 会失败，需在 secret 过期前更新。
- NPatch 注入时序依赖根因待查（见第 6 节第 4 项）。
- 去掉 `generate_release_notes` 后发布 body 只有安装说明——是否需要 CI 走"读版本号 Notes 文件"实现完整 changelog 自动发布？（上会话遗留）
- miuix-blur 在老设备（Android 12 以下）自动降级纯色底——是否需要显式 `LocalEnableBlur` false 待讨论。
- 320kbps 替换曲是否够用？换曲直接替换 `assets/f1_music.mp3`。
