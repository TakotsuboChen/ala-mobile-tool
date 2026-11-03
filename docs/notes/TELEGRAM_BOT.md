# Telegram Bot 接入（服务端）

> 状态：活跃 · 最后编纂：2026-11-03
> 一句话结论：**规则引擎单一共享，平台差异只有三处**——身份列、`{{at_me}}` 渲染、失败文案措辞。任何「给 Telegram 复制一份引擎」的做法都会让「交互逻辑完全一致」退化成「两套代码碰巧写得一样」。

## 1. 结论（发现）

- [V] **`qq_bot.rs` 不是「QQ 模块」，是「bot 模块恰好只实现了 QQ」**。1790 行里 6 类职责，
  只有「平台适配」与「发送」两类真正与 QQ 绑定；规则引擎、三个内置动作、播报判定、
  金标判定、configs 读写全部平台中立。抽出 `bot_core.rs` 后 `qq_bot.rs` 只剩 680 行。
- [V] **平台差异收敛到三处**：
  1. **身份列**：`users.member_openid`（QQ，TEXT，**按群隔离**）/ `users.telegram_id`
     （TG，**BIGINT**，全局唯一）
  2. **`{{at_me}}` 渲染**：QQ 内联 `<qqbot-at-user id=".." />`（走 markdown 通道）/
     TG 转 `text_mention` 或 `mention` 实体
  3. **失败文案措辞**：「QQ 群设置」↔「Telegram 隐私模式」
- [V] **两平台账号体系独立**（用户 2026-11-03 定案）：同一人在 QQ 和 TG 是两个账号、
  两套成绩。`telegram_id` 与 `member_openid` 同一行上互不重叠，不做跨平台合并。
- [V] **QQ 的 `member_openid` 按群隔离，TG 的 `from.id` 全局唯一**。这不是「TG 更严格」，
  而是**两平台的身份基数不同**：QQ 现状真实语义是「一人跨群可多号」（换群重新发码
  openid 不同 ⇒ 唯一约束不触发）。
- [V] **Telegram 没有「查询我加入了哪些群」的 API**，只能靠收到消息被动登记，
  或管理端手动填 `chat_id`。
- [V] **Telegram 的 `secret_token` 不是签名**：只是明文 header 回显，不覆盖请求体、
  不防重放。安全性完全依赖 HTTPS + token 不泄露。
- [V] **`entity` 的 `offset`/`length` 是 UTF-16 code unit**（不是字节、不是码点）。
  中文 1 字 = 3 字节但只有 1 unit，用字节偏移会让实体整体错位。
- [V] **`text` 长度上限 4096 字符**（entities 解析后计），超长**整条被拒、不截断**。
- [V] **频控三档独立**：每 chat ≈1 条/秒、每群 20 条/分钟、全局 ≈30 条/秒。
  一条广播稳在全局限内，仍可能因对某群连发 21 条/分钟被拦。
- [V] **隐私模式默认开启，且对已存在的群不立即生效**：BotFather `/setprivacy` 关闭后
  必须把 bot 移出群再重新拉进来；或直接设为群管理员（等效且立即生效）。
  `getMe().can_read_all_group_messages` 是可编程自检。
- [V] **VPS 直连 `api.telegram.org` 被 DNS 污染**：解析到 Facebook 的 IP
  （`128.242.240.157` / `2a03:2880:f129:83:face:b00c:0:25de`），连接被拒。

## 2. 依据（研究）

### 2.1 平台差异清单（会导致「交互不一致」的点）

| # | 维度 | QQ | Telegram |
|---|---|---|---|
| 1 | 用户标识 | `member_openid` **按群隔离** | `from.id` **全局唯一** |
| 2 | @ 的表示 | `content` 里替换成 `<@openid>` + `mentions[]` | `entities[]` 的 offset/length，**正文原样不变** |
| 3 | 去重 | 平台给 `msg_id` + `msg_seq` | 只有 `update_id`，**at-least-once 重投** |
| 4 | 被动回复 | 5 分钟窗、每条最多 5 次 | **无时效、无次数限制** |
| 5 | 富文本 | Markdown 需申请模板权限 | `HTML`/`MarkdownV2`/`entities` 自由用 |
| 6 | 消息长度 | 阈值未公开 | 4096（entities 解析后） |
| 7 | 鉴权 | Ed25519 签名 | **无签名**，只有 `secret_token` |
| 8 | 多环境 | 沙箱/正式分开配置 | **同一 token 只能一个 webhook** |
| 9 | 群 id 稳定性 | `group_openid` 稳定 | `chat.id` **会因升级超级群而变** |
| 10 | 用户枚举 | 有群成员列表接口 | **无**，只有 count/admins/单个查 |

