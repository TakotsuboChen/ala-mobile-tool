# HANDOFF — 读全文再开始干活

生成时间: 2026-09-02T18:35:00+08:00 · Git HEAD: `00b0795`（模块仓）/ paddock 仓 `f489214`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `00b0795`（2026-09-02）；paddock 仓 `main` @ `f489214`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `00b0795`；paddock 仓同理对 `f489214`。不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源）

## 1. 当前目标
**围场排行榜大规模数据实测 + 性能/动画时序重做**。本会话：服务器注入 205 名测试车手（用户名+头像+全 16 赛道圈速）实测榜单 → 据实测反馈重做 `LeaderboardScreen`（LazyColumn 行级 items / 头像降采样 / 排名居中 / 两阶段切换动画 / 尾部留白），4 轮迭代全部落地并装机 → 测试数据已全部清理，服务器回到真实状态。完成定义已达成（排行榜 205 人规模实测通过，动画无闪现无掉帧）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **排行榜重做已提交推送**（模块仓 `2aed156` + CLAUDE.md `00b0795`），release 已装机（adb 192.168.50.142:39345，Success）
- [V] **测试数据已全部清理**：`DELETE FROM users WHERE reg_seq>=1000` → DELETE 205；核对 users=1/max_seq=3、laps=10（8 条测试前 + 2 条用户本轮实测上传的真实圈速）、best_laps=2、records=4（全是 Takotsubo 真实纪录）。paddock 仓无改动
- [V] **实时刷新定为"暂时不做"**（用户明确拍板）：当前只有进页面+切筛选时拉取，停留期间是静态快照
- [V] 构建：`compileDebugKotlin`/`lint`/`assembleRelease` 全 EXIT=0（53 warnings 全在 baseline 内）；用户 4 轮视觉验收：排名对齐✓ 无掉帧✓ 无闪现✓（第 4 轮尾部留白修复后未复测）
- [V] 工作区：模块仓干净已推送；paddock 仓干净（HEAD `f489214` 未动）

### 测试/build 输出（真实退出码）
```
./gradlew :app:compileDebugKotlin → BUILD SUCCESSFUL（4 轮每轮都跑）
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0（53 warnings baseline 内）
./gradlew :app:assembleRelease → BUILD SUCCESSFUL, EXIT=0
adb install -r app-release.apk → Success（无线 adb，端口本会话从 41673 漂到 39345）
服务器注入：users/laps/best_laps 205/13075/3280 → 清理后 1/10/2
```

