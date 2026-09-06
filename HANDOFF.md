# HANDOFF — 读全文再开始干活

生成时间: 2026-09-06T15:30:00+08:00 · Git HEAD: `74f4f8a`（模块仓；paddock 仓 `1dbe7a2`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `74f4f8a`（2026-09-06）；paddock 仓 `main` @ `1dbe7a2`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `74f4f8a`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源头）+ 本文件 §4 @ 语法五轮实测矩阵

## 1. 当前目标
**赛道正名/国旗 + QQ bot @车手 + 管理端输入框**——已全部完成、上线部署、群内实测通过（`/handoff 可以了` = 用户确认 @ 效果达标）。无在途排查。

## 2. 已验证状态 — 工作实际停在哪
- [V] **迪拜赛车场正名**：原"亚斯码头赛道"是误命名（游戏场景 Dubai/Emirates GP，TRACK_IDENTIFICATION.md 本来就对）。改了模块 `TRACK_NAMES` 末项 + 服务端 `track_display_name` + PADDOCK_PLAN §5 三处
- [V] **服务端赛道名国旗**：16 项全加国旗前缀（模块侧本就有），单一源头函数 10 个调用点全链路生效；**qq_bot 播报专用紧凑版** `track_display_name_compact` 剥旗后空格（模块 UI 保留空格——用户明确要求分层）
- [V] **{{at_me}} @车手**：五轮实测矩阵定案（见 §4），最终方案 = `<qqbot-at-user id="member_openid" />` + markdown 通道，群内实测真实 @ 成功（用户原话"可以了"）。openid 缺失回落 `@「用户名」`，双缺失空串
- [V] **管理端添加成绩三输入框去预填 0**：`lapInputs()` 添加路径渲染空值+placeholder，编辑路径不受影响
- [V] **paddock 已部署上线**：镜像 `paddock-api:1.0.1`（版本号红线：仓库现版本原样构建，未升版）；health `{"status":"ok","version":"1.0.1"}`；公网验证 `gp15 = 🇦🇪 迪拜赛车场`；部署链四轮（本地 build→save|gzip→scp 4142→load→up）
- [V] **模块 APK 已装机**：无线 adb（mdns 重发现 42197 端口）安装 release 成功，装机 `versionName=1.0.4 Alpha 1 / versionCode=104100`（未升版）
- [V] `./gradlew :app:lint` → EXIT=0；`cargo check` → EXIT=0
- 工作区: 两仓均 clean 全部已推送。模块仓 `74f4f8a`（正名）；paddock 仓 `f17f9ee`(正名+国旗)+`bcd875b`(at_me+播报紧凑+芯片)+`3bf1f87`(输入框)+`fd45772`(README)+`1dbe7a2`(Cargo.lock 补漏)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → EXIT=0
cargo check → EXIT=0（paddock-api）
curl https://paddock.takotsubo.cloud/v1/leaderboard/track/15 → track_name='🇦🇪 迪拜赛车场'
群内实测播报 → @ 真实提及成功（用户确认）
```

## 3. 决策与理由
- **国旗空格分层** [V]：模块 UI 与契约函数带空格（`🇦🇪 迪拜赛车场`），仅 qq_bot 播报剥空格（`track_display_name_compact`）。用户原话"模块里面要保留国旗和文字之间的空格，只有服务端播报不保留"。紧凑函数在 qq_bot 本地实现而非改契约函数加参数——展示层变体留在唯一关心它的消费点
- **@ 语法=新标签+markdown 通道** [V]：text-chain 规范的 `<qqbot-at-user id=".."/>` 只在 msg_type=2 被解析；send_message 检测内容含 `<qqbot-at-user` 即整体切 markdown（与 content 字段互斥）。无标签消息保持 msg_type=0 零行为变化
- **@ 标签带 openid 回落链** [V]：`at_user(openid, username)`——openid 优先（真实提及），缺失退化纯文本 @「用户名」，双缺失空串。四处查询均带 `member_openid` 列
- **失败文案清 {{at_me}} 残留** [V]：`fail_type_reply` replace 空串（同 {{qq_name}} 既有模式）
- 继承：CrashCatcher 只装模块进程 / 积分公式 v40 / token 三级回落 / order==2 挂圈 / 版本号红线（全局 CLAUDE.md）

## 4. 失败的尝试 — 不要再试
- **`<@openid>` 旧格式发 @** [X]——markdown/纯文本两通道均被平台静默转义为裸 openid 文本（群内实测）。旧频道格式已弃用，勿再用
- **`<qqbot-at-user>` 标签走纯文本通道（msg_type=0）** [X]——文本通道不解析 XML 标签，原样打印（群内实测）
- **compact 剥空格只消费一个旗码点** [X]——国旗=两个区域指示符（🇦🇪=U+1F1E6+U+1F1EA），逐码点处理吃掉半旗（`🇪 迪拜赛车场` 实证）；二次修复时又忘了把旗码点拼回输出（裸文字实证）。最终版=成对判定两码点都在 U+1F1E6..1F1FF 且 `format!("{a}{b}{rest}")` 拼回。⚠️ 任何处理 flag 序列的代码都要成对码点判定
- **宣称"QQ 官方群 bot 完全不支持 @"** [X]——是错误结论（当时据 paotuan.io 骰机器人文档 + 两轮失败推断），`<qqbot-at-user>`+markdown 实测可行。教训：第三方机器人文档的"平台限制"可能是该 bot 自身的通道选择问题，不能直接外推为平台能力边界
- 继承（前向有效，见 `.handoffs/20260906130000-handoff.md` §4）：靠自导日志定位模块 App 闪退 [X] / 把用户口头崩溃入口当排查目标 [V] / NPatch 靠 Remote Preferences 传 token [X] / ConfigProvider 读 filesDir [X] / SQL 窗口函数 CTE 内先 WHERE 再 rank [X] / 未经同意改版本号 [V] / Release Notes 凭 commit message 直写 / scp ssh 端口参数（ssh=-p 小写，scp=-P 大写，本次又踩）/ VPS 禁 cargo build / IL2CPP dump 用 Windows dotnet / mdns 端口漂移 / serde Option 不兜空串 / askama 禁调函数 / lap_hook 全套 / IL2CPP 扫描三坑

## 5. 已知坑
- ⚠️ **avatarCache 无界** [?]（继承，未修）——`LeaderboardScreen.kt:289` 无 LRU 上限，榜单增长下去迟早 OOM；即使不是闪退元凶也建议加并发/容量上限
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——CrashCatcher 已在 1.0.4 Alpha 1 分发版里，等用户复现闪退导出日志定案；若 CrashCatcher 段为空→坐实 MIUI 系统杀
- ⚠️ **LogExporter java/native 段可各自回落不同时期缓存** [?]（继承，未修）
- ⚠️ **该设备 ConfigProvider `Unknown authority`** [?]（继承，未修）——影响 token 第三级回落
- ⚠️ **markdown 通道的主动播报若遇 304036 无权限会整条失败** [?]——当前群实测通过说明权限够，但换 bot/权限变更时留意；降级方案=纯文本 @「用户名」（git 历史 bcd875b 可考）
- ⚠️ **qqbot-at-user 标签是文档级而非实测级支持** [?]——成功实测仅此一个群一个 bot；跨群/跨 bot 行为未验证
- ⚠️ 继承：NPatch 管理器 binder 时序 / lint baseline 13 条失效 / paddock 版本三处同步无校验（deploy.sh 固化未做）/ 排行榜无实时刷新 / 管理端网页验证未做 / 双仓赛道中文名两份硬编码（本次已同步，未来改仍需两边同改）/ Garage 206 测试对象

## 6. 下一步（有序）
1. v1.0.4 正式发布（版本号/tag/Release Notes）**待用户定版**——遵守版本号红线；CrashCatcher 已随 Alpha 1 分发
2. 等用户复现模块 App 闪退后导出日志（CrashCatcher 第一段即堆栈），据此定案修复
3. （可选）avatarCache 加 LRU/并发上限防御性加固
4. （可选继承）LogExporter 缓存回落修复 / deploy.sh 固化 / lint baseline 重生成

## 7. 留给用户的开放问题
- v1.0.4 正式版版本号与发布时机
- 闪退用户日志何时能拿到
- @「用户名」回落样式是否需要调整（当前 `@「名字」`）
