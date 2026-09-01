# PADDOCK_PLAN — 围场私服第 1 期：圈速排行榜执行计划

> 生成：2026-08-31 · grill-me 多轮问答定案（用户逐项拍板）。
> 目标：Ala Mobile 私服第一步——计时赛圈速排行榜（围场）。匹配机制等后续规划本期不做。
> 证据标注：[V] 已实证 · [D] 本轮问答拍板 · [?] 待验证。

## 1. 定案总览（grill-me 轮次记录）

| 议题 | 定案 | 备注 |
|---|---|---|
| 后端语言 | Rust (axum + tokio + sqlx) [D] | 远期在线比赛服务器同栈；内存 50–80MB 级 |
| 数据库 | PostgreSQL [D] | VPS 建议 2GB 起步（见 §8 风险） |
| 对象存储 | Garage（Rust，S3 兼容，512MB 可跑）[D] | 弃 MinIO（开源版收缩）；管理端保留存储方式可配置 |
| Web 管理端 | Rust 服务端渲染（askama/maud + 少量 JS）[D] | 单二进制，无 Node 构建链 |
| Bot 部署形态 | 单二进制一体化（API+管理页+webhook 同进程）[D] | Compose 三容器：app / postgres / garage |
| Bot 资质 | 已有正式 bot 在 CAMDA 群 [D] | 无沙盒阻塞 |
| 身份绑定 | userid ↔ QQ `member_openid`（群聊场景）[V] | QQ 号拿不到；openid 每 AppID 独立 |
| 防伪 | 取消物理阈值拒收（**全放行**），登录态本身为门槛，管理端事后删 [D] | ⚠️ 第二轮曾选"合理性检查"，本轮推翻，以最新为准 |
| 上传开关 | 无开关；登录后刷圈即传 [D] | 未登录走本地缓存补传 |
| 上传触发 | 每条有效圈都上报，服务端只留最佳 [D] | Toast 判定权在服务端响应 |
| 数据契约 | 圈时(ms) + gpIndex + versionCode（最小契约）[D] | 未来扩展再加字段 |
| 版本键 | 游戏 6 位 versionCode（200146=8.0.4）[D] | 版本榜即该游戏版本榜 |
| 登录态 | 90 天滑动 token [D] | 改密即失效兜底 |
| 积分公式 | `score = round(1 + (N−rank)×99/(N−1))`，N=1 给 100 [D] | 每赛道×每版本独立；总榜跨版本累加 |
| Toast | 四条件全启用，同帧取最高一条：全服历史 > 全服版本 > 个人历史 > 个人版本 [D] | 服务端响应决定 |
| 弱网 | 本地待传队列自动重传 + 去重 [D] | 未登录圈缓存（时效 30 天）登录后补传 |
| 密码找回 | bot 一次性码（群内 @bot "重置密码 用户名"）[D] | 管理端同时留人工重置入口为宜（计划内含） |
| 用户名 | 1–16 字符：中文/字母/数字 + 相邻单空格（两侧禁），不可改 [D] | |
| 注册防劫持 | 校验码绑定注册会话（非裸码验证）[D] | 码 30 分钟时效 + 一次性 |
| 服务器地址 | 内置默认 + 设置页可覆盖（自部署/测试）[D] | |
| 切片顺序 | API → 模块上传 → UI → bot [D] | §6 |
| 菜单位置 | 新增"围场"页，位于配置和设置之间；顶/底栏大标题"围场"；底栏 icon=无旗杆方格旗；miuix 风格 [D] | |
| 赛道中文名 | 16 赛道对照表（§5），场景名 key **逐字照抄**（`Shangai` 单 a、`a1Ring` 小写、`MelbourneRifatta`）[V] | |

关键机制事实（联网调研核实）：
- QQ 官方 bot 拿不到 QQ 号，群聊身份用 `member_openid`（作者在该群内唯一）。[V]
- 收全量群消息需三处齐配：开放平台回调配置勾选 `GROUP_MESSAGE_CREATE` + 平台开启"接收所有消息" + 群设置"机器人可获取的群聊消息范围=获取群内全部消息"。[V]
- webhook 模式要求：公网 IP + HTTPS + 平台 IP 白名单；被动回复 5 分钟内有效、每条消息最多回 4 次。[V]
- bot 主动/被动消息频控：未认证 30 条/分钟（认证 10 QPS）——注册高峰期回复需做队列。[V]
- 模块在游戏进程发 HTTPS 无障碍（游戏自身联网，用独立线程 + OkHttp/HttpURLConnection）。[V]

