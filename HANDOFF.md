# HANDOFF — 读全文再开始干活

生成时间: 2026-11-03T21:32:17+08:00 · Git HEAD: 模块仓 `c45e89d` / 服务端仓 `0ea089c`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `c45e89d`；服务端仓 `../ala-mobile-paddock` `main` @ `0ea089c`
- 漂移检查: 两仓各自的 `git rev-parse HEAD~1` 是否仍 = 上述 SHA（HEAD 必是本次 handoff 提交）
- 待重探的 [?]: §5 中标 [?] 的条目
- 先读: [`../ala-mobile-paddock/paddock-api/src/bot_core.rs`](../ala-mobile-paddock/paddock-api/src/bot_core.rs)
  的 `Platform` 枚举与 `decide_reply()`；[`docs/notes/TELEGRAM_BOT.md`](docs/notes/TELEGRAM_BOT.md)

## 1. 当前目标
无进行中的功能切片。本会话完成：**服务端新增 Telegram Bot，交互逻辑与 QQ Bot 完全一致**，
已部署上线并由用户实机验收通过（「好了，这次对了」）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **Telegram Bot 已上线**：镜像 `paddock-api:1.0.1-tg5`，VPS `PADDOCK_IMAGE` 已切。
  `curl /v1/health` → `{"status":"ok","version":"1.0.1"}`。
- [V] **迁移已落库**：`_sqlx_migrations` 有 `12 | telegram bot` 与 `13 | telegram username`；
  `users.telegram_id bigint` + `users.telegram_username text`。
- [V] **两仓工作已提交并推送**：模块仓 `c45e89d`（docs/契约）、服务端仓 `0ea089c`（实现）。
- [V] **测试**：`cargo test --offline` → **55 passed / 0 failed / 25 ignored**；
  真库 `--ignored --test-threads=1` → **25 passed**（新增 6 个 Telegram 建号用例）。
- [V] **`release` 构建成功**，产物特征串实证含 `tg/webhook`/`telegram_id`/`text_mention`。
- [V] **端到端验收**：TG 群收到格式正确的金标播报（标题加粗、车手名加粗、
  `@username` 形式提及、无身份时整行消失）。
- [V] **QQ 侧回归正常**：播报走 markdown 通道，`@「名字」` 假提及已消失。
- 工作区: 两仓均干净，`main...origin/main` 已同步。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ cargo test --offline
test result: ok. 55 passed; 0 failed; 25 ignored; 0 measured

$ DATABASE_URL=postgres://...@127.0.0.1:55433/paddock cargo test --offline -- --ignored --test-threads=1
test result: ok. 25 passed; 0 failed; 0 ignored

$ cargo build --release --offline
Finished `release` profile [optimized] target(s) in 1m 00s
```

## 3. 决策与理由
- **规则引擎单一共享，不按平台分 key** [V]——用户原话「交互逻辑和 QQ Bot 完全一致」。
  `bot_core.rs` 是唯一实现，两平台只做适配。否决方案：按平台分 `bot_message_rules:qq/tg`
  （会让一致性退化成「两套配置碰巧一样」，无法用测试保证）。
- **账号模型 = 方案 A（`users.telegram_id` 加列）** [V]——用户明确选「独立账号」。
  否决方案：关联表 `user_identities`（用不上多身份能力）、单列平台前缀（未来要再迁表）。
- **不复刻 QQ 的「按群隔离」** [V]——TG 的 `from.id` 全局唯一，直接用。
  复刻要付 schema 复杂度代价且体验更差（用户已认可）。
- **`entities` 而非 `parse_mode`** [V]——MarkdownV2 有 18 个必须转义的字符且规则
  上下文相关，一个全局 escape 必然出错。
- **管理端不做平台 Tab** [V]——用户定案「不用，标是 Telegram 还是 QQ 用户就行」。
  改为：设置页加一块 TG 凭据 + 用户列表加「平台/身份」列。
- **`{{at_me}}` 无身份输出空串** [V]——用户两轮纠正后定案，见 memory `at-me-empties-not-degrades`。
- **版本号未改**（仍 `1.0.1`）[V]——用户未要求升版；镜像 tag 用 `1.0.1-tg5` 区分以便回滚。

## 4. 失败的尝试 — 不要再试
- [X] **`@{围场用户名}` 做 TG 提及降级**（2026-11-03 实机）——围场用户名与 Telegram
  用户名是两个独立命名空间，加 `@` 会被 TG 当真实用户名解析，**撞名就 @ 到陌生人**。
  纪录持有者是 QQ 用户时必走此分支，必现。
- [X] **无身份时「只显示名字不加 @」**——仍多出一行光秃秃文本。用户：「直接不显示啊」。
- [X] **QQ markdown 通道判据只看 `<qqbot-at-user`**——markdown 是通用富文本载体，
  `***`/`# ` 也依赖它。只看标签会让「持有者是 TG 用户」时播报掉进纯文本通道，
  群里看到 `***张三***` 源码。
