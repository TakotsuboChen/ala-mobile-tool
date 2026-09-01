# HANDOFF — 读全文再开始干活

生成时间: 2026-09-02T00:45:00+08:00 · Git HEAD: `c483943`（模块仓）/ paddock 仓 `f489214`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `c483943`（2026-09-02）；paddock 仓 `main` @ `f489214`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `c483943`；paddock 仓同理对 `f489214`。不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源，§4 已落实 GET /v1/me）

## 1. 当前目标
**围场第 1 期 UI 打磨与登录态恢复**。本会话完成：`GET /v1/me` 端点（服务端+模块全链路）+ 登录态恢复 + 用户信息连体卡 + 排行榜/弹窗/设置页共 11 条用户 UI 反馈全部落地。服务端 v12 已部署上线，模块 release 已装机，用户已完成两轮视觉验收（第二轮 11 条全部确认改善）。

完成定义剩余项：QQ 群注册全流程复测（bot 新文案"您是全服第 x 位车手"群内实测）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **paddock v12 已上线**（`paddock-api:v12`，compose 切标签 up -d）。`/v1/health` 200；`GET /v1/me` 无 token → 401"缺少登录态，请先登录围场"。旧 v11 镜像在 VPS 可回滚。
- [V] **`GET /v1/me` 全链路通**：用户实测登录后正常进入（此前 401 自动登出 bug 已修，见 §4）；重进模块恢复用户名/积分待用户最终确认，但链路各环节（fetchMe→ViewModel init→连体卡渲染）代码已验证。
- [V] **模块 release APK 已装机**（22:39 无线 adb `192.168.50.142:41673`，含全部 11 条 UI 反馈修复）。
- [V] 构建：模块 `assembleDebug+lint` EXIT=0（52 warnings 全部在 baseline 内）；`assembleRelease` EXIT=0；paddock `cargo build --release` EXIT=0（仅剩继承的 server_best warning）。
- [V] 工作区：两仓全部提交推送干净（模块仓 HEAD `c483943`，paddock 仓 `f489214`）。

### 测试/build 输出（真实退出码）
```
./gradlew :app:assembleDebug :app:lint → BUILD SUCCESSFUL, EXIT=0（新 shell 复跑）
./gradlew :app:assembleRelease → BUILD SUCCESSFUL, EXIT=0
cargo build --release → Finished EXIT=0（paddock）
用户实测：登录→进入正常 [V]；UI 两轮 11 条反馈全部落地并装机 [V]
```