## 2. 系统架构

```
┌─ 游戏进程 ──────────────────────────────┐
│ lap_hook (native, order==2 边界)         │
│   └─ JNI 抛圈速 → Java 上传器            │
│       ├─ 本地待传队列 (文件, 30 天时效)   │
│       └─ OkHttp HTTPS 直传 paddock API   │
└──────────────┬──────────────────────────┘
               │ POST /v1/laps (Bearer token)
┌─ VPS (Docker Compose) ──────────────────┐
│ Caddy (HTTPS, 自动证书, IP 白名单适配)    │
│  └─ paddock (axum 单二进制)              │
│      ├─ /v1/*        模块 API            │
│      ├─ /admin/*     管理页 (SSR)        │
│      └─ /qq/webhook  CAMDA bot 回调      │
│ PostgreSQL (用户/成绩/积分/会话)          │
│ Garage     (头像对象存储)                 │
└─────────────────────────────────────────┘
```

- 新仓库 `ala-mobile-paddock`（与模块独立，开源），纯 Rust workspace：`paddock-api`（bin：axum 服务）+ 可选拆分 crate。
- 模块侧新增：`PaddockClient`（上传器）、本地队列、围场 UI 页。native 侧 lap_hook **零改动**（order==2 边界已有 JNI 上抛点，扩展信号即可）。

## 3. 数据模型（Postgres, sqlx）

```sql
users        (id, username UNIQUE, pass_hash argon2id, member_openid UNIQUE,
              avatar_key, created_at, reg_seq)        -- reg_seq=车手 ID：注册顺序从 1 起
              -- （定案 2026-08-31：user_reg_seq 序列发放 + 唯一索引；榜单/verify/login 均返回）
sessions     (user_id, token_hash, expires_at)         -- 90 天滑动
pending_regs (reg_code, expires_at 30min, member_openid NULL, status)
laps         (id, user_id, gp_index, version_code, lap_ms, s1,s2,s3 NULL,
              created_at)                              -- 全量留档（全放行配套）
best_laps    (user_id, gp_index, version_code, lap_ms, updated_at,
              UNIQUE(user_id, gp_index, version_code)) -- 榜单数据源
records      (gp_index, kind: alltime|version, version_code NULL,
              lap_ms, user_id, updated_at)             -- 全服历史/版本最佳（Toast 判定）
积分: 查询时按 best_laps 实时 rank 计算，或物化到 score 表（首期实时计算即可，
      16 赛道 × 用户量级小，无性能问题）
```

- Toast 判定算法（服务端）：新圈 vs `records` 与该用户 `best_laps` 历史 → 返回 `{toast_level, track_name, scope}`；写入前先读后写同事务。

## 4. API 草案（/v1）

```
POST /v1/auth/register-request  {username,password}  → 409 已存在 | {reg_code, message_hint}
    （2026-09-01 v2：申请时一并设密+发车手 ID（存 pending_regs），bot 校验成功即建号）
POST /v1/auth/login             {username,password} → {token, user_id, reg_seq, has_avatar} (90d)
    （has_avatar=false → 注册后首次登录，模块引导上传头像；旧 register-verify 端点已删）
POST /v1/auth/reset-by-code     {reset_code,new_password} → 204 | 404 码无效/过期
POST /v1/me/avatar              (Bearer, body=JPEG/PNG ≤2MB) → {uploaded, url}（Garage 存储）
GET  /v1/me/avatar              (Bearer) → {uploaded, url}（查自己是否有头像）
GET  /v1/avatar/{user_id}       → 200 图片字节 | 404 无头像（公开端点）
POST /v1/laps                   {gp_index, lap_ms, version_code} (Bearer)
                                                    → {personal: bool, server: bool,
                                                       toast: null|{level, track}}
GET  /v1/leaderboard/points?version=      → 积分总榜/版本榜
GET  /v1/leaderboard/track/{gp_index}?version= → 赛道榜（总榜不分版本）
GET  /v1/me                          → 个人信息卡
```

错误码明确返回（401 掉登录态→模块进缓存补传路径）。

### 注册流程（v2，2026-09-01 定案：bot 校验即建号）