## 3. 决策与理由
- **测试数据锚点用 `reg_seq >= 1000`**（真实用户最大 3）——一条 DELETE 级联清 sessions/laps/best_laps；测试圈速硬下限 95s 慢于真实纪录，避免 `records.user_id ON DELETE SET NULL` 留无主脏纪录。
- **头像直插 SQL 但上传走真实 API**（并发 8 经 SSH 隧道→VPS 8080）——argon2×205 太慢；头像链路（SigV4→Garage→落库）顺带压测，205/205 零失败。
- **榜单行必须是 LazyColumn items 而非 Card+forEach**——205 行全量组合在数据到达帧撞上转场动画掉帧；连体卡视觉由行级 surfaceContainer 底色+首末行圆角拼接替代。
- **两阶段切换：渲染数据（visibleBoard）≠ 筛选状态**——旧行显式 Animatable alpha 立即渐隐（与筛选卡伸缩并行）→ 完成后换 visibleBoard → 新行 animateItem 淡入。渐隐不用 animateItem 的移除淡出（换源帧才启动，撞布局动画尾巴=闪现）。
- **animateItem 而非手动 Animatable 做行淡入**——snapTo(0f) 在 LaunchedEffect 协程里跑，新数据先以 alpha=1 渲染一帧才被拽回 0，那一帧就是闪现；animateItem 由框架在首帧前定初始 alpha。
- **进入转场 500ms 结束后才渲染行**（delay(NAV_ENTER_SETTLE_MILLIS)）——miuix NavDriver `PROGRAMMATIC_DURATION_MILLIS=500` 契约值；数据请求与转场并行，只推迟进组合树，总等待不变。
- **排名数字 `textAlign=Center` + 28dp 固定槽**——Compose Text 默认靠左，多位数排名挤向头像列。
- 继承：/v1/me 全链路 / 注册流 v2 / token daemon / 全放行防伪 / order==2 挂圈 / 拦截器零浮点 / miuix preference 全盘照搬。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/`。

### 本轮新增（排行榜性能/动画 4 轮迭代）
- [X] **生成圈速 `max(ms, 95000)` 钳位保底** → 正态采样 90% 低于下限全被压到同一毫秒 → 几百人并列 1:35.000、积分榜一片 1600。修复：分布整体定义在 [95s,125s]（三角分布）+同赛道撞值+1ms 错开。教训：保底要靠"分布在界内采样"，不能"先采样再砍"；造数脚本先跑重复值自检再入库（v2 首版 dups=99~171 被自检拦下）。
- [X] **Card 内 forEach 渲染 205 行** → 数据到达帧一次性全量组合，撞 Crossfade 动画中途掉帧（用户："动画进行到一半就卡一下"）。修复：行改 LazyColumn items。
- [X] **手动 Animatable + snapTo(0f) 做行淡入** → 数据同帧先以 alpha=1 渲染、协程下一帧才拽回 0 → 闪现且动画被打断（用户："会闪现，没有动画"）。修复：`Modifier.animateItem()`（框架首帧前定初始 alpha）。
- [X] **换源直接读筛选状态（visibleBoard 未与筛选解耦）** → 旧行瞬消+新行瞬现，淡出/淡入同帧竞争（用户："切换还是闪现"）。修复：visibleBoard 独立状态+switchSeq 协程换源。
- [X] **换源 delay(150ms) 排在筛选卡伸缩动画之后** → 伸缩刚结束的那一帧移除+插入同帧发生，仍闪现（用户："筛选条件伸缩完了之后排行榜闪一下"）。修复：渐隐立即启动（显式 alpha）与伸缩并行，换源发生在 alpha=0 空档。
- [X] **Card→行级 items 丢了底部留白** → 榜单滑到底卡片贴屏幕底边（原 Card 在 Column spacedBy(12dp) 里的外距被拆掉）。修复：items 末尾补 Spacer(12.dp) item。
- [X] **SQL 直插 VALUES 不带类型标注** → `INSERT...SELECT t.*` 报 `user_id is of type uuid but expression is of type text`。修复：`t.user_id::uuid` 显式 cast（本地脚本已同步修）。
- [X] **adb 无线端口固定假设（再次）** → 端口又变（41673→39345），mdns 重扫连最新条目。每次装机先 `adb mdns services`。

### 继承死路（全部 [X] 前向有效，详见 .handoffs/20260902182211-handoff.md §4）
- fetchMe 无鉴权 getJson 漏发 Authorization → 401 自动登出 / Crossfade key 绑筛选条件闪两次 / TopAppBar navigationIcon 只画不绑事件 / 弹窗文字默认观感太矮小 / Card 内手写内容贴边 / OverlaySpinnerPreference 迁移丢参数 / `clickable` 包名是 foundation / adb 端口漂移。
- 两进程共用代码引 `AlaMobileModule.logX` → NoClassDefFoundError 伪装"网络错误" / PaddockClient 单例只在游戏进程 init / externalFilesDir 跨进程不可见（token 走 daemon）/ CropImageActivity 主主题闪退+NoActionBar 白屏 / 改代码只跑 assembleDebug 装旧 release / sqlx HRTB / axum 0.8 `:param` panic / zsh `$UID` / VPS SSH `takotsubo@8.134.50.222 -p 4142`+docker sudo+compose 文件名是 docker-compose.vps.yml。
- lap_hook 三版演化 + 指示灯信号链 + FPSIMD：圈完成等 order 2→0 回绕 / sectorOrder 1-based / gate v1 champ==NULL 放行 / trackToRace 当赛道名 / 拦截器浮点操作 / ABS v1-v5 / proxy_shift 日志洪水 / IL2CPP dlopen / 后台线程调 Unity API / records 复合主键 NULL 维度 / askama 单引号 / axum nest("/admin") 404 / Write 工具输出污染。
- QQ bot webhook：bearer_auth 调 QQ API / 被动回复用 `d.id` 非 event id / member_openid 在 `d.author` / 凭据未配置时信平台报错 / VPS cargo build OOM（本地 build+scp）/ 诊断先加 payload 原文日志。

## 5. 已知坑
- ⚠️ **群全量消息（不@）平台至今不推送** [V]——配置全开、@ 消息正常，不@静默丢弃。处理代码已就位，堵住"注册不依赖 @"。需提工单或接受 @-only。
- ⚠️ **bot 新回复文案（带车手序号）群内未复测** [?]——v2 建号逻辑代码验证过，真实群内"发申请码→@回复带序号"全流程待跑。**v1 完成定义最后遗留项**。
- ⚠️ **Garage 206 个测试/遗留头像对象** [V]——bucket `avatars` 里 `avatars/aaaaaaaa-…`（205 个本轮测试）+`avatars/98f7fad3…`（旧测试）无 shell/aws cli 清不掉；无害（按 user_id 命名不复用）。
- ⚠️ **Toast 文案"蒙扎国家赛车场 的"多空格** [V]——parseToast 模板 `$track 的`，track 值可能带尾空格；纯文案瑕疵，用户未点名修。
- ⚠️ **lint baseline 13 条失效** [V]——下次重新生成。
- ⚠️ **排行榜无实时刷新**（用户拍板"暂时不做"）——只有进页面+切筛选时拉取；候选方案（下拉刷新/RESUME 刷新/轮询）已分析留给用户选。
- ⚠️ 继承：双仓赛道中文名两份硬编码（契约源 PADDOCK_PLAN §5，`Shangai` 单 a）；继承 [?]：多指日志回传、16 赛道 12/16 未实机、门禁 champ==NULL 多语义、矩阵空格。

## 6. 下一步（有序）
1. **群内复测 bot 新文案**：发 `@bot 申请围场通行证#码` → 预期回复"校验成功…您是全服第 x 位车手！请返回模块直接点击登录。"（v1 完成定义最后遗留）
2. **用户复测第 4 轮修复**：榜单滑到底部应有 12dp 底部留白（装机后未复测）。
3. **重构**（用户定案"下个版本重构模块和服务端的一堆东西"）——范围待用户指定。
4. （可选）排行榜实时刷新：用户已拍板暂不做，重启话题时从下拉刷新方案起。
5. （可选清理）Toast 文案空格、lint baseline 重新生成、Garage 测试对象。

## 7. 留给用户的开放问题
- 群消息不@推送：提工单还是接受 @-only？影响注册 UX。
- 计时赛积分卡"暂无"与"加载失败"是否需要区分显示？
- 大奖赛/娱乐匹配占位卡真做时：赛季积分/胜场数据从哪来（服务端目前只有计时赛成绩）？
- 重构范围：用户说"下个版本重构模块和服务端的一堆东西"，具体清单待定。
- 继承：多指日志回传裁决、工具按钮重置入口、指示灯亮度调节等（未恶化不动）。