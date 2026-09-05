# HANDOFF — 读全文再开始干活

生成时间: 2026-09-04T23:56:42+08:00 · Git HEAD: `13fa6f8`（模块仓；paddock 仓 `0ba46f2`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `13fa6f8`（2026-09-04）；paddock 仓 `main` @ `0ba46f2`（tag `v1.0.0`）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `13fa6f8`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源）+ paddock 仓 `HANDOFF.md` + paddock 仓 `CHANGELOG.md`「版本约定」

## 1. 当前目标
**v1.0.3 正式版发布 + 服务端 SemVer 收敛**：模块 v1.0.3（围场互联大版本）已发布上线；paddock 服务端从部署序号 vN 收敛到 SemVer 1.0.0 并重新部署。两者均完成并验证。

## 2. 已验证状态 — 工作实际停在哪
- [V] 模块 v1.0.3 发布全链路完成：commit `13fa6f8`（RELEASE_NOTES/build.gradle.kts 103300/module.prop/README）+ tag `v1.0.3` 已 push；CI run 33888396463 conclusion=success（build+sync-lsposed 双绿）
- [V] 源 Release 验证：`gh release view v1.0.3` → body=手工 Notes、asset `Ala.Mobile.Tool.v1.0.3.apk`、prerelease=false
- [V] 镜像仓验证：`gh release view 103300-1.0.3 --repo Xposed-Modules-Repo/tools.alamobile.mod` → body=手工 Notes、prerelease=false；镜像 README grep 到 1.0.3 徽章/8.0.6/围场路线图项
- [V] paddock 版本体系收敛：Cargo.toml version=1.0.0、`/v1/health` 返回 `{"status":"ok","version":"1.0.0"}`（env!("CARGO_PKG_VERSION") 编译期注入）、deploy compose 镜像行 `paddock-api:1.0.0`、CHANGELOG.md（Keep a Changelog，1.0.0≈线上v39）、LICENSE 补本体
- [V] paddock 重新部署上线：本地 build→gzip 5.9MB→scp→VPS load→compose 切 tag→up；curl 三接口实测 health v1.0.0 / 积分榜（Takotsubo 700）/ 8.0.6 摩纳哥赛道榜全部正常
- [V] paddock 仓提交推送：`62d0b94`（chore(release) v1.0.0）→ `0ba46f2`（README 重写）→ tag `v1.0.0` 已 push；工作区 clean
- [V] 模块仓 README 本会话更新（已随 13fa6f8 入库）：围场功能块+第3页配置表（底栏顺序=概览/配置/围场/设置，MainScreen.kt:205-208 核实）、架构图补 PaddockClient/Uploader/hide_pedals_hook/lap_hook、版本历史 1.0.3 条目、游戏版本全切 8.0.6
- [V] lint 门禁：全新 shell `./gradlew :app:lint` → EXIT=0（52 warnings 4 hints 均既有；baseline 13 条失效提示非阻塞，handoff 继承项）
- 工作区: 两仓均 clean，全部已推送

### 测试/build 输出（真实退出码）
```
模块仓: ./gradlew :app:lint → EXIT=0（"Lint found 52 warnings, 4 hints"）
paddock: cargo check（全新 shell）→ EXIT=0（2 既有 warning）
paddock 线上: curl /v1/health → {"status":"ok","version":"1.0.0"}；积分榜/赛道榜 JSON 正常
CI: gh run watch 33888396463 → conclusion=success
```

## 3. 决策与理由
- **服务端版本=SemVer，部署序号vN 废弃**（用户定案）[V]：v1~v39 是人肉部署批次混用三义（部署批次/方案序号/镜像tag），收敛为 Cargo.toml 单一事实源 + git tag + 镜像 tag 三处同源；映射起点 **1.0.0 ≈ 线上 v39**。历史文档 vNN 记法保留不改（指方案定案），CHANGELOG「版本约定」有说明。
- **`/v1/health` 从 "ok" 改 JSON** [V]：加 version 字段让线上可核版本；改前 grep 确认模块端零依赖此端点，零风险。
- **Release Notes 用户定稿 5 处修改** [V]：摘要加欢迎语；Breaking 改"请务必将游戏更新至 8.0.6！"（删"或继续使用 v1.0.2"）；删"游戏版本即独立赛季"尾注；"小米/红米 MIUI 设备"；**整节删除 Known Issues**。用户理由：换挡早已禁用无可用入口，"没有的东西不要放上去"——Release Notes 只写用户可见的东西。
- **Release Notes 剔除两条修复** [V]（我判定+用户默许）：ABS 浮点污染回归、围场上传链路修复——bug 只存在于 1.0.2..1.0.3 之间的提交中，坏版本从未到达用户。
- **覆盖链取最新**（用户要求）[V]：注册流v2（verify已删）/排行榜行级items/制动压力v6饱和重映射/服务器设置=OverlaySpinner/ABS档位=正常工作——五条链全部以 HEAD 状态为准。
- **paddock README 重写**（用户选中等完整版）[V]：原"定案速记"~3600字开发日志性质 → 压缩为6条运维速记，全文外移 PADDOCK_PLAN；补 Quick Start/环境变量表/发版流程。
- **路线图禁删暂缓条目**（用户纠正，已入记忆 roadmap-no-delete-paused-items）[V]："手动换挡"三处恢复并补状态上下文；整理 TODO 类内容只做事实更新，想删先问。
- **围场页位置表述**（用户纠正两次）[V]：模块 App 底栏第 3 个 tab（概览/配置/围场/设置），不是"游戏内第 4 页"——页序以 MainScreen.kt 枚举为准，不抄文档旧表述。
- 继承：积分公式 v39 / 版本独立赛季 / 字段核对清单=OFF_* 全集 / RVA 单一事实源=OffsetTable / 规则防覆盖 / 排行榜行级 items / token daemon / order==2 挂圈 / 拦截器零浮点。