## 3. 决策与理由
- **`GET /v1/me` 而非登录响应带积分**——积分是动态查询，塞进登录响应会让两处口径耦合；统一从 /v1/me 取，服务端复用总榜 CTE（`GROUP BY user_id, gp_index` + min）保证个人积分与总榜永远一致。
- **401 才登出、网络错误保留 token**（fetchMe needRelogin 语义）——把"token 失效"与"暂时离线"混为一谈会误登出。
- **排行榜 Crossfade 数据驱动**（sealed `BoardState` 含数据本体为 target）——key 绑筛选条件会闪现两次（条件变→"加载中"→数据到）；数据到达才切 state，加载期间保留旧内容。
- **表单校验本地复刻服务端规则**（`validateFormat` 逐字对齐 auth.rs 用户名空格/汉字规则）——错误文案两侧一致；注释标了规则出处，改规则要改两处（已知代价，用户接受）。
- **忘记密码/退出登录走 OverlayDialog 常驻组合树**（`show || mounted` 双状态控制挂载）——退场动画需要组件驻留到 `onDismissFinished`。
- **围场服务器显式保存按钮**（非防抖自动保存）——涉及"重启才生效"的配置给用户确定感；120dp 固定宽≈1/3 输入框宽。
- 继承：注册流 v2 / token daemon 通道 / 头像 SigV4 / 全放行防伪 / order==2 挂圈 / 拦截器零浮点 / miuix preference 全盘照搬。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/`。

### 本轮新增（登录态恢复 + UI 反馈）
- [X] **`fetchMe` 复用无鉴权 `getJson(url)`** → 不带 Authorization → `/v1/me` 恒 401 → ViewModel 判定 token 失效 `clearAuth()` → "登录成功"Toast 后一秒被踢回登录表单。修复：`getJson(url, token)` 显式参数。诊断指纹：`token saved to remote prefs` 日志正常但随后无 fetchMe WARN——漏发 header 的 401 在客户端看就是"合法拒绝"。
- [X] **Crossfade key 绑筛选条件**（Triple(tab,track,version)）→ 条件变即切"加载中"、数据到再切内容，一次操作闪两次，无动画感。修复：sealed BoardState（数据本体在 state 里）数据到位才切。
- [X] **TopAppBar `navigationIcon` 只画 Icon 不绑事件** → 返回键点了没反应。必须显式 `Modifier.clickable { navigator.pop() }`。
- [X] **弹窗文字区太矮太小**（14sp 左对齐）→ 用户点名改居中粗体 17sp+垂直 padding。弹窗标题类文字的默认观感（小字正文）用户不接受。
- [X] **Card 内手写内容贴底边**（说明文字 Row、保存按钮 Row 均踩过）→ miuix Card 无 contentPadding，preference 组件的 insideMargin 替你管边距，手写 Column/Row 必须自己带全四周 padding。
- [X] **OverlaySpinnerPreference 换组件丢 startAction** → ArrowPreference→Spinner 迁移时漏带图标，用户点名"没让你去掉图标"。迁移 preference 组件时逐参数对照。
- [X] **`clickable` import 包名**：正确是 `androidx.compose.foundation.clickable`（miuix 源码证实），`androidx.compose.ui.clickable` 不存在。Modifier 扩展的包名以编译器为准，别猜。
- [X] **adb 无线端口固定假设** → 手机重开无线调试后端口变化（41433→41673），旧端口拒绝连接。自救：`adb mdns services` 看当前真实端口，选最新条目连。

### 继承死路（全部 [X] 前向有效，详见 .handoffs/20260902003000-handoff.md §4）
- 两进程共用代码引 `AlaMobileModule.logX` → NoClassDefFoundError 伪装"网络错误"（只准用 Logger）/ PaddockClient 单例只在游戏进程 init / externalFilesDir 跨进程不可见（token 走 daemon）/ CropImageActivity 主主题闪退+NoActionBar 白屏 / 改代码只跑 assembleDebug 装旧 release / sqlx HRTB 泛型闭包 / axum 0.8 `:param` 路由 panic / zsh `$UID` / VPS SSH `takotsubo@8.134.50.222 -p 4142`+docker sudo。
- lap_hook 三版演化 + 指示灯信号链 + FPSIMD：圈完成等 order 2→0 回绕 / sectorOrder 1-based / HandleSectorsTimes 当过线单事件 / gate v1 "champ==NULL→放行" / trackToRace 当赛道名 / bestLapTimeInfo 当历史最佳 / raceModes 当判据 / 切片提交混 git rename / 拦截器浮点操作 / ABS v1-v5 / 指示灯 v1-v5 / proxy_shift 日志洪水 / IL2CPP dlopen / 后台线程调 Unity API / records 复合主键 NULL 维度 / Toast 双维度共用出口 / askama 单引号 / miuix SuperArrow / sqlx::migrate! 相对路径+query_as! 宏离线 / axum nest("/admin") 带斜杠 404 / Write 工具输出污染。
- QQ bot webhook：bearer_auth 调 QQ API（要 `QQBot {token}`）/ 被动回复用事件外层 id（要用 `d.id`）/ member_openid 声明在 d 顶层（在 `d.author` 嵌套）/ 凭据未配置时信平台报错文案 / VPS 上 cargo build（OOM，本地 build+save/scp）/ WSL 密钥直用 /mnt/d / 诊断盲区靠猜（先加 payload 原文日志）。

## 5. 已知坑
- ⚠️ **群全量消息（不@）平台至今不推送** [V]——配置全开、@ 消息正常，唯独不@静默丢弃。处理代码已就位。堵住"注册不依赖 @"需求。需提工单或接受 @-only。
- ⚠️ **bot 新回复文案（带车手序号）群内未复测** [?]——v2 建号逻辑代码验证过，但真实群内"发申请码→@回复带序号"全流程待跑。
- ⚠️ **QQ 被动回复无真 @mention API** [V]——回复的 `@用户名` 只是文本前缀，群里显示为普通文字。
- ⚠️ **Garage 22 字节测试对象残留** [V]——bucket `avatars` 里 `avatars/98f7fad3…`（已删用户的测试头像）无 shell/aws cli 清不掉；无害（按 user_id 命名，不会复用）。
- ⚠️ **Toast 文案"蒙扎国家赛车场 的"多空格** [V]——parseToast 模板 `$track 的` 拼接，track 值可能带尾空格；纯文案瑕疵，用户尚未点名修。
- ⚠️ **lint baseline 13 条失效** [V]——`/doctor` 逻辑建议下次重新生成（AndroidGradlePluginVersion 等已修复项还挂着）。
- ⚠️ 继承：双仓赛道中文名两份硬编码（契约源 PADDOCK_PLAN §5，`Shangai` 单 a）；继承 [?]：多指日志回传、16 赛道 12/16 未实机、门禁 champ==NULL 多语义、矩阵空格。

## 6. 下一步（有序）
1. **群内复测 bot 新文案**：发 `@bot 申请围场通行证#码` → 预期回复"校验成功…您是全服第 x 位车手！请返回模块直接点击登录。"（v1 完成定义最后遗留项）
2. **用户最终确认登录态恢复**：杀模块进程重进 → 用户信息连体卡应完整显示（用户名/车手 #ID/计时赛积分），本轮已装机但用户尚未明确反馈此项 OK。
3. （可选清理）Toast 文案空格、lint baseline 重新生成、Garage 测试对象、payload 原文日志转 debug。
4. 重构（用户此前定案"下个版本重构模块和服务端的一堆东西"）——范围待用户下次指定。

## 7. 留给用户的开放问题
- 群消息不@推送：提工单还是接受 @-only？影响注册 UX。
- 计时赛积分个人卡在无成绩时显示"暂无"，登录成功但 /v1/me 网络失败时也显示"暂无"——是否需要区分"加载失败"与"暂无成绩"？
- 大奖赛/娱乐匹配占位卡（Toast"开发中"）后续真做时：赛季积分/胜场数据从哪来（服务端目前只有计时赛成绩）？
- 继承：多指日志回传裁决、工具按钮重置入口、指示灯亮度调节等（未恶化不动）。