```
模块：输入用户名+密码 → POST register-request（服务端哈希密码+nextval 发号存 pending）
     → 弹窗展示"申请围场通行证#码"（点击复制指令）→ 用户复制后发 CAMDA 群
bot：群内匹配码 → 绑 member_openid → 建号事务（DELETE pending RETURNING → INSERT users）
     → 回复"@用户名 校验成功，欢迎您加入 CAMDA，您是全服第 x 位车手！请返回模块直接点击登录。"
模块：用户回 App 用同一账号密码 → POST login → has_avatar=false → 跳头像上传页
```

- 车手 ID（reg_seq）在**申请时**即发放：bot 回复需要序号；未完成注册的号作废
  （顺序不乱，允许空缺——与"严格连续"的取舍已由用户定案）
- 密码在申请时一并设置（服务端哈希落 pending），bot 建号时直接使用——
  模块端无 verify 步骤、无校验码回填

## 5. 赛道中文名对照（key=场景名，逐字照抄 [V]）

| 场景名 | 显示 |
|---|---|
| MelbourneRifatta | 🇦🇺 阿尔伯特公园赛道 |
| Shangai | 🇨🇳 上海国际赛车场 |
| Sakhir | 🇧🇭 巴林国际赛车场 |
| Imola | 🇮🇹 伊莫拉赛道 |
| Barcellona | 🇪🇸 加泰罗尼亚赛道 |
| Monaco | 🇲🇨 摩纳哥赛道 |
| Montreal | 🇨🇦 吉尔·维伦纽夫赛道 |
| a1Ring | 🇦🇹 红牛环赛道 |
| Silverstone | 🇬🇧 银石赛道 |
| Hockenheim | 🇩🇪 霍根海姆赛道 |
| Hungaroring | 🇭🇺 亨格罗宁赛道 |
| Spa | 🇧🇪 斯帕-弗朗科尔尚赛道 |
| Monza | 🇮🇹 蒙扎国家赛车场 |
| Suzuka | 🇯🇵 铃鹿赛道 |
| Interlagos | 🇧🇷 英特拉格斯赛道 |
| Dubai | 🇦🇪 亚斯码头赛道 |

（用户原表 `Shanghai` 已修正为场景真名 `Shangai`——单 a，实机 LAPscene 实证，勿纠正。）

## 6. 执行切片（拍板顺序：API → 上传 → UI → bot）

**S1 — paddock 后端骨架与 API（可独立实测）**
1. ✅ 仓库脚手架：axum + sqlx + 配置文件 + Dockerfile + docker-compose（app/pg/garage/caddy）。`cargo check` 通过（2026-08-31）。
2. ✅ 数据模型 migration（users/sessions/pending_regs/laps/best_laps/records）+ Argon2id 哈希。
3. ✅ auth API：register-request / register-verify / login（reset-by-code 留 S4）；register-verify 已实现 bot 校验绑定路径，S1 过渡期靠管理端代绑（管理端未建前可 psql 手工 UPDATE pending_regs）。
4. ✅ laps 上报（服务端 Toast 四条件判定事务）+ leaderboard（积分总榜/版本榜 + 赛道榜）。
5. ✅ 管理端 SSR（2026-08-31，`8fcd44a`）：askama 4 页 + cookie 会话 + 环境变量播种管理员；**代绑 openid 闭环实测通过**（页面粘贴 → 409/400 校验 → verify 建号）；删圈同事务回放 laps 重算 best_laps/records + 审计留痕，实测 90000→92000 回放正确。bot AppID/Secret 与存储方式配置项随 S4/S3 再补。
6. ✅ 验证：临时 Postgres 容器 + 本机 cargo run → curl 全链路（2026-08-31 实测通过）：
   注册申请（同名在途 409 / 非法名 400 / 换名骗码 400）→ psql 代绑 → verify 建号（reg_seq 1/2）→
   登录（错误密码 401）→ 传圈六用例（Toast 四条件全部正确）→ 积分总榜/版本榜（#1=100/#2=1，版本隔离 ✓）→
   赛道榜（总榜/版本榜+圈时格式化 ✓）。修复 3 bug：records 主键 NULL 约束（alltime 行 version_code=0 占位）、
   同名并发注册、Toast 双纪录维度耦合误报（version 首建缺失导致更差圈误报 alltime_server）。