## 4. 失败的尝试 — 不要再试
- **Release Notes 凭 commit message 直写** [V]——步骤3B代码验证发现多处需对拍 HEAD（如制动压力键名实为 brake_scale/0xF0 重映射）。每个 feat/fix 必须 grep/读文件确认真实存在。
- **文档旧表述照抄不核实** [X→V]：「围场第4页」从上会话 CLAUDE.md 一路传播到 agent 报告再到我笔下，实际底栏第 3 页（MainScreen.kt:205-208）。文档里的位置/序号类表述必须对代码核实。
- **scp 用错用户** [X]：`root@8.134.50.222` Permission denied——正确是 `takotsubo@` + `~/.ssh_paddock/id_rsa` + `-p 4142 -o IdentitiesOnly=yes`（记忆 paddock-deploy-chain-local-build 有完整命令，先查记忆再连）。
- **VPS 上 compose 镜像行与仓内不同步** [V]：仓内死值 v1、线上实际 v39——部署时 sed 的目标值要以 VPS `grep` 实际输出为准，不能假设仓内状态。
- 继承（前向有效，详见 .handoffs/20260904233000-handoff.md §4）：IL2CPP dump 用 Windows dotnet / 后台任务 cwd 漂移 / mdns 端口漂移 / miuix 无 onSurfaceVariant / serde Option 不兜空串 / askama 模板禁调函数 / 图标只偏色相会发棕 / adb push 必须完整文件路径 / 规则覆盖=读路径回落预设非部署所致 / fetchMe 漏 token / lap_hook 全套 / IL2CPP 扫描三坑。

## 5. 已知坑
- ⚠️ **8.0.4 官版用户会收到 unsupported** [V]——门禁已切 8.0.6 单版本（v1.0.3 Breaking Changes 已告知用户升游戏或留 v1.0.2）。
- ⚠️ **lint baseline 13 条失效** [?]（继承）——lint 报 baseline not found 提示，下次重新生成。
- ⚠️ **paddock 版本三处同步无校验** [?]——Cargo.toml/compose镜像行/git tag 靠人工同步，防 drift 可给 deploy.sh 加一致性校验（deploy.sh 本身也未实施，见 §6）。
- ⚠️ **镜像仓 README 同步以内容指纹验证** [V]——sync job 只在 build 成功后跑一次，同步失败旧文件仍在，泛泛检查查不出，要 grep 本次变更特征。
- ⚠️ 继承：排行榜无实时刷新（用户拍板暂不做）/ 管理端网页验证未做 / 群内 bot 复测未做（重置口令严格匹配/带码注册车手号）/ 双仓赛道中文名两份硬编码（Shangai 单 a）/ Garage 206 测试对象。

## 6. 下一步（有序）
1. **用户复验**（继承）：重开模块 App——围场主页积分、排行榜 8.0.6 筛选（摩纳哥 1:08.964）、1.0.3 APK 装机跑圈；管理端成绩页四筛选+跳页。
2. **群内 bot 复测**（继承）：重置口令严格匹配、裸关键词静默、带码注册车手号=最小空缺。
3. （可选）`deploy/deploy.sh` 固化 paddock 部署链（用户已认可提议）+ 版本三处一致性校验。
4. （可选继承）lint baseline 重生成 / 重构范围清单（用户提过"下个版本重构一堆东西"，待指定）。

## 7. 留给用户的开放问题
- 重构范围清单待定（模块+服务端）。
- 播报主动消息额度（约 4 条/月/群）：接受 / 申请认证？
- 官版 8.0.4 用户兼容策略：单版本门禁已实装，未来是否做按版本分 OffsetTable？
