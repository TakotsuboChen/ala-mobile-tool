# HANDOFF — 读全文再开始干活

生成时间: 2026-09-04T22:08:08+08:00 · Git HEAD: `9fe3865`（模块仓；paddock 仓 `a68a4a3`）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块仓 `main` @ `9fe3865`（2026-09-04）；paddock 仓 `main` @ `a68a4a3`
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `9fe3865`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源，本会话已更新至 v4/积分v39）+ paddock 仓 `HANDOFF.md`

## 1. 当前目标
**Ala Mobile 8.0.6 (200150) 三端全面适配**：模块（OffsetTable/VersionGate/闪退修复）+ 共存版（品牌定制 Ala Mobile Pro / #FF8000 橙图标）+ 围场服务端（版本适配/筛选改造/积分 v39/规则防覆盖）。全部已上线/装机，剩收尾验证。

## 2. 已验证状态 — 工作实际停在哪
- [V] 模块仓两切片已推送：`b41ee5e`（12 文件代码）→ `9fe3865`（CLAUDE.md/skill/PADDOCK_PLAN）；lint EXIT=0（全新 shell 重跑 `./gradlew :app:lint` → BUILD SUCCESSFUL）
- [V] paddock 仓两切片已推送：`a9ba14a`（7 文件）→ `a68a4a3`（README）；线上 **v39** 运行（health=ok，`docker logs` → listening on 0.0.0.0:8080）
- [V] 服务端积分三维度线上验证：总榜 500（8.0.4 四赛道 400 + 8.0.6 摩纳哥 100）/8.0.4 版本榜 400/8.0.6 版本榜 100（curl 三接口实测）
- [V] 闪退修复装机实测：8.0.6 共存版 + 模块跑圈正常（21:16 产生新成绩 Toast「全服版本最佳」）；共存版裸包已推手机 Download（md5 `b81ca9d0...` 对照一致）
- [V] 规则防覆盖 v37 部署后核对：库内用户定制文案四条逐字未动
- [?] 模块端排行榜 UI 复验未做：VERSION_CODES 哨兵错位修复版已装机，用户需重开 App 确认（8.0.6 版本筛选应显示摩纳哥 1:08.964；围场主页积分应 500）
- [?] `mdns` 端口 38039 连接在交接时可能已失效（手机休眠后无线调试常关）

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL EXIT=0
./gradlew :app:assembleRelease → BUILD SUCCESSFUL；adb install -r Success（×3 轮）
paddock: cargo check EXIT=0（2 既有 warning）；docker build v36~v39 全 DONE；jsdom verify-laps-v36.mjs 21/21 OK EXIT=0
线上：/v1/health=ok；积分三接口 curl 数值正确
```

## 3. 决策与理由
- **积分公式 v39**（用户定案）[V]：`round((N−rank)×100/N)`——第一 100、每名次递减 100/N、虚位第 N+1 名 0、N=1 给 100（2 人=100/50）。否决旧式 `1+(N−rank)×99/(N−1)`（递减不均，用户否）。
- **总榜=版本独立赛季**（用户定案）[V]：每 (版本,赛道) 独立排名计分后按用户累加，两版本全赛道第一=3200。否决跨版本取最快（旧实现，用户纠正理解）。
- **字段核对清单=native 全部 OFF_* 常量而非 offsets_sheet.csv** [V]——8.0.6 闪退根因：odometerHandler 插 centralMessagesContainer(0x30) 其后全 +8，5 个硬编码字段漏核 → 垃圾当指针 SIGSEGV。已入 CLAUDE.md 红线 + 记忆 playbook。
- **RVA 单一事实源=OffsetTable.kt**（用户定案）[V]：hide_pedals 8 个 `#define RVA_*` 拆除改 `hide_pedals_set_offsets` JNI 注入；unlock_hook 3 个 fallback 保留（g_config 异常兜底，注释标版本）。
- **共存版品牌定制固化**（用户定案）[V]：应用名 "Ala Mobile Pro"、图标 #FF8000（HSV：色相 30°/S×1.3/V×1.12，s<0.25 不动；只偏色相压明度会发棕——v1 教训）。已入 coex skill §1a。
- **bot 规则防覆盖**（用户投诉驱动）[V]：`load_rules` key 存在即用户数据永不回落预设（解析失败宁 bot 静默保原文）；`save_rules` 覆盖审计记改前后 diff。部署本身从不写 configs——覆盖链是"读路径回落预设→设置页全量保存固化"。
- 继承：排行榜行级 items / token daemon / 两进程共用代码只用 Logger / order==2 挂圈 / 拦截器零浮点。

