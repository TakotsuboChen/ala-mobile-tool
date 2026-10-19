# HANDOFF — 读全文再开始干活

生成时间: 2026-10-19T21:06:00+08:00 · Git HEAD: `0f733f5`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `0f733f5`（已 push）· 服务端 `main` @ `6d1063b`（已 push，本会话未动）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `0f733f5`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/DriverScreen.kt`
  （注释里写满了小标题/间距/底栏一致性的「为什么」）

## 1. 当前目标
无进行中的功能切片。本会话完成车手主页概览页的三轮 UI 精修：
小标题改用 miuix `SmallTitle`、间距符合 miuix「上远下近」规范、
「暂无」改黑色、删除大奖赛 Toast 并统一底栏点击行为。

## 2. 已验证状态 — 工作实际停在哪
- [V] **「个人计时赛」改用 miuix `SmallTitle`**：从 `StatsCard` 内部移出，
  作为卡片上方的分组标题。`textColor = onBackground.copy(alpha = 0.6f)`（中性灰），
  `insideMargin = PaddingValues(horizontal = 16.dp, vertical = 8.dp)`。
- [V] **间距符合 miuix 规范「上远下近」**：删除 `Column` 的
  `Arrangement.spacedBy(12.dp)`，改由各元素自己的 padding 表达——
  `ProfileCard(modifier = Modifier.padding(bottom = 12.dp))` + SmallTitle 自带
  上下 8dp ⇒ 上方 20dp / 下方 8dp。`ProfileCard` 新增 `modifier` 参数。
- [V] **「暂无」改黑色**：`StatValueText` 删掉 `empty` 参数，颜色恒为
  `colorScheme.onBackground`。
- [V] **底栏大奖赛统一行为**：删 Toast、删 `LocalContext`/`Toast` import 与
  `context` 变量；`onClick` 三个 tab 一视同仁走 `animateScrollToPage(index)`。
- [V] **已装机**：魅族 20（`192.168.50.142:5555`），APK 21:03:03，用户确认「可以」。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`，服务端 `1.0.1`。
- [V] **工作区干净**：两仓 `main...origin/main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1m 31s（66 warnings 为既有基线，0 errors）
$ ./gradlew :app:assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
  → BUILD SUCCESSFUL in 2m 7s，APK 21:03:03（源码 20:59:17，晚于改动 ✓）
$ unzip -p <apk> classes.dex | grep -ac "开发中，敬请期待"   → 1
$ adb -s 192.168.50.142:5555 install -r <apk>              → Success
```

## 3. 决策与理由
- **分组标题必须用 `SmallTitle` 而非手写 `Text`** [V]——`SmallTitle` 走
  `textStyles.subtitle` + `onBackgroundVariant`，字号颜色属主题；手写 14sp + alpha
  只是数值接近的仿制品，用户一眼看出「不符合 MIUIX 小标题规范」。
- **间距由各元素 padding 表达，不用 `spacedBy`** [V]——`spacedBy(12.dp)` 会与
  SmallTitle 自带 8dp 内边距**叠加**，把 miuix 规范的「上 20 / 下 8」压成「20 / 20」
  对称，小标题就"悬浮"在两卡正中间。否决方案：调 `spacedBy` 数值——治标，
  组件内边距一变又会错。
- **小标题用中性灰而非默认 `onBackgroundVariant`** [V]——后者在本项目
  `lightColorScheme()` 里是 `0xFF8C93B0`（偏蓝蓝灰）。用户定案「不需要动态取色，
  灰色就行」。本项目**未启用动态取色**（`ConfigActivity.kt` 固定
  `lightColorScheme()`/`darkColorScheme()`）。
- **底栏三个 tab 行为必须一致** [V]——删 Toast 后大奖赛变成「点不动、只能滑」，
  用户报「点不动，只能滑过去」。按钮无反应会被读作"失灵"；既然页内已有占位提示，
  点击切页就是最一致的行为。
- **`StatValueText` 删掉 `empty` 参数** [V]——颜色只剩一个分支，保留参数会让人
  误以为「暂无」还有特殊配色。

## 4. 失败的尝试 — 不要再试
- [X] **手写 `Text(14sp, alpha = 0.6f)` 仿制小标题** [V]——用户
  「小标题不对吧，感觉不符合 MIUIX 小标题规范」。项目里早有 `SmallTitle`，
  且 `PaddockPagerMiuix.kt:368`、`AvatarScreen.kt:182` 已有两处既定用法。
  **写 UI 前先 grep 项目既有用法 + 查 `references/miuix/`**。
