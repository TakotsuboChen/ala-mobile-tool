# PADDOCK_PLAN — 围场私服第 1 期：圈速排行榜执行计划

> 状态：**活跃** · 最后编纂：2026-10-26
> 一句话结论：围场私服第 1 期契约源：API 形状、积分公式（v39 线性递减）、赛道显示名——模块与 `ala-mobile-paddock` 服务端必须同会话双改。

> 文档规范见 [`docs/README.md`](../README.md)。


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
| 积分公式 | `total = base + bonus`。**基础分** `base = round((N+1−rank)×100/N)`：第一 100，每名次递减 100/N，第 N 名 100/N，虚位第 N+1 名 0；N=1 自然得 100 无需特判（v40 修正定案。例：2 人 → 100/50/虚位0）。⚠️ v39 曾写成 `(N−rank)×100/N`，与例句 100/50/虚位0 矛盾（off-by-one，SQL 实现抄了错的那半，致 2 人榜 50/0），v40 起以本行为准 [D]。**辅助加成**（v41，2026-09-18）：线性踏板 +10% / 关 TC +10% / 低档 ABS +10% / 关 ABS +30%（最大 50%），`bonus = base>0 && pct>0 ? max(1, round(base×pct)) : 0` [D] | 每赛道×每版本独立；总榜=各版本独立计分后累加（版本=独立赛季：两版本全赛道第一=3200）。⚠️ 加成只加在**基础分**上（不是总分复利） |
| 辅助配置上报 | `POST /v1/laps` 附带 5 个**原始枚举**（pedal_mode / tc_mode / tc_strength / abs_mode / abs_strength），服务端派生布尔位；缺失 = 不发字段 = 不加分 [D] | 客户端只报事实、规则留在服务端（调规则不必发模块版本）。**未开自定义（mode=default）= 游戏默认 = 绝不算"关"**——此时 strength 只是 UI 记忆值 [D] |
| 零辅助金标 | 赛道榜：该车手**个人最快圈**为零辅助（TC 与 ABS 均关）→ 整行金字；积分榜：该用户零辅助最快圈占比 ≥50%（版本榜 `2×零辅助 ≥ 总数`；总榜另需 ≥1 条）→ 金字 [D] | 判据**只看 TC/ABS，不看踏板**（原生按键物理上跑不出有效圈）[D]。金标由服务端判定（响应带 `gold` 字段），客户端不重算 |
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
hidden_missing_laps (user_id, lap_id, hidden_at,
              PRIMARY KEY(user_id, lap_id))            -- 2026-10-20：屏蔽缺失配置记录
              -- ⚠️ **纯审计记录**（2026-10-20 二次定案起）：记「谁在哪一刻屏蔽了
              -- 哪些圈」，**不参与任何判据**。判据已升级为谓词
              -- `AUTO_HIDDEN_SQL` = `users.hidden_missing_at IS NOT NULL`
              -- AND 该圈五维任一 NULL。
              -- 为什么改：首版按 lap_id 记（快照语义），与配置状态完全解耦 ⇒
              -- 管理端补全配置后红标不消失、圈也回不了榜（实机报障根因）。
              -- 谓词语义下：补全配置即自动解除，开关打开期间新传的缺失圈自动
              -- 被屏蔽。范围仍是该用户**全部**缺失配置的圈（不是每维度获胜圈
              -- ——那会让次快的缺失圈接棒成「最佳」）。
              -- 积分/榜单从 best_laps 删行即自动生效（score.rs 是唯一窗口定义）；
              -- 红标（driver.rs）、`recalc_dims` 重算排除、`upload_lap` 闸门
              -- 三处全部走同一个 `laps::AUTO_HIDDEN_SQL`。
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
    （2026-09-06：users.avatar_version=上传 epoch millis，榜单/ /v1/me 的 avatar_url
     带 ?v=<version>——客户端磁盘缓存失效开关；响应带 Cache-Control: immutable）
    （2026-10-20：POST /v1/me/avatar 加 **30 天冷却**——`users.avatar_updated_at`
     非空且未满 30 天时返回 429 + 中文剩余天数文案。⚠️ 冷却检查必须在 S3 写入
     **之前**：先传后拒会把 Garage 对象换掉而 avatar_key 未更新，URL 版本号不变
     却指向新内容，客户端按版本号失效的磁盘缓存会一直显示旧图。）