### 2.2 身份模型选型（2026-11-03 用户定案）

| 方案 | 结论 |
|---|---|
| **A. `users.telegram_id BIGINT UNIQUE`（采用）** | 纯加列无回填，TG = 独立账号 |
| B. 泛化单列 `platform_identity`（`qq:x`/`tg:y`） | 否决：天生不支持一人多平台，未来要再迁表 |
| C. 关联表 `user_identities` | 否决：用户明确选「两平台独立账号」，用不上多身份能力 |
| D. 单平台部署配置切换 | 否决：不支持并存，且 configs 全是 QQ 专属格式 |
| E. TG 用 `(chat_id, user_id)` 复合键模拟按群隔离 | 否决：把实现细节误当产品需求，比 QQ 更糟 |

### 2.3 渲染方式选型

| 方案 | 结论 |
|---|---|
| **`entities` 参数（采用）** | 完全绕开转义解析。MarkdownV2 有 **18 个必须转义**的字符（``_ * [ ] ( ) ~ ` > # + - = \| { } . !``），现有模板里 `.`/`-`/`#`/`!` 遍地都是，漏一个整条 400 丢失 |
| `parse_mode=MarkdownV2` | 否决：转义规则**上下文相关**（正文 18 个、`code` 内 2 个、URL 内 2 个），一个全局 escape 函数必然出错 |
| 纯文本 | 否决：`***粗体***` 原样显示星号 |

## 3. 实现（固化）

### 3.1 代码结构

```
paddock-api/src/
├── bot_core.rs      ~1480 行  平台中立：规则引擎/三动作/渲染/播报/金标
├── qq_bot.rs        ~680 行   原 1790 行，减掉的 1110 行全是平台中立代码
└── telegram_bot.rs  ~880 行   验签/payload 解析/entities/分段/去重/频控
```

**唯一抽象** = `bot_core::Platform` 枚举（决定身份列、@ 渲染、文案措辞）。
`decide_reply()` 是两平台唯一入口，QQ 与 TG 的 handler 都只做
「解析 → 调它 → 入队」。

### 3.2 关键实现点

- **两个独立发送队列**：QQ 2s 串行（30/qpm）、TG 每 chat 3s 节流（20/min）
  + 429 `retry_after` 退避 + `migrate_to_chat_id` 自动重发
- **`update_id` 幂等去重**（内存环形 512 条）+ **webhook 秒回 2XX 异步处理**
- **4096 分段**：按 UTF-16 长度切，每段**重算**实体偏移，不劈 surrogate pair
- **`message_thread_id` 回传**（论坛话题群，否则落到 General）
- **过滤三类假身份**：`1087968824`（匿名管理员）/ `777000`（频道转发）/ `is_bot`
- **`{{at_me}}` 无身份 → 空串**，并收掉残留空行

### 3.3 提及形式的优先级（TG）

```
有 @username  → mention 实体，显示 @zhangsan（可点击）
无 username   → text_mention 实体，显示「张三」蓝字（可点击）
两者都无      → 整个 {{at_me}} 空串（整行消失）
```

`@username` 的补录两条路（**都不需要用户重新注册**）：
- 被动：用户每次群内发言，`message.from.username` 顺手写回
- 主动：管理端「检查状态」遍历已知群逐个 `getChatMember`

### 3.4 部署约束

```yaml
environment:
  HTTPS_PROXY: ${TELEGRAM_PROXY:-}
  HTTP_PROXY: ${TELEGRAM_PROXY:-}
  NO_PROXY: garage,postgres,localhost,127.0.0.1,::1,172.16.0.0/12,10.0.0.0/8,192.168.0.0/16
```