- [X] **给容器加 `HTTP_PROXY` 不配 `NO_PROXY`**——全局代理劫持内网请求，
  `garage:3900` 被塞给代理 → 502 → **头像上传 500**。
- [X] **修改已应用的迁移文件**——给 `0012` 追加列，但生产库已执行过，sqlx 不会重跑
  ⇒ 新列不存在。必须新建 `0013`。
- [X] **`text_mention` 显示成 `@username`**——它渲染的是蓝色自定义文本，要 `@xxx`
  必须用 `mention` 实体 + 知道对方 username。
- [X] **`teloxide`**——核心是 polling/内置 webhook server，与「axum 里收 POST」不匹配，
  手写约 180 行更轻。
- [X] **共用 QQ 的 `SendJob`**——字段语义完全不同，强行合并是一堆类型转换。
- [X] **启动时自动 `setWebhook`**——网络抖动耦合进启动路径。
- [X] **本地共用生产 token**——同一 token 只能一个 webhook，本地注册即断生产。
- [X] **`getUpdates` 探活**——409 并把生产 webhook 踢掉。
- [X] **`try_send` 入队**——满队列静默丢消息。
- [X] **`handle_*` 返回 `String` 用空串表示静默**——会发空消息并消耗 QQ 被动回复窗。
- [X] 继承死路（详 `.handoffs/20261031130957-handoff.md` §4 及更早）：整圈五维全填 0 /
  `strings | grep` 搜 APK 中文 / `baksmali` 后 grep 中文 / 条件调用 `remember` /
  `PickVisualMedia` / `createChooser` / `ResolveInfo.loadLabel()` / 相信 `UP-TO-DATE` /
  hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` /
  整段 `md.disasm(detail=True)` / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at` / `scrollToItem` 跨数据集归位 /
  `firstVisibleItemIndex > 0` 才归位。

## 5. 已知坑
- ⚠️ **VPS 直连 `api.telegram.org` 被 DNS 污染** [V]——解析到 Facebook IP
  （`128.242.240.157`）。代理出口 = 宿主机 mihomo `172.25.0.1:7890`。
- ⚠️ **`NO_PROXY` 不可省** [V]——见 memory `docker-proxy-env-needs-no-proxy`。
- ⚠️ **已应用的迁移文件不能改** [V]——见 memory `migration-file-edits-need-new-version`。
- ⚠️ **Telegram 隐私模式对已存在的群不立即生效** [V]——BotFather `/setprivacy` 关闭后
  必须移出重加，或设为群管理员。`getMe().can_read_all_group_messages` 可自检。
- ⚠️ **`entity` 偏移是 UTF-16 code unit** [V]——中文 1 字 = 3 字节但 1 unit。
- ⚠️ **`tg_broadcast_chats` 与 `bot_broadcast_groups` 是两套独立配置** [V]——
  勾了 QQ 群不等于 TG 也会播报，需在管理端分别勾选。
- ⚠️ **`@username` 补录需要用户先发过言或点「检查状态」** [V]——用户反馈
  「要我先重新点一下检查状态才行」，说明存量用户不会自动补齐。
- ⚠️ **`bot_known_groups` 里有 4 个群但 `bot_broadcast_groups` 只有 2 个** [?]——
  另 2 个是登记过但未勾选播报，属正常。
- ⚠️ **`reqwest` 对 `NO_PROXY` 的 CIDR 语法未单独验证** [?]——目前靠主机名匹配生效。
- ⚠️ **`edited_message` 当前忽略** [?]——若支持需先做内容哈希比对。
- ⚠️ **supergroup 迁移链路未实机触发** [?]——代码处理了两个入口。
- ⚠️ **QQ markdown 通道对纯 `***`/`# ` 的渲染未真机验证** [?]——判据已扩展但未单测。
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
1. **（待你决定）存量用户的 `@username` 补齐**：目前要靠用户发言或手动点「检查状态」。
   可考虑在服务端启动时后台跑一次 `backfill_usernames`（注意限速，逐群 `getChatMember`）。
2. **（待你决定）Telegram 播报目标群**：`tg_broadcast_chats` 已在用，但管理端
   「勾了 QQ 不等于 TG 也会播报」缺提示，是否需要加一句说明。
3. **（待你决定）1.0.2 发版**：本次是服务端功能，若要走版本流程需你定版本号
   （**版本号是你的决策域，我未改**）。
4. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能展示，回写游戏未做。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- 存量用户 `@username` 补齐要不要做成启动时后台任务？限速策略如何定？
- 管理端要不要提示「QQ 群勾选不等于 TG 群也播报」？
- 服务端这次要不要发版？（版本号由你定）
- 覆盖重发/版本相同导致存量用户收不到更新提示，是否需要升 Fix 号？（继承）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承）
- 是否要按调教做榜？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