--- 账号设置（2026-10-20 新增，`api/account.rs`）---

GET  /v1/me/account             (Bearer) → {avatar_cooldown_secs, username_cooldown_secs,
                                            hidden_missing, has_hidden_candidates}
    账号设置页的全部状态。**冷却剩余秒数由服务端时钟算**（本机时间可被用户改，
    客户端不自行推算"上次操作 + 30 天"）。`has_hidden_candidates` 口径 =
    该用户**全部配置缺失的圈数 > 0**（与 `hide-missing` 的选取集合同源——
    早期算的是「榜上获胜圈缺失的维度数」，会让"获胜圈完整、次快圈缺失"的用户
    被判成无可屏蔽记录，2026-10-20 修正）。
PUT  /v1/me/username            (Bearer) {username} → {username, cooldown_secs} | 409
    ⚠️ **用户名就是登录名**（用户定案 2026-10-20）：改完必须用新名字登录，
    旧名字因 UNIQUE 约束被释放、可被他人注册。30 天一次（`users.username_updated_at`）。
    校验复用 `auth::validate_username`（与注册同一套规则）。
POST /v1/me/hide-missing        (Bearer) → {hidden: 被屏蔽的圈数}
    | 400「您没有可以屏蔽的圈速记录」| 409「您已经屏蔽过…该操作无法撤销」
    **一次性、不可撤销**（用户定案）。语义 = 配置缺失的成绩不算数：
    ① **先**落开关 `users.hidden_missing_at`（**必须早于任何重算**——判据依赖它，
       后写则谓词恒假、一条都排除不掉）；② 把当前全部缺失圈的 lap_id 记进
       `hidden_missing_laps`（**纯审计**，不参与判据）；③ 对这些圈涉及的每个
       (赛道,版本) 维度重算 `best_laps`（先删行，再从剩余圈回放，按谓词排除）；
    ④ 重算受影响赛道的 `records`（全服纪录也要让位）。
    ⚠️ **判据是谓词不是快照**（2026-10-20 二次定案）：
       `AUTO_HIDDEN_SQL` = 「开关打开」且「该圈五维任一 NULL」。因此开关效果持续：
       补全配置 → 自动解除并回榜（管理端编辑保存即生效）；开关打开期间新传的
       缺失圈 → 自动被屏蔽。首版是快照（只处理点击当时那批），实机报障为
       「管理端补全配置后红标仍不消失」。
    ⚠️ **范围 = 全部缺失圈，不是「每维度获胜圈」**（2026-10-20 修正）：
       首版用 `DISTINCT ON (gp_index, version_code)` 只取每维度最快的圈，结果
       每维度最多屏蔽 1 条；被屏蔽的获胜圈移出 `best_laps` 后，回放会挑**下一条
       最快的圈**顶上——而它往往同样缺失配置，于是「缺失」与「最佳」同挂一条，
       用户看到「只有少部分显示已屏蔽，还有一堆没屏蔽，缺失配置的还显示最佳」。
       实机数据：飞行雪绒 65 条缺失圈只标了 7 条（= 7 个维度的获胜圈）。
       `GET /v1/me/account` 的 `has_hidden_candidates` 判据同步改为
       「全部缺失圈数 > 0」，与屏蔽动作的选取集合同源。
    ⚠️ **三处路径必须共用同一个谓词**，漏一处就是"榜上不见但积分还在"或
       "屏蔽了又回来"：`driver.rs` 红标、`admin::recalc_dims` 重算、
       `upload_lap` 的入库闸门（屏蔽时 best_laps 行已删，原 UPDATE 命中 0 行会
       走 insert 分支，不看判据直接插回榜单）。