`NO_PROXY` **不可省**：`reqwest` 的代理环境变量是全局的，不排除内网会把
发往 `garage:3900` 的头像请求也劫持到代理上（实测 502 → 头像 500）。

代理出口 = 宿主机 mihomo `172.25.0.1:7890`（docker 网桥网关）。

### 3.5 迁移

- `0012_telegram_bot.sql`：`telegram_id BIGINT UNIQUE` + 部分索引
- `0013_telegram_username.sql`：`telegram_username TEXT`

⚠️ **已应用的迁移文件不能改**，必须新建下一个版本号——sqlx 按 version 记录，
改文件内容不会重跑（2026-11-03 踩过：给 0012 追加列，生产库根本没这列）。

## 4. 已否决的方案

- [X] **`@{围场用户名}` 做 TG 提及降级** —— 围场用户名与 Telegram 用户名是**两个独立
  命名空间**，加 `@` 会被 TG 当真实用户名解析，撞名就 **@ 到陌生人**。
  纪录持有者是 QQ 用户时必走此分支，必现。
- [X] **无身份时「只显示名字不加 @」** —— 仍然多出一行光秃秃的文本。
  `{{at_me}}` 语义是「@ 这个人」，没有可 @ 的身份就不该产出任何内容。
  用户原话：「直接不显示啊，没有对应身份就应该直接不显示」。
- [X] **QQ markdown 通道判据只看 `@` 标签** —— markdown 通道是**通用富文本载体**，
  `***`/`# ` 同样依赖它。只看 `<qqbot-at-user` 会让「持有者是 TG 用户」时
  整个播报掉进纯文本通道，群里看到 `***张三***` 源码。
- [X] **`text_mention` 能显示成 `@username`** —— 不能。`text_mention` 渲染的是
  **蓝色自定义文本**；要 `@xxx` 形式必须用 `mention` 实体 + 知道对方 username。
- [X] **`teloxide`** —— 它核心是 long polling 或内置 webhook server，我们要的是
  「axum 里收 POST」，用它等于绕过 80% 设计还付全部编译代价。手写约 180 行。
- [X] **共用 QQ 的 `SendJob`** —— 字段语义完全不同（`chat_id` 是 i64、无 `msg_id`
  被动回复窗、`scene` 概念不存在），强行合并会写出一堆 `String ↔ i64` 转换。
- [X] **启动时自动 `setWebhook`** —— 把网络抖动耦合进启动路径，且 token 未配置时
  逻辑分叉。改为「保存 token 时同步注册」+「独立检查状态按钮」。
- [X] **本地开发共用生产 token** —— 同一 token 只能一个 webhook，本地一注册
  生产立即断流。必须 BotFather 另建测试 bot。
- [X] **`getUpdates` 探活** —— 会造成 409 并把生产 webhook 踢掉。用 `getWebhookInfo`。
- [X] **用 `try_send` 入队** —— 满队列时立即返回 `Err(Full)`，被吞掉 = 静默丢消息。
  必须 `send().await`（阻塞等待，真反压）。
- [X] **`handle_*` 返回 `String` 用空串表示静默** —— 会发一条**空消息**并消耗 QQ 的
  被动回复窗（每条最多 5 次）。必须 `Option<String>`，`None` = 什么都不做。

## 5. 待验证

- [?] **`reqwest` 是否真的支持 `NO_PROXY` 的 CIDR 网段语法** —— 目前靠主机名匹配
  （`garage`/`postgres`）生效，网段部分未单独验证。
- [?] **`edited_message` 是否需要处理** —— 当前忽略。若支持需先做内容哈希比对，
  否则同一条逻辑消息会被处理两次。
- [?] **Telegram 4096 的计量单位** —— 官方只说 "characters"，第三方实测按码点。
  当前保守按 UTF-16 长度切（对中文/ASCII 等价，对 emoji 切得早但永不超限）。
- [?] **supergroup 迁移的完整链路** —— 代码处理了两个入口（主动服务消息 / 被动
  400 + `parameters`），但实机未触发过。
- [?] **QQ 的 markdown 通道对 `***` 和 `# ` 的实际渲染效果** —— 判据已扩展，
  但未在真机验证「纯 `***` 无 @ 标签」的播报能否正确渲染。
