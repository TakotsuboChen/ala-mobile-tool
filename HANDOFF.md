# HANDOFF — 读全文再开始干活

生成时间: 2026-09-03T08:06:00+08:00 · Git HEAD: `aeb5944`（模块仓；paddock 仓 `6fedf56`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `aeb5944`（2026-09-02，本会话无改动）；paddock 仓 `main` @ `6fedf56`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `aeb5944`；paddock 仓对 `6fedf56`（其 parent `9ef6fb3` 是 README 切片）。不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源）+ paddock 仓 `docs/BOT_RULES_ANALYSIS.md`（消息规则引擎现状）

## 1. 当前目标
**围场服务端 Web 管理端 v2 重做 + QQ bot 消息规则引擎**（工作全在 paddock 仓，本会话模块仓零改动）。用户 16 条反馈全部落地（v13–v28 共 16 次线上部署）：管理端页内弹窗 fetch 化、品牌名/Logo、消息规则引擎（条件与或 + 失败文案全可编辑 + 预设 4 条完整呈现）、播报事件分立（alltime/version）、引用回复、分页/搜索、best_laps 缺行存量 bug 修复。

## 2. 已验证状态 — 工作实际停在哪
- [V] **模块仓干净零改动**：`git status` clean，HEAD `aeb5944`，工作切片/持久文档/handoff 三切片全在 paddock 仓完成
- [V] paddock 仓三切片已推送：工作 `8a8c580`（10 文件 +2577/−589）→ README `9ef6fb3` → HANDOFF `6fedf56`
- [V] 线上 v28：`/admin` 200、`/v1/leaderboard/points` 200；生产库迁移版本=5（0005 已回填 best_laps 缺行，巴林圈 85010 榜单可见——用户实测确认）
- [V] 模板 JS 门禁升级：jsdom 真实执行验证渲染产物（本轮抓出 3 次语法/顺序事故，grep 冒烟不可信）
- [V] 模块侧排行榜（上一会话成果）装机在用户手上：LazyColumn 行级 items / 两阶段切换 / 头像降采样

### 测试/build 输出（真实退出码）
```
paddock: cargo check EXIT=0；docker build v28 → DONE；线上 /admin 与 /v1 均 200
模块仓本会话无构建（零改动）
```

## 3. 决策与理由
- **双仓协作节奏**：服务端 UI/规则引擎的多轮迭代全部只动 paddock 仓，模块仓不动——契约（PADDOCK_PLAN §5 赛道表）未变更，无同步需求。
- **模块端需跟进的点已写入 paddock HANDOFF §6.1**：注册回复文案从"@围场用户名 校验成功"改为"校验成功，欢迎 {{paddock_name}} 加入"且不带 @ 前缀（引用回复形态）；模块侧 Toast 模板 `$track 的` 的空格瑕疵与服务端播报文案无关联，独立存在。
- 继承：排行榜行级 items / 两阶段切换 / token daemon / /v1/me 显式 token 红线 / 全放行防伪 / order==2 挂圈 / 拦截器零浮点。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。本轮新增死路全在 paddock 仓（见 paddock HANDOFF.md §4，16 条），模块仓侧完整历史见 `.handoffs/`。

### 继承死路（全部 [X] 前向有效，详见 .handoffs/20260903080000-handoff.md §4）
- 排行榜 4 轮迭代：圈速钳位保底（分布要在界内采样）/ Card forEach 渲染 205 行（行级 items）/ 手动 Animatable snapTo(0f)（animateItem）/ 换源直接读筛选状态（visibleBoard 解耦）/ delay 排在伸缩后（渐隐并行）/ 行级 items 丢尾部留白（Spacer item）/ SQL 直插缺类型标注（::uuid）/ adb 端口漂移（mdns）。
- fetchMe 无鉴权 getJson 漏 Authorization → 401 自动登出 / Crossfade key 绑筛选条件 / navigationIcon 只画不绑事件 / OverlaySpinnerPreference 迁移丢参数 / clickable 包名是 foundation。
- 两进程共用代码引 AlaMobileModule.logX → NoClassDefFoundError 伪装"网络错误" / externalFilesDir 跨进程不可见（token 走 daemon）/ CropImageActivity 主主题闪退 / 改代码只跑 assembleDebug 装旧 release / sqlx HRTB / axum 0.8 `:param` panic / VPS cargo build OOM。
- lap_hook：圈完成等 2→0 回绕 / sectorOrder 1-based / trackToRace 当赛道名 / 拦截器浮点操作（FPSIMD 污染）/ proxy_shift 日志洪水 / IL2CPP dlopen / 后台线程调 Unity API。
- QQ bot：bearer_auth 调 QQ API / 被动回复用 event id 非 d.id / member_openid 在 d.author / **"平台不推全量消息"实为手机QQ群内授权未开**。

## 5. 已知坑
- ⚠️ **群全量消息根因已明** [V]（本轮升级）——手机QQ群内机器人设置"获取群内全部消息"才是开关（用户开启后已能收到），非开放平台开关；已存记忆 `qq-bot-group-full-message-config`。
- ⚠️ **QQ bot 回复文案已改版** [V]——预设模板去 @ 前缀（引用回复形态），模块侧 Toast 不受影响；群内复测待做（paddock HANDOFF §6.1）。
- ⚠️ **Garage 206 个测试/遗留头像对象** [V]（继承）——无害；无 shell/aws cli 清不掉。
- ⚠️ **Toast 文案"蒙扎国家赛车场 的"多空格** [V]（继承）——模块侧 parseToast 模板 `$track 的`；纯文案瑕疵，用户未点名修。
- ⚠️ **lint baseline 13 条失效** [V]（继承）——下次重新生成。
- ⚠️ **排行榜无实时刷新**（用户拍板"暂时不做"）——候选方案已分析留给用户选。
- ⚠️ 继承：双仓赛道中文名两份硬编码（契约源 PADDOCK_PLAN §5，`Shangai` 单 a）；多指日志回传、16 赛道 12/16 未实机、门禁 champ==NULL 多语义。

## 6. 下一步（有序）
1. **群内全流程复测 bot 新文案**（详见 paddock 仓 HANDOFF.md §6.1–6.3）：@bot 发申请码 / 不带码 / 私聊静默 / 播报实测——全部在服务端，模块仓无需改动。
2. **模块侧无待办**：排行榜第 4 轮尾部留白复测（装机后未复测）仍留给用户。
3. **重构**（用户定案"下个版本重构模块和服务端的一堆东西"）——范围待用户指定，可能涉及模块仓。
4. （可选）排行榜实时刷新（用户已拍板暂不做）、Toast 文案空格、lint baseline 重生成、Garage 头像清理。

## 7. 留给用户的开放问题
- 单聊"支持指令"兜底已移除（未命中静默）——是否需要私聊帮助指令？
- 播报受主动消息额度限制（未认证约 4 条/月/群）：接受 / 申请认证？
- 计时赛积分卡"暂无"与"加载失败"是否区分显示？
- 重构范围清单待定。