- [X] **用 `Arrangement.spacedBy(12.dp)` 给「标题 + 卡片」分组排版** [V]——
  与组件自带内边距叠加，把非对称间距压成对称。用户
  「我看别人也用 MIUIX 的小标题都离下方卡片更近，离上方卡片更远」。
- [X] **把默认 `onBackgroundVariant` 当成中性灰** [V]——它是偏蓝的 `0xFF8C93B0`。
- [X] **删 Toast 后让大奖赛 tab「不切页」** [V]——用户报「点不动，只能滑过去」。
  点击与滑动行为不一致 = 按钮看起来失灵。
- [X] 继承死路（详 `.handoffs/20261019210607-handoff.md` §4 及更早）：
  以为两个 BlurredBar 共用 backdrop 会冲突 / 把"跟字号没关系"理解成"也别改字号" /
  左右容器不同却只调对齐参数 / 只查 `max(version_code)` 统计纪录数 /
  `listState.scrollToItem(0)` 放在请求前 / 用 `heightIn` 给 `Text` 撑高 /
  把 `OPD2413` 说成 "OPPO 平板" / `unzip -p | strings | grep TextOverflow` /
  zsh 里 `unzip -p "$APK" classes*.dex` glob 不展开 / VPS 上 `apt-get update` /
  轮询等待慢构建 / 真库测试并行跑 / `--rerun-tasks` 重编 / 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / 抓地力加地板 / `HapticGenerator` / `pm grant VIBRATE` /
  hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` /
  整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 /
  踏板「单向补送」/ `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` /
  全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 /
  `kerbTapWave` 垫底 / versionCode 方案 806301 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号。

## 5. 已知坑
- ⚠️ **UI 元素必须先用 miuix 现成组件，禁止凭视觉手写仿制** [V]——
  本会话**同日两次**因此返工。已固化进项目 CLAUDE.md 与 memory
  `ui-use-miuix-component-not-handwritten-imitation`。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——本会话全程只做 `adb install`，
  未启动 App、未读日志，用户未提出异议。见 memory `no-autonomous-device-control`。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——见 memory `ask-before-designing-ui`。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）——见 memory `rank-row-number-not-rank`。
- ⚠️ **`deploy/docker-compose.vps.yml` 必须与 VPS 逐字一致** [V]（继承）——
  见 memory `paddock-compose-must-match-vps`。
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]（继承）——见 memory `compose-maxlines-overflow-orthogonal`。
- ⚠️ **R8 内联 value class 使 `strings` 搜符号假阴性** [V]（继承）；
  中文串用 `grep -a` 字节搜。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）——
  空状态必须短路返回 `false`；判据看**请求间隔**不看次数。
- ⚠️ **围场 UI 日志在模块进程 `filesDir`，需 root** [V]（继承）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20。
- ⚠️ **`/health` 端点不存在（404）** [V]——服务端只有业务路由。探活请用
  `/v1/leaderboard/points`。别把 404 误读成"服务挂了"。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [V]（继承）——
  看日志环境块的构建指纹（memory `build-fingerprint-in-logs`）。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——
  判据 = 日志含 `Pushed game logs via chunked broadcast`。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [?]（继承）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [?]（继承）——当前约 41 项。
- ⚠️ **用户日志在 Windows 盘** [?]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **等用户对车手主页的进一步反馈**：小标题观感、成绩卡对齐、「暂无」配色、
   底栏三页切换。异常先拉模块进程日志
   （`/data/data/tools.alamobile.mod/files/ala_tool.log`，需 root）。
2. **（待用户决定）大奖赛页的产品形态**：当前是占位文案。用户说"开发中"，
   未给出后续计划。
3. **（可选）VPS 清理**：`~/paddock` 已有多个历史镜像（`1.0.1-paging`、
   `1.0.1-driverstats`、`1.0.1-recfix`）+ 备份 `.tar.gz` + compose 备份文件。
4. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块 `1.0.4 Alpha 4`、
   服务端 `1.0.1` 均未动。

## 7. 留给用户的开放问题
- 车手主页的「全服最快圈数量」显示跨版本累计数（Takotsubo = 5）。
  是否需要在界面上标注"含历史版本"？
- 大奖赛页最终要做什么？当前只有占位。
- 计时赛页的筛选（赛道/版本/零辅助/仅个人最佳）是否还需要保留？