POST /v1/laps                   {gp_index, lap_ms, version_code,
                                 pedal_mode?, tc_mode?, tc_strength?,
                                 abs_mode?, abs_strength?,
                                 car_id?, car_era?,
                                 setup_front_wing_aero?, setup_rear_wing_aero?,
                                 setup_front_ride_height?, setup_rear_ride_height?,
                                 setup_brake_bias?,
                                 setup_front_stiffness?, setup_rear_stiffness?,
                                 setup_front_rollbar?, setup_rear_rollbar?,
                                 setup_front_camber?, setup_rear_camber?,
                                 setup_front_toe?, setup_rear_toe?}   (Bearer)
                                                    → {personal: bool, server: bool,
                                                       toast: null|{level, track}}
    （2026-09-18 加辅助配置五维：全部可选，缺省 = 缺失（旧模块零改动即天然缺失）。
     枚举：pedal_mode=off|single|dual；tc/abs_mode=default|custom；
     tc/abs_strength=off|weak|medium|strong|stock。未知值被服务端归一化为缺失——
     宁可记缺失也不丢圈。⚠️ 客户端只报原始值，派生规则（关/低/线性）在服务端
     `score.rs`，改规则不必发模块版本。）
    （2026-10-26 加车型 + 车辆调教，全部可选、flatten 接入，旧客户端零改动：
     **车型只报整数** `car_id`(0..41) + `car_era`(0=地效/1=主动空力)，服务端
     存整数、**不存展示名**——「2026 梅赛德斯-AMG W17」由模块侧 `CarCatalog`
     反查渲染，改车队译名只发模块、历史圈自动跟着变。
     **调教存的是 UI 显示值本身**（下压力 65.0 不是 0.65，外倾角 -3.0 度不是
     弧度），服务端不做换算。整组**全有或全无**：13 个字段缺任一 → 整组落
     null（半套调教比缺失更误导），由 `score::CarSetupConfig::normalized` 强制。
     ⚠️ `setup_brake_bias` 只有**前**值，后 = 100 − 前——游戏 UI 的 `60 / 40`
     是派生量，**不要加 rear 列**（会出现两个真值源）。）
GET  /v1/leaderboard/points?version=&limit=&offset=   → 积分总榜/版本榜（分页）
     条目含 points / best_count / zero_count / gold（金标由服务端判定）
     响应含 total（该榜总条数，客户端据此判断是否还有下一页）
GET  /v1/leaderboard/track/{gp_index}?version=&limit=&offset= → 赛道榜（分页）
     条目含 gold（该车手个人最快圈是否零辅助 → 整行金色流光）+ rank（**全局名次**，非页内序号）
     响应含 total
     **圈速详情的取数路径（2026-10-28 定案：服务端零改动）**：
     赛道榜行只有榜单摘要，完整记录（created / pedal / tc / abs / car_id / setup）
     在 `laps` 全量表里，`best_laps` 读模型没有 ⇒ 客户端点击时**二次请求**
     `GET /v1/driver/{uid}?gp_index={visTrack}&version={visVersion}&best=1&sort=lap_asc&limit=1`
     取那一条。两处同源：赛道榜是 `DISTINCT ON (b.user_id) ORDER BY lap_ms ASC`，
     driver 的 `best=1` 分支按 `best_laps.lap_ms` 相等判，取到的必然是同一圈。
     ⚠️ **筛选条件必须用 `visibleBoard` 的 (track, version)，不能用 `selectedTrack/selectedVersion`**：
     榜单切换有 150ms 显式淡出窗口，这期间点到的仍是旧行，现读筛选条件会拿新条件
     去查旧行 ⇒ 版本/赛道对不上的圈速，且**不报错**。
     ⚠️ 否决「服务端扩展榜单端点 JOIN laps 一并下发」：响应体约 ×3（30 行 × 15 字段）、
     要同步本契约并重新部署 VPS，换来的只是省一次 200~500ms 往返。
     见 memory `lap-detail-via-driver-endpoint`。
GET  /v1/me                      (Bearer) → {user_id, username, reg_seq, has_avatar, avatar_url, total_points}
    （2026-09-01 实现：模块重进后恢复登录态展示；total_points=计时赛总积分，与积分总榜同口径
     （v39 起=各版本独立计分累加，无成绩=0）；401=token 失效→模块自动登出；
     2026-09-06 加 avatar_url=版本化头像 URL（无头像=缺省））
GET  /v1/driver/{user_id}?gp_index=&version=&assist=zero&best=1&sort=&limit=&offset=
                                 → {profile, entries, total}（车手主页，公开端点）
    （2026-10-18 新增。profile = {user_id, username, reg_seq, avatar_url, total_points,
     total_rank, best_count, zero_count, gold, version_points, version_rank,
     version_code, version_name, record_count, record_rank}；entries = laps 全量表的分页
     （不是 best_laps），每条含 lap_id / gp_index / track_name / version_code /
     version_name / lap_ms / lap_display / personal_best / pedal / tc / abs /
     zero_assist / created（北京时间）/ **hidden**。
     （2026-10-20 加 `hidden`：该圈被本人「屏蔽缺失配置记录」移出了榜单 →
     客户端显示红色「已屏蔽」标签。与 `personal_best` 互斥——屏蔽即删 best_laps 行。
     ⚠️ 判据是谓词 `AUTO_HIDDEN_SQL`（开关打开 AND 该圈配置缺失），**不是**
     `hidden_missing_laps` 的 lap_id 匹配——后者是快照语义，补全配置后红标
     永远不消失，用户实机报障即此。）
     （2026-10-26 加 `car_id` / `car_era` / `setup`：车型给整数、展示名客户端
     反查；`setup` 是 13 个 float 的对象，**整组全有或全无**，全 null = 缺失。）
     筛选：gp_index 0..15、version=游戏 versionCode、assist=zero（TC 与 ABS 均关）、
     best=1（仅个人最佳，与榜单 best_laps 同源）。404 = 车手不存在。

     **2026-10-19 扩展（车手主页三页改版 + 排序）**：
     - **`sort` 排序方式**（2026-10-19 新增，四选一）：
       `time_desc`（缺省）= `created_at DESC` / `time_asc` = `created_at ASC` /
       `lap_desc` = `lap_ms DESC`（慢的在前）/ `lap_asc` = `lap_ms ASC`（快的在前）。
       **未知值回落 `time_desc`**（前向兼容：新版客户端传新值、旧版服务端不报错）。
       命名跟数值方向而非"好坏"：`lap_asc` 是毫秒数升序 = 圈速快的在前。
       ⚠️ 四个分支都必须带 `l.id` 兜底键且方向与主键一致——排序键有重复值时
       （同秒上传、同毫秒圈速）LIMIT/OFFSET 跨页会重复或漏项。
     - `total_rank` / `version_rank` / `record_rank` 均为 **`Option<i64>`**（无成绩 = JSON
       `null`，**不是 0**）。名次语义 = **榜单行号**，用 `row_number()` 而非 `rank()`
       ——后者同分并列会跳号（1,1,3），主页就会显示"第 2 名"而榜单里他在第 3 行。
       `row_number()` 的排序键与积分榜 `ORDER BY points DESC, username ASC` 逐字一致。
     - **`version_*` 的"最新版本"由服务端现取 `max(best_laps.version_code)`**，不硬编码：
       游戏升版后新圈入库即自动切换，无需发版。`version_code`/`version_name` 一并下发
       供客户端展示（如「8.0.6」）。
     - **`record_count` 口径 = 版本 × 赛道**（`records` 表 `kind='version'`，
       主键 `(gp_index, kind, version_code)` ⇒ 每赛道每版本恰好一条）。
       同一条赛道在 8.0.4 与 8.0.6 各算一条，**不跨版本混算**（用户 2026-10-19 定案）。
     - 积分聚合已收口到 `score::user_points_and_rank`（单一实现点），
       榜单 / `/v1/me` / 车手主页三处共用 `score::scored_cte` 生成的窗口定义。）