**S2 — 模块上传链路（实机可测）** ✅（2026-08-31，`f848a3d`，代码完成+三闸通过；实机验证待设备接入）
1. ✅ `PaddockClient`（HttpURLConnection 零新依赖，游戏进程直传；本地待传队列 30 天 TTL 上限 200；401/网络失败入队）。
2. ✅ lap_hook order==2 → native 单槽 (gp_index,lap_ms,seq) → JNI `pollLapUpload`/`markLapUploadConsumed` → `PaddockUploader` 1Hz 主线程轮询。**零新 hook**，沿用 intro/TcAbs 轮询惯例。
3. ✅ 响应处理：Toast 四条件取最高（服务端判定文案直达）+ 未登录自动入队。
4. ✅ 登录/注册 API 客户端已就绪（S3 的围场页直接消费）。
5. ⬜ 实机验证：计时赛刷圈 → 成绩上榜 → 破纪录 Toast（等设备）。

**S3 — 围场 UI（完整体验）**
1. ✅ 底栏"围场"页（配置与设置之间，页 2）：通行证核验（登录/注册两步流：申请码→复制→发群→贴码 verify 自动登录）；已登录显示用户名卡+退出。方格旗图标自绘 ChequeredFlagIcon（`acc9970`）。
2. ✅ 排行榜子页（`e47e15f`）： Route.Paddock 二级导航；积分榜/赛道榜 tab + 16 赛道中文名 spinner + 版本筛选（总榜/8.0.4）；数据 PaddockClient.fetchPointsBoard/fetchTrackBoard。
3. ⬜ 头像上传+裁剪（vanniktech/android-image-cropper 4.6.0）——依赖 Garage 对象存储通路（S4 一并）。
- 服务器地址覆盖 UI + 游戏进程读取链路：TODO（当前 PaddockClient 用内置默认占位域名，部署后替换并接 ModConfig）。

**S4 — CAMDA bot**
1. webhook 回调处理器（并入 axum，Ed25519 签名校验）+ 全量群消息三处配置核对。
2. 注册校验码监听→绑定 member_openid→被动回复（5 分钟窗、4 次限额、发送队列防频控）。
3. 重置密码一次性码；冲突/过期提示文案。
4. 上线前：平台审核状态确认（正式 bot 已有 [D]）、IP 白名单写入 VPS 地址。

## 7. 里程碑验收

- S1 末：本地 compose up 后 curl 走通注册→传圈→榜单。
- S2 末：实机一局计时赛，成绩出现在版本榜，破个人纪录 Toast 出现。
- S3 末：真实手机全流程注册（含 bot 或管理端代绑）进围场看榜。
- S4 末：QQ 群真实注册一位新用户全流程无人干预。

## 8. 风险与未决

- ⚠️ **Postgres+Garage 常驻 ~300–400MB** 与"极致低占用"目标的张力：VPS 选 2GB；若未来要压回 1GB，Garage→磁盘目录、Postgres 不可去（比赛服务器仍需要）。管理端保留存储方式配置项即为此留的口子。
- ⚠️ **全放行防伪**：作弊成绩只能事后删，删除后需重算积分/records（写明管理端删除即触发重算事务）。
- [?] QQ webhook 的 Ed25519 签名验证细节（计划 S4 实现时以官方文档核对）。
- [?] `member_openid` 在用户退群后的语义（能否继续 bot 私聊触达）——影响重置密码流程可用性，S4 首个实测项。
- [?] versionCode 与"版本"的显示转换表（200146 ↔ 8.0.4）在 UI 的呈现方式。
- 管理端"代绑 openid"是 S1 的过渡桥（bot 未上线时注册靠它），S4 后降级为备用。

## 9. 仓库布局与协作约定

- paddock 仓库：`/home/takotsubo/projects/ala-mobile-paddock`（与模块仓库**同级**，独立 git）。
- **会话始终开在模块仓库目录**（`ala-mobile-tool`），两边在同一会话内配合开发——跨仓库改动（契约变更、赛道表同步）必须同会话内两侧联动，不允许分仓分会话各自演化。本计划文档是两边共用的唯一契约源，paddock 仓的 README 指回本文档。

## 10. 与本仓库的边界

- 本仓库改动集中在 S2/S3：`PaddockClient`、lap_hook JNI 上抛、围场 UI、设置页服务器地址覆盖。
- lap_hook 现有红线全部沿用：order==2 判定、日志只在边界、LAPsession 行保留（模式标签未来 UI 用）。
- 计时赛门禁不变：只有 `isTimeAttack` 会话的圈才会上传（v2 门禁 9/9 实证）。