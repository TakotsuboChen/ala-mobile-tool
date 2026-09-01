# HANDOFF — 读全文再开始干活

生成时间: 2026-09-01T13:32:18+08:00 · Git HEAD: `629f98c`（模块仓）/ paddock 仓 `f646318`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `629f98c`（2026-09-01）；paddock 仓 `main` @ `f646318`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `629f98c`；paddock 仓同理对 `f646318`。不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源，§4 已更新为注册流 v2）

## 1. 当前目标
**围场第 1 期功能全部落地并上线 v11**。本会话完成：围场 UI 全重做 + 注册流 v2（bot 校验即建号）+ 头像完整链路 + 管理端四轮增强 + 修复三层上传链路 bug。端到端实测：登录→token daemon 恢复→刷圈→Toast→榜单（Monza 1:15.705 上榜，车手 #3）全通。

完成定义剩余项：QQ 群注册全流程复测（bot 新文案"您是全服第 x 位车手"群内实测）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **paddock v11 已上线**（compose app/pg/garage；migration 0004 自动跑）。`/v1/health` 200；赛道榜返回 `avatar_url`；`register-request` 带 password 实测发码 + 同名 409。
- [V] **端到端上传实测通**：游戏日志 `remote token read: len=48` → `auth restored` → `queue drained` → `Toast: 您已刷新蒙扎国家赛车场 的全服历史最佳成绩！`；榜单 API 确认 Takotsubo（reg_seq=3）Monza 1:15.705 100分。
- [V] **头像上传链路实测通**：真 Garage SigV4 上传 200 → 下载字节 `cmp` 一致。
- [V] **管理端新功能上线**（浏览器待用户复测）：用户改名/删除（prompt 输用户名确认，删后重算 records）、成绩圈时编辑（毫秒，0<ms≤3600000）、时间显示北京时间。
- [V] **模块 release APK 已装机**（12:47 无线 adb `192.168.50.142`，含榜单重做+头像+全部修复）。用户尚未反馈新 UI 视觉验收。
- [V] 构建：模块 `assembleDebug+lint` EXIT=0、`assembleRelease` EXIT=0；paddock `cargo build --release` EXIT=0（仅剩继承的 server_best warning）。
- [V] 工作区：两仓全部提交推送干净。

### 测试/build 输出（真实退出码）
```
./gradlew :app:assembleDebug :app:lint → BUILD SUCCESSFUL, EXIT=0
./gradlew :app:assembleRelease → BUILD SUCCESSFUL, EXIT=0
cargo build --release → Finished EXIT=0（paddock）
端到端：登录→daemon token→传圈→Toast→榜单 全通 [V]；群内 bot 新文案复测未做 [?]
```

## 3. 决策与理由
- **注册流 v2：bot 校验即建号**（用户定案）——申请时一并设密+发 reg_seq 存 pending_regs，bot 绑 openid 后直接建号并回复"@用户名 校验成功，欢迎您加入 CAMDA，您是全服第 x 位车手！请返回模块直接点击登录。"；`register-verify` 端点删除。否决"申请只存名、bot 回复不放序号"——用户要求回复带车手序号，只能申请时发号（未完成注册的号作废，顺序不乱允许空缺）。
- **登录 token 走 Remote Preferences daemon**（key `paddock_token_v1`）——两进程 externalFilesDir 按包名隔离互不可见，daemon 是唯一已验证跨进程通道；`saveAuth` 双写/`loadAuth` daemon 恢复/`clearAuth` 双清/`App.onServiceBind` 补 flush。
- **头像 SigV4 手写**（~80 行）——不用 aws-sdk（opt-level="z" 体积敏感）；只服务 Put/Get 两形态。PutObject 200 + GetObject 字节级 cmp 一致 [V]。
- **车手 ID 申请时发放**——bot 回复需要序号；代价是 ID 有空缺（用户接受）。
- **管理端删除用户**：prompt 输入用户名确认；删用户事务先收集 (gp,version) 维度再 CASCADE 删 + 逐维度重算 records（records 外键 SET NULL 会悬空，必须重算）。
- 继承：全放行防伪、1Hz 轮询单槽、order==2 挂圈、拦截器零浮点、miuix preference 全盘照搬。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/`。

### 本轮新增（三层上传链路 + UI 集成）
- [X] **两进程共用代码引用 `AlaMobileModule.logX`** → 模块进程 NoClassDefFoundError（XposedModule），被外层 catch 拼成"网络错误"伪装。共用代码只准用 `Logger`；libxposed-api 是 compileOnly，只有游戏进程被 LSPosed 注入。诊断指纹：报错文案与某 catch 模板逐字吻合 + 报错类是 compileOnly 依赖。
- [X] **`PaddockClient` 单例只在游戏进程 init** → 模块进程 `saveAuth` 写文件必抛 "not initialized" 且被吞，token 从未持久化。object 单例掩盖了"每进程都要各自初始化"。
- [X] **登录态靠 externalFilesDir 文件跨进程** → 按包名隔离互不可见（Android 11+ scoped storage），"两进程都可见"的原注释是错的。必须走 daemon。
- [X] **`CropImageActivity` 用模块主主题** → AppCompatActivity 强制 AppCompat 系主题，IllegalStateException 闪退。manifest 单独 override `Theme.Cropper`。
- [X] **`Theme.Cropper` 用 NoActionBar** → 裁剪确认按钮走 options menu（showAsAction=always），NoActionBar 下 menu 无处渲染=白屏无按钮。必须带 ActionBar（反编译字节码确认，非文档）。
- [X] **改代码后只跑 assembleDebug 就装旧 release APK** → "老样子"假象。装机前必须当场 `assembleRelease`。
- [X] **sqlx 泛型闭包 `FnOnce(&mut Transaction)->Future` 抽象** → HRTB lifetime 推导失败。放弃抽象直写两份事务代码（recalc_delete / recalc_edit 各自调 recalc_dims）。
- [X] **axum 0.8 写 `:user_id` 路由** → 启动 panic "Path segments must not start with `:`"。0.8 用 `{user_id}`。
- [X] **zsh `$UID` 当临时变量** → bad math expression。换名（如 `RID`）。
- [X] **VPS SSH 端口 22/root** → Connection closed。实际 `takotsubo@8.134.50.222 -p 4142`，docker 要 sudo。

### 继承死路（全部 [X] 前向有效，详见 .handoffs/20260901003000-handoff.md §4）
- lap_hook 三版演化 + 指示灯信号链 + FPSIMD：圈完成等 order 2→0 回绕 / sectorOrder 1-based / HandleSectorsTimes 当过线单事件 / gate v1 "champ==NULL→放行" / trackToRace 当赛道名 / bestLapTimeInfo 当历史最佳 / raceModes 当判据 / 切片提交混 git rename / 拦截器浮点操作 / ABS v1-v5 / 指示灯 v1-v5 / proxy_shift 日志洪水 / IL2CPP dlopen / 后台线程调 Unity API / records 复合主键 NULL 维度 / Toast 双维度共用出口 / askama 单引号 / miuix SuperArrow / sqlx::migrate! 相对路径+query_as! 宏离线 / axum nest("/admin") 带斜杠 404 / Write 工具输出污染。
- QQ bot webhook：bearer_auth 调 QQ API（要 `QQBot {token}`）/ 被动回复用事件外层 id（要用 `d.id`）/ member_openid 声明在 d 顶层（在 `d.author` 嵌套）/ 凭据未配置时信平台报错文案 / VPS 上 cargo build（OOM，本地 build+save/scp）/ WSL 密钥直用 /mnt/d / 诊断盲区靠猜（先加 payload 原文日志）。

## 5. 已知坑
- ⚠️ **群全量消息（不@）平台至今不推送** [V]——配置全开、@ 消息正常，唯独不@静默丢弃。处理代码已就位。堵住"注册不依赖 @"需求。需提工单或接受 @-only。
- ⚠️ **bot 新回复文案（带车手序号）群内未复测** [?]——v2 建号逻辑代码验证过，但真实群内"发申请码→@回复带序号"全流程待跑。
- ⚠️ **QQ 被动回复无真 @mention API** [V]——回复的 `@用户名` 只是文本前缀，群里显示为普通文字。
- ⚠️ **Garage 22 字节测试对象残留** [V]——bucket `avatars` 里 `avatars/98f7fad3…`（已删用户的测试头像）无 shell/aws cli 清不掉；无害（按 user_id 命名，不会复用）。
- ⚠️ **Toast 文案"蒙扎国家赛车场 的"多空格** [V]——parseToast 模板 `$track 的` 拼接，track 值可能带尾空格；纯文案瑕疵。
- ⚠️ **lint baseline 13 条失效** [V]——`/doctor` 逻辑建议下次重新生成（AndroidGradlePluginVersion 等已修复项还挂着）。
- ⚠️ 继承：双仓赛道中文名两份硬编码（契约源 PADDOCK_PLAN §5，`Shangai` 单 a）；继承 [?]：多指日志回传、16 赛道 12/16 未实机、门禁 champ==NULL 多语义、矩阵空格。

## 6. 下一步（有序）
1. **用户视觉验收新 UI**：围场页（登录/注册并排+注册弹窗）、排行榜新行布局（头像/连排/全称）、头像上传裁剪页——装机完成但用户尚未确认视觉。
2. **群内复测 bot 新文案**：发 `@bot 申请围场通行证#码` → 预期回复"校验成功…您是全服第 x 位车手！请返回模块直接点击登录。"
3. **管理端浏览器复测**：用户改名/删除、成绩编辑、北京时间显示。
4. （可选清理）Toast 文案空格、lint baseline 重新生成、Garage 测试对象、删 hidePedalsApply 死代码 + abs_diag_log rfHits 行、payload 原文日志转 debug。
5. 重构（用户此前定案"下个版本重构模块和服务端的一堆东西"）——范围待用户下次指定。

## 7. 留给用户的开放问题
- 群消息不@推送：提工单还是接受 @-only？影响注册 UX。
- 注册流程若还有体验不符预期的地方（v2 弹窗文案/按钮布局），用户验收后反馈。
- 头像上传入口目前只在首登自动跳转+（规划中）个人卡点击——是否需要在围场页加显式入口？
- 继承：多指日志回传裁决、工具按钮重置入口、指示灯亮度调节等（未恶化不动）。