## 4. 失败的尝试 — 不要再试
- **IL2CPP dump 用 Windows dotnet** [V]：WSL 无 dotnet；`dotnet <dll>` 经 cmd.exe UNC 路径不被支持必须先复制到 /mnt/c/Temp；`DOTNET_ROLL_FORWARD=Major` 让 6.0 应用跑在 .NET 8。产物输出在输入目录非指定 out 目录。
- **后台任务 cwd 漂移** [V]×3——run_in_background 的命令不继承当前 cwd（assets 合并/打包两次失败），后台命令一律绝对路径。
- **排行榜 VERSION_CODES 混入哨兵 0** [V]：`arrayOf(0, 200150, 200146)` + `getOrNull(selectedVersion-1)` 错位——选 8.0.6 发 `version=0`（服务端空榜）、选 8.0.4 发 200150。日志实锤（`fetchTrackBoard: GET track/5?version=0`）。已修为纯版本码数组。
- **serde `Option` 不兜空串** [V]：表单下拉"全部"提交 `?version=` → "cannot parse integer from empty string" 400。axum Query 在 handler 前运行——未登录请求返回 303（非 400）即反序列化通过的证明。修复=`empty_str_as_none`。
- **askama 模板调 Rust 函数** [X]：E0433 `cannot find module filters`（HANDOFF 继承 + 本轮再次验证）。显示名必须 Rust 侧预处理成字段。
- **图标只偏色相+压明度** [X]：红色相 → 屎棕色。必须 S×1.3 拉饱和。
- **adb push 目标只给目录** [X]：中文+空格文件名报 "Is a directory" 且显示 "1 file pushed" 假成功。目标必须完整文件路径 + 推完 md5 对照。
- **规则覆盖误判为部署所致** [X]：部署从不写 configs。真实链=读路径回落预设→设置页全量保存固化。库内原文 diff 证明用户定制一直在。
- 继承（前向有效，详见 .handoffs/20260904220808-handoff.md §4）：mdns 端口漂移+广播延迟+缓存陈旧 / miuix 无 onSurfaceVariant / 无线 adb 旧端口拒绝 / jsdom location.href 真导航 / ssh 4142 / VPS cargo OOM / fetchMe 漏 token / lap_hook 全套 / IL2CPP 扫描三坑。

## 5. 已知坑
- ⚠️ **8.0.4 官版用户会收到 unsupported** [V]——模块门禁已切 8.0.6 单版本（OffsetTable 按 8.0.6 硬编码，无按版本分表机制）。
- ⚠️ **lint baseline 13 条失效** [?]（继承）——lint 报 baseline not found 提示，下次重新生成。
- ⚠️ **排行榜无实时刷新** [?]（继承，用户拍板暂不做）。
- ⚠️ **管理端网页验证未做** [?]——成绩页四筛选组合/跳页/补录版本下拉（凭据只有用户有）。
- ⚠️ **paddock README/handoff 尚未 push handoff 提交** [V]——本 handoff 将提交两仓 HANDOFF.md；paddock 仓归档文件一并入该提交。
- ⚠️ 继承：双仓赛道中文名两份硬编码（`Shangai` 单 a）/ Garage 206 测试对象 / 群内 bot 复测未做（重置口令严格匹配/带码注册车手号）。

## 6. 下一步（有序）
1. **用户复验**：重开模块 App——围场主页积分 500、排行榜 8.0.6 筛选显示摩纳哥 1:08.964、管理端成绩页四筛选+跳页+补录版本。
2. **群内 bot 复测**（继承）：重置口令严格匹配、裸关键词静默、带码注册车手号=最小空缺。
3. （可选）`deploy/deploy.sh` 固化 paddock 部署链为脚本（用户已认可提议，未实施）。
4. （可选继承）lint baseline 重生成 / 排行榜实时刷新（暂不做）/ 重构范围清单（用户提过"下个版本重构一堆东西"，待指定）。

## 7. 留给用户的开放问题
- 重构范围清单待定（模块+服务端）。
- 播报主动消息额度（约 4 条/月/群）：接受 / 申请认证？
- 官版 8.0.4 用户兼容策略：保持单版本门禁 / 未来做按版本分 OffsetTable？
