# HANDOFF — 读全文再开始干活

生成时间: 2026-11-03T21:50:00+08:00 · Git HEAD: 模块仓 `ae3697e` / 服务端仓 `0ea089c`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `ae3697e`；服务端仓 `../ala-mobile-paddock` `main` @ `0ea089c`
- 漂移检查: 两仓各自的 `git rev-parse HEAD~1` 是否仍 = 上述 SHA（HEAD 必是本次 handoff 提交）
- 待重探的 [?]: §5 中标 [?] 的条目
- 先读: [`docs/notes/NPATCH_CACHE_CRASH_NOTES.md`](docs/notes/NPATCH_CACHE_CRASH_NOTES.md)
  顶部五 bug 对照表 + **§15（Bug E）**；[`docs/notes/TELEGRAM_BOT.md`](docs/notes/TELEGRAM_BOT.md)

## 1. 当前目标
无进行中的功能切片。本会话完成：**诊断「新用户解锁不生效」并定案为 NPatch 作用域未勾选**
（Bug E），判据与结论已固化进 `docs/` + CLAUDE.md + 项目记忆。

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因已定案**（用户原话「破案了，是没弄作用域」）：NPatch 管理器没给本模块勾选
  游戏包作用域 ⇒ 模块从未被注入游戏进程。
- [V] **决定性判据**：用户提供的 601 行导出日志
  `grep -n '^==='` → **只有 `1:=== 模块进程日志 (Java) ===` 一段**；游戏进程
  Java/Native 段、崩溃记录段、`npatch/log/` 段全部缺失。
- [V] **零业务日志佐证**：全文件 grep `Module loaded` / `Package loaded` /
  `onPackageReady read config` / `unlock` / `Billing` / `SetUnlocked` → 全部零命中。
- [V] **排除 AFA 权限**：日志 20:46 起 `Mailbox write OK ... (1241B)`，说明目录存在、
  权限已给（18:33 首次启动时还是 `EPERM`）；但目录里没有 `ala_tool.log` ⇒ 文件真不存在。
- [V] **排除版本门控**：模块进程读到 `游戏: 8.0.6 Fix 3 (200150)` = `SUPPORTED_COEX_FIX`
  ；模块 `1.0.4 (104300)` 要求 NPatch 850。版本维度全满足。
- [V] **文档已落盘并推送**：`3a8edce`（docs §15 + 顶部对照表扩到五 bug）、
  `ae3697e`（CLAUDE.md 判据段）。项目记忆
  `npatch-scope-not-set-looks-like-no-module.md` 已写并加索引。
- 工作区: 两仓均干净，`main...origin/main` 已同步。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ grep -n '^===' ala_tool_log_20261103_205743.txt
1:=== 模块进程日志 (Java) ===          ← 仅此一段，其余四段全缺

$ grep -n "Module loaded|Package loaded|unlock|Billing" <同上>
(零命中)
```
（本次未改代码，无 build/test 可跑。）

## 3. 决策与理由
- **判据落在「导出日志的段数」而非 logcat** [V]——99% 用户没有电脑（memory
  `users-cannot-use-adb`），Bug E 用一份导出日志就能定案，不该抬到需要 root/adb 的门槛。
  否决方案：让用户抓 logcat（只有 Bug C 才必须）。
- **因果基础用 `onModuleLoaded` 的第一句日志** [V]——`AlaMobileModule.kt:127` 的
  `Module loaded in process` 早于所有门控 return（`initGameLogger()` 在 `:151`，
  注释明写"必须在一切门控 return 之前"）⇒ 注入发生则必有游戏进程日志，零日志 = 没注入。
  否决方案：靠某条业务日志判断（业务日志都在门控之后，分不清"没注入"与"注入但被拦"）。
- **Bug E 单列一节而非并入 Bug C** [V]——两者症状相同但判据与处置完全不同（C 需 logcat、
  是上游 bug；E 一份日志定案、是配置遗漏）。合并会让下一个排查者走错分支。
- **版本号未改**（仍 `1.0.5 Alpha 1` / `105110`）[V]——用户未要求升版。

## 4. 失败的尝试 — 不要再试
- [X] **靠「日志里有没有某条游戏侧业务日志」判断模块是否注入**——业务日志全在门控之后，
  被拦住时同样为零，无法区分「没注入」与「注入了但被拦」。必须用 `onModuleLoaded`
  的**第一条**日志。
- [X] **`Mailbox write OK` 当作「游戏侧通道正常」的证据**——`CrossPkgMailbox.write`
  内部 `mkdirs()` 会创建 `Android/media/<游戏包>/`，写成功只证明**模块进程**有 AFA、
  目录存在，与游戏日志是否存在无关。
- [X] **把「不崩溃 + 功能全无」直接判为 Bug C**——Bug C 的 NPatch 文件日志里会有
  `Loaded cached module` 而无 `Module loaded in process`；连 `npatch/log/` 段都没有的是 Bug E。
- [X] 继承死路（详 `.handoffs/20261103214856-handoff.md` §4 及更早）：`@{围场用户名}` 做
  TG 提及降级 / 无身份时"只显示名字不加 @" / QQ markdown 判据只看 `<qqbot-at-user` /
  容器加 `HTTP_PROXY` 不配 `NO_PROXY` / 修改已应用的迁移文件 / `text_mention` 当 `@username` /
  `teloxide` / 共用 QQ 的 `SendJob` / 启动时自动 `setWebhook` / 本地共用生产 token /
  `getUpdates` 探活 / `try_send` 入队 / `handle_*` 返回空串表示静默 /
  整圈五维全填 0 / `strings | grep` 搜 APK 中文 / `baksmali` 后 grep 中文 /
  条件调用 `remember` / `PickVisualMedia` / `createChooser` / `ResolveInfo.loadLabel()` /
  相信 `UP-TO-DATE` / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at` / `scrollToItem` 跨数据集归位 /
  `firstVisibleItemIndex > 0` 才归位。

## 5. 已知坑
- ⚠️ **`LogExporter` 对「段缺失」是静默的** [?]——`foundAny` 只要模块日志读到就是 `true`
  （`util/LogExporter.kt:180`），所以「游戏进程日志缺失」**完全不提示**，用户和我们看到的
  都是一份"看起来正常"的日志，实际少了最关键的一半。**待改：每段显式输出
  `[未找到：<路径>]`**，让缺失本身成为证据。本会话未实施。
- ⚠️ **NPatch 的 `android.util.Log` 只进 logcat 不落文件** [V]——文件日志只有 XLog
  （tag 以 `NPatch` 开头）。所以"文件日志里没有某行"不能推出"没发生"，
  见 `NPATCH_CACHE_CRASH_NOTES.md` §5.1。
- ⚠️ **Bug E 不需要 logcat，Bug C 必须** [V]——排查顺序：先数导出日志段数，
  只有一段 = Bug E 一步定案。
- ⚠️ **VPS 直连 `api.telegram.org` 被 DNS 污染** [V]（继承）——解析到 Facebook IP；
  代理出口 = 宿主机 mihomo `172.25.0.1:7890`。
- ⚠️ **`NO_PROXY` 不可省** [V]（继承）——见 memory `docker-proxy-env-needs-no-proxy`。
- ⚠️ **已应用的迁移文件不能改** [V]（继承）——见 memory `migration-file-edits-need-new-version`。
- ⚠️ **Telegram 隐私模式对已存在的群不立即生效** [V]（继承）——需移出重加或设为管理员。
- ⚠️ **`entity` 偏移是 UTF-16 code unit** [V]（继承）。
- ⚠️ **`tg_broadcast_chats` 与 `bot_broadcast_groups` 是两套独立配置** [V]（继承）。
- ⚠️ **`@username` 补录需要用户先发过言或点「检查状态」** [V]（继承）。
- ⚠️ **`edited_message` 当前忽略** [V]（继承，`paddock-api/src/` 全仓零命中）。
- ⚠️ **`bot_known_groups` 4 群 vs `bot_broadcast_groups` 2 群** [?]（继承，疑正常）。
- ⚠️ **`reqwest` 对 `NO_PROXY` 的 CIDR 语法未单独验证** [?]（继承，靠主机名生效）。
- ⚠️ **supergroup 迁移链路未实机触发** [?]（继承，代码两个入口都在
  `telegram_bot.rs:271` / `:518`）。
- ⚠️ **QQ markdown 通道对纯 `***`/`# ` 的渲染未真机验证** [?]（继承）。
- ⚠️ **魅族 20 的 IP 会来回变** [V]（继承）——`.142`/`.123` 都出现过。
- ⚠️ **覆盖重发后已装用户收不到更新提示** [V]（继承）——更新检查按 versionCode 比较。
- ⚠️ **`sort=time_desc/asc` 排的是上传时间** [V]（继承）——按圈速排用 `lap_desc`/`lap_asc`。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算** [V]（继承）。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 数据展示口径（精度/小数位/间距）先问再定
  17 下断言前先取证 18 大段文档写文件不贴终端 19 日志里有某行 ≠ 该行被执行了
  20 **用户说「我的设备没事但别人的可能有问题」时，不能因为本机复现不了就否定**

## 6. 下一步（有序）
1. **（待你决定）修 `LogExporter` 的「段缺失静默」**：每段显式输出
   `=== 游戏进程日志 (Java) ===` + `[未找到：<路径>]`，让缺失本身成为证据。
   独立小改动，与任何功能无关。§5 第一条有详细说明。
2. **（待你决定）存量用户的 `@username` 补齐**（服务端）：目前要靠用户发言或手动点
   「检查状态」。可考虑启动时后台跑一次 `backfill_usernames`（需限速，逐群 `getChatMember`）。
3. **（待你决定）Telegram 播报目标群提示**：管理端「勾了 QQ 不等于 TG 也会播报」缺说明。
4. **（待你决定）1.0.2 发版**（服务端）：本次是服务端功能，走版本流程需你定版本号
   （**版本号是你的决策域，我未改**）。
5. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能展示，回写游戏未做。
6. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- `LogExporter` 段缺失提示要不要做？（第 1 项）
- Bug E 要不要写进用户侧 FAQ / 围场指南？目前只在开发侧文档。
- 存量用户 `@username` 补齐要不要做成启动时后台任务？限速策略如何定？
- 管理端要不要提示「QQ 群勾选不等于 TG 群也播报」？
- 服务端这次要不要发版？（版本号由你定）
- 覆盖重发/版本相同导致存量用户收不到更新提示，是否需要升 Fix 号？（继承）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承）
- 是否要按调教做榜？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