```

### 分页契约（2026-10-18 定案）

- **单页默认 30 条**（`DEFAULT_PAGE_LIMIT`），服务端硬上限 100（`MAX_PAGE_LIMIT`）。
  模块端 `PaddockClient.PAGE_SIZE = 30` 必须与服务端同值。
- 三个榜单端点都返回 `total`（**不受本页 limit/offset 影响**的总条数），客户端
  `entries.size >= total` 即判定到底，不再发请求。
- **排序必须在 SQL 里做完再 LIMIT/OFFSET**：Rust 侧 `sort_by` 后切片会让分页
  语义失效（切的是未排序结果的前 N 条，跨页重复/漏项）。赛道榜的 `rank` 用
  `row_number() OVER (ORDER BY lap_ms)` 全局算，不能在 Rust 里 `enumerate()`。
- **稳定排序键**：赛道榜 `lap_ms ASC`（同秒并列按 DISTINCT ON 后的顺序），
  车手主页 `created_at DESC, lap_id DESC`。无稳定键时 LIMIT/OFFSET 可能跨页漏项。

错误码明确返回（401 掉登录态→模块进缓存补传路径）。

### 注册流程（v4，2026-09-04 定案：申请即设密 + 幂等恢复 + 建号时发最小空缺号）

```
模块：输入用户名+密码 → POST register-request（服务端哈希密码落 pending_regs）
     → 弹窗展示"申请围场通行证#码"（点击复制指令）→ 用户复制后发 CAMDA 群
bot：群内匹配码 → 建号事务（车手号=最小未占用正整数）→ 回复欢迎语（含车手号）
     → 用户回 App 登录 → has_avatar=false → 跳头像上传页
```

- **车手 ID（reg_seq）在 bot 建号时发放**（v3+ 变更）：`min(正整数 not in 已占用)`，
  弃号立即回收；并发撞号由 reg_seq 唯一约束兜底（建号失败可重试）
- **重复点"注册"幂等恢复**（v33+）：同名在途 + 密码一致 → 返回原 reg_code（200，
  模块端弹窗自然重弹）；密码不一致 → 409（防用户名抢占攻击）
- 密码在申请时一并设置（服务端哈希落 pending），bot 建号时直接使用——
  模块端无 verify 步骤、无校验码回填
- **重置密码**：群内发「我需要重置密码」严格匹配 → bot 按群身份（member_openid）
  反查账号签发一次性重置码；单聊不支持（user_openid/member_openid 两体系不互通）
- **查询用户名**：群内发「查询用户名」严格匹配 → bot 按群身份（member_openid）
  反查账号，回复围场用户名；未注册/无群身份各有独立失败文案；单聊不支持（同重置密码）

### Bot 消息规则引擎（2026-09-12 重构）

- **单一事实源 = `qq_bot::action_metas()`**：每个内置动作（`reply`/`reg_code`/`reset_password`/
  `query_username`）的触发语义、匹配方式（normal/contains/exact）、失败字段与内置默认文案
  全部定义于此。管理端设置页把整表注入前端（`ACTIONS_META`），**前端不再维护任何镜像**
  （此前 JS 里 ACTIONS/FAIL_FIELDS/PRESET_FAIL 三份硬编码曾漂移出「切动作预填错文案」）。
- **新增一个动作 = 两处改动**：① `action_metas()` 加一条 ② 群/单聊 handler 的 `match` 加分支。
  管理端编辑器自动出现该动作及其失败字段。
- **失败文案优先级**：该动作的字段（如 `no_user_template`）→ 通用 `fail_template` → 内置默认。
- **普通回复的身份依赖兜底**：模板含 `{{paddock_name}}`/`{{paddock_id}}`/`{{at_me}}` 而
  发言者账号查不到时，走 `no_user_template`（未注册）/ `no_identity_template`（无群身份），
  **绝不把空串渲染进模板**（旧实现会回「用户名是「」」）。
- 触发词/成功文案/失败文案可管理端编辑；建号事务、openid 唯一约束、码过期等安全边界
  仍在代码里（配置管文案，代码管动作与防重）。

### 版本键（v36+）

- 游戏 versionCode 作为成绩/积分的版本维度键：200146=8.0.4、**200150=8.0.6（当前）**
- 上传 `POST /v1/laps` 按 `version_code` 分维度写 best_laps/records；积分版本榜按
  `?version=` 过滤，总榜按版本独立计分累加（版本=独立赛季）——新游戏版本上线时数据链路零改动，只需：
  ① 模块 OffsetTable.PADDOCK_VERSION_CODE 升新码 ② 服务端 version_display 加映射
  ③ 管理端补录 KNOWN_VERSIONS/弹窗下拉加选项

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
| Dubai | 🇦🇪 迪拜赛车场 |

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