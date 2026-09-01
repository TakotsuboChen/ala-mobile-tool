# HANDOFF — 读全文再开始干活

生成时间: 2026-09-01T00:35:00+08:00 · Git HEAD: `9666f67`（模块仓）/ paddock 仓 `5b2d85f`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `9666f67`（2026-09-01）；paddock 仓 `main` @ `5b2d85f`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `9666f67`；paddock 仓同理对 `5b2d85f`。不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源）+ `paddock-api/src/qq_bot.rs` 头注（bot 鉴权事实速记已沉淀进 paddock README）

## 1. 当前目标
**围场私服第 1 期收尾与实机验证**。本会话完成了 S4 bot 全部 + 管理端完全体 + VPS 上线 + 实机联调到"群注册校验回复成功"；遗留 3 个实机 bug（见 §2/§6）。用户定案：**下个版本重构模块和服务端的一堆东西**——先重构，再重做端到端。

完成定义：实机计时赛刷圈 → 成绩上榜 → 赛道中文名 Toast → QQ 群注册全流程无人干预。

## 2. 已验证状态 — 工作实际停在哪
- [V] **paddock v9 已上线** `https://paddock.takotsubo.cloud`（compose app/pg/garage 三容器；反代 = 1Panel openresty 终结 443→127.0.0.1:8080；迁移 0003 含车手 ID 序列/reset_codes/configs 表）。`/v1/health` 200。
- [V] **bot 群链路实测通**：群 @消息验签进站 → 指令匹配 → `member_openid` 识别 → **被动回复成功送达群里**（"无法识别你的群身份"是修复前 v8 的反馈，v9 已修嵌套读取）；私聊被动回复实测通（"消息已发送"+ROBOT1.0 id）。
- [V] **实机暴露 3 个未解决 bug**（用户实测反馈）：
  1. 围场页"我已校验"完成注册时**报错**，但服务端实际已建号成功（注册 OK，响应处理或流程 UI 有 bug）；
  2. 用户反馈注册流程本身与其预期不一致（UI 流程要重新设计，见 §7）；
  3. 已注册用户在围场页**登录报错** `Failed resolution of: Lio/github/libxposed/api/XposedModule;`——游戏进程内 HttpURLConnection 链路上某处触碰了 Xposed 类（疑似 PaddockUploader/PaddockClient 在游戏进程的类加载问题），未复现定位。
- [V] 群全量消息（GROUP_MESSAGE_CREATE，不@）平台至今未推送（@ 消息正常）——平台侧问题，配置已全开；用户要求注册**不依赖 @**，此堵点未解。
- [V] 构建：模块 `assembleDebug+lint` BUILD SUCCESSFUL EXIT=0；paddock `cargo build --release` EXIT=0。
- [V] 工作区：两仓干净（本 handoff 提交后）；模块仓 HEAD `9666f67`，paddock `5b2d85f`，均与 origin 同步。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleDebug :app:lint → BUILD SUCCESSFUL in 13s, EXIT=0
cargo build --release → Finished EXIT=0（paddock 仓）
实机端到端：注册"半通"（服务端建号成功/客户端报错）、登录失败（XposedModule 类解析）、群校验回复 v9 修复后待复测
```

## 3. 决策与理由
- **管理端完全体优先于实机测试**（用户定案）——S4 bot + 管理端设置页/改密码/改用户名/人工重置 + 车手 ID 全部做完才进实机，不再"半成品先测"。
- **车手 ID = reg_seq 注册顺序从 1 起**（用户 2026-08-31 定案）：`user_reg_seq` Postgres 序列发放 + UNIQUE 索引；verify/login 响应、两个榜单、管理端用户页均返回/展示。契约已更新 PADDOCK_PLAN §3/§4。
- **bot 只在单一自拉群工作，不配群白名单**（用户定案）——被动回复直接发消息来源群（`d.group_openid`），管理端已去掉 bot_group_openid 配置项。
- **管理端用户名必须可改**（用户反馈）——设置页新增改用户名（验当前密码，改完全会话作废）。
- bot 鉴权事实（逐篇核对官方 11 篇文档）：`Authorization: QQBot {token}` 非 Bearer；被动回复 msg_id 用 `d.id`；群/单聊发送者身份在 `d.author.member_openid`/`user_openid`（嵌套）；群被动窗 5min/5 次、单聊 60min/4 次；token 获取 `POST bots.qq.com/app/getAppAccessToken`。已沉淀进 paddock README + 代码头注。
- 镜像分发：本地 build → `save|zstd|scp|load`（v2..v9 迭代 9 次），VPS 1.8G 内存不现场编译。
- 继承：全放行防伪、1Hz 轮询单槽、order==2 挂圈、拦截器零浮点。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/`。

### 本轮新增（QQ bot webhook 联调）
- [X] **`bearer_auth()` 调 QQ API** → 401 `11241 Authorization参数格式错误`。QQ 鉴权头是自定义 scheme `QQBot {token}`，不是 RFC Bearer。接第三方平台的鉴权头必须按其文档写，不用 `bearer_auth()` 便捷方法。
- [X] **被动回复用事件外层 id（`事件类型:hex` 形态）当 msg_id** → 400 `40034024 msg_id无效或越权`。被动回复必须用 `d.id`（ROBOT1.0_ 形态）。
- [X] **GroupMessage 把 member_openid 声明在 d 顶层** → 永远取空 → 回复"无法识别群身份"。实际在 `d.author.member_openid` 嵌套（C2C 是 `d.author.user_openid`）。**教训：写完 payload 解析结构必须用真实 payload 原文逐字段核对，不能凭文档表格直觉**。
- [X] **凭据未配置时假设平台报错信息可信** → 平台"签名校验不通过"是笼统文案，真因是服务端当时无凭据无法应答 op=13。排障先看服务端日志分清"没应答"vs"验签失败"。
- [X] **在 VPS 上 cargo build** → 1.8G 内存 OOM 风险。本地构建镜像 → save/zstd/scp/load，5.7MB 传输。
- [X] **WSL 密钥直用 /mnt/d（0777）** → SSH 拒用。拷进 ext4 chmod 600。
- [X] **诊断盲区靠猜** → "群消息权限没放量"等猜测被 payload 全量落盘（v7 `payload 原文` 日志）一击终结。webhook 排障先加原文日志再讨论。
- [?] **服务端归一化 `#` 前空格**（v8 仍保留）——起因是用户手机 QA 模块的 Pangu 化插空格，非官方行为；保留无害（防全角＃等变体），但不要据此声称"官方客户端插空格"。

### 继承死路（lap_hook 三版演化 + 指示灯信号链 + FPSIMD，全部实机实证，详见 .handoffs/20260901003000-handoff.md §4）
- [X] 圈完成等 order 2→0 回绕 / sectorOrder 1-based / HandleSectorsTimes 当过线单事件 / gate v1 "champ==NULL→放行" / trackToRace 当赛道名 / bestLapTimeInfo 当历史最佳 / raceModes 当判据 / 切片提交混 git rename / 拦截器浮点操作 / ABS v1-v5 / 指示灯 v1-v5 / proxy_shift 日志洪水 / IL2CPP dlopen / 后台线程调 Unity API / records 复合主键 NULL 维度 / Toast 双维度共用出口 / askama 单引号 / miuix SuperArrow / sqlx::migrate! 相对路径+query_as! 宏离线 / axum nest("/admin") 带斜杠 404 / Write 工具输出污染——全部 [X] 前向有效，勿重试。

## 5. 已知坑
- ⚠️ **群全量消息（GROUP_MESSAGE_CREATE，不@）平台不推送** [V]——开放平台配置全开（Webhook + 全部接收 + IP 白名单 8.134.50.222）、@ 消息和 GROUP_MEMBER_ADD 都正常推送，唯独不@的群消息静默丢弃。处理代码已就位（handle_group_message 两事件同路径），平台哪天推了立即生效。堵住"注册不依赖 @"的需求，用户明确要求必须不 @。
- ⚠️ **v9 群校验回复链路修复后未复测** [?]——member_openid 嵌套修复（v9）后用户尚未再发群申请码验证；预期回复"校验成功"。
- ⚠️ **实机登录 `XposedModule` 类解析崩溃** [?]——围场页登录报 `Failed resolution of: Lio/github/libxposed/api/XposedModule;`。疑似游戏进程里 Paddock 链路（PaddockUploader/PaddockClient 或 ModConfig）间接触发了 Xposed 类加载。唯一线索：报错文案格式与 PaddockClient 的 `网络错误: ${e.message}` 拼接一致，即发生在 HttpURLConnection 调用层之下。
- ⚠️ **注册"已校验"步骤 UI 报错但服务端建号成功** [?]——register-verify 的响应（201 {token,reg_seq...}）或客户端对 404/409 的展示有问题；服务端数据正确意味着请求本身到了，需看 PaddockClient.registerVerify 的具体返回分支。
- ⚠️ **QQ 平台推送内容不可信到字节级**：事件外层 id、author 结构、content 前导空格都可能与文档示例有差异，解析必须以落盘 payload 为准（v7 起有 `payload 原文` 日志）。
- ⚠️ 继承：双仓赛道中文名两份硬编码（契约源 PADDOCK_PLAN §5，`Shangai` 单 a）；继承 [?]：多指日志回传、Bug B 未复现、16 赛道 12/16 未实机、门禁 champ==NULL 多语义、矩阵空格。

## 6. 下一步（有序）
1. **复测 v9 群校验回复**：群里再发 `@bot 申请围场通行证#码`——预期"校验成功"回复；通过后围场页走完注册（注意 §5 的"已校验报错"bug，服务端已建号时 verify 会 404——重复注册同码）。
2. **修"我已校验"报错**：先看服务端日志 verify 响应码，再对照 PaddockClient.registerVerify 分支；大概率是 201 body 解析或"码已被用"路径的文案映射缺失。
3. **修登录 XposedModule 类解析崩溃**：抓 release 实机日志（`ala_tool.log` + logcat），定位 Xposed 类从哪条链路进的游戏进程 classloader；候选嫌疑：PaddockUploader 15s 延迟路径的异常处理引用了 Xposed 类型。
4. **重构**（用户定案"下个版本重构模块和服务端的一堆东西"）：范围由用户下次指定；已注册用户在库（reg_seq 1 号已建号成功）。
5. （可选清理）删 `hidePedalsApply()` 死代码 + abs_diag_log rfHits/hitAge 行；payload 原文日志转 debug 或限速。

## 7. 留给用户的开放问题
- 注册流程形态：用户说"我之前说的注册流程本来也不是这样的"——重构时需用户重述预期流程（现实现=申请码→群发码→我来校验→回填码+密码）。
- 群消息不@推送：配置全开平台仍不推，需要提工单还是接受 @-only？影响注册 UX（@ 要翻列表）。
- QQ 模块 Pangu 化插空格（用户自己处理）vs 服务端归一化已并存，用户模块修完后两者兼容。
- 头像上传+裁剪（依赖 Garage 初始化，未开始）。
- 继承：多指日志回传裁决、工具按钮重置入口、指示灯亮度调节等（未恶化不动）。