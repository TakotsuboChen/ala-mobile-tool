# HANDOFF — 读全文再开始干活

生成时间: 2026-09-13T02:14:42+08:00 · Git HEAD: `35ad3be`（模块仓；本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `35ad3be`、paddock main @ `973329d`（2026-09-13 02:14；两边均已 push）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `35ad3be`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源，积分公式见「积分公式」行 / bot 规则见「Bot 消息规则引擎」节）

## 1. 当前目标
**已完成**：围场指南（卡片 + 锁死弹窗 + Markdown 排版分级）。已构建装机、用户实机验收通过（排版调整后）。**现无进行中目标**；下一步仅剩 v1.0.4 定版发布（等用户）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **围场指南全链路**（commit `0f68603`）：`PaddockGuide.kt`（Markdown 正文 + filesDir flag `paddock_guide_seen.flag`，`GUIDE_VERSION=1`）+ `ui/PaddockGuideDialog.kt`（**锁死** `onDismissRequest={}` → 点外/返回键无操作；**读完门控** 滚到底才可点，按钮文案「请先阅读指南」→「我已了解」）+ 围场页已登录区「围场指南」卡（`Icons.Rounded.MenuBook`）+ 自动弹出（已登录 + root 围场页 + 未读）
- [V] **不误弹头像上传**（用户硬要求）：两道闸 —— `navAtRoot`（backStack 深度）+ `!uiState.needsAvatar`。后者必需：`markAvatarDone()` 与 `navigator.push()` 之间无重组点，单靠 `navAtRoot` 有竞态窗口
- [V] **`MarkdownText.kt` 排版分级**（同一 commit）：正文 `lineHeight = fontSize×1.5`；块间距按类型分级（标题上 24dp/下 8dp、列表项间 4dp、列表接上一段 8dp、其余 12dp、段落首行 0）；三级标题 15→16sp；新增可选参数 `textSizeSp` / 外部 `scrollState`。**根因**：原实现所有块统一 6dp 间距 + 无行高 → 用户实机反馈「好挤、好乱、行距区分不明显」（截图实证）
- [V] **正文定稿经用户逐字改写**：问答 5 问（`###` 编号标题）；公式去 `round()` 改纯粗体 `**积分 = (N+1-名次)×100÷N**`（`***` 粗斜体渲染器不支持，会残留星号）；4 人榜例 100/75/50/25 + 区间解释 + 「4人第2=75 → 10人第2=90」浮动例。全部算术已核对（`(N+1-rank)×100÷N`，10 人例 = `(10+1-2)×100÷10 = 90`）
- [V] **构建/lint**（全新 shell）：`./gradlew :app:lint` → BUILD SUCCESSFUL（0 新增 error）；`assembleRelease -x lintVital*` → BUILD SUCCESSFUL；`aapt2 dump badging` → `versionCode=104100 versionName='1.0.4 Alpha 1'`（**未动版本号**）
- [V] 工作区：两仓均 clean，全部已 push。临时草稿 `PADDOCK_GUIDE_DRAFT.md` 已删（未提交）
- [?] **装机**：会话中多次 `adb install -r` Success（设备 381QYFCN22B9A）。handoff 前最后一次 install 报 `no devices/emulators found`——**设备已断开**，最后构建产物（含全部改动）未安装到设备。用户已实机验收"排版前"版本，`1.5×` 行高 + 分级间距是**用户反馈后**的改动，其视觉效果用户看到的是最后一次装机版本——**断连前已装过含排版分级的包**，但 handoff 时无法再验证

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:lint                      → BUILD SUCCESSFUL（EXIT 0；Lint found 62 warnings, 4 hints，baseline 过滤 3 errors/15 warnings）
./gradlew :app:assembleRelease -x lintVital* → BUILD SUCCESSFUL（EXIT 0）
aapt2 dump badging app-release.apk       → versionCode='104100' versionName='1.0.4 Alpha 1'
adb install -r app-release.apk           → 会话中 Success；handoff 时 no devices（设备断开）
```

## 3. 决策与理由
- **弹窗锁死走 `onDismissRequest={}`** [V]——正文点击、窗口外点击、返回键三路都收敛到这一个回调，置空即全锁死，比逐路拦返回键更不易漏
- **`markSeen` 放按钮 onClick 而非 `onDismissFinished`** [V]——miuix `onDismissFinished` 在弹窗被抢断/取消时不触发；用户点「我已了解」是明确 yes，应立刻落盘
- **改共享 `MarkdownText` 而非给指南单开渲染路径** [V]——更新弹窗（Release Note）一并受益，避免第二套渲染逻辑
- **行高/间距是根因而非装饰** [V]——"行高区分段内换行、块间距区分块"，两者缺一都糊；标题留白非对称（上 24/下 8 ≈ 3:1）让标题在视觉上归属于其下内容
- **README 不改** [V]——本次是模块内设置 UI 新增，无对外功能/命令变化
- 继承：`action_metas()` 单一事实源 / 信箱通道优于修进程通道 / 登出写空 token / AFA 硬性前置 / 4xx 先存后判(isRetryableStatus 单源) / 零 Hook 判定红线 / 版本号红线——详见 `.handoffs/20260913021442-handoff.md` §3

## 4. 失败的尝试 — 不要再试
- **`***text***` 粗斜体** → 渲染成 `*text*`（外层星号残留）[V]——`MarkdownText` 解析器无 `***` 分支，`**a**` 优先匹配吃掉中间两星。用纯粗体 `**` 或行内代码
- **给 `MarkdownText` 传表格 Markdown** → 逐行当普通文本渲染，无对齐网格 [V]——解析器不支持表格
- **alpine 容器跑本地编译的 Rust 二进制** → `no such file or directory`（容器 Restarting 255）[V]——本地 glibc ELF vs alpine musl。必须 debian-slim base
- **psql `-v` 传大 JSON / heredoc 转义 `\047`** → 语法错误 [V]——用 dollar-quoting（`$json$...$json$`）写 SQL 文件 + `-f`
- **用旧快照 JSON 覆盖 `configs` 表** → 抹掉用户在场编辑（删的规则复活）[V]——写前必 dump 实况，在实况上增量改；管理端保存是前端整数组全量回传，并发编辑互相覆盖
- 继承 [X]：`LocalBringIntoViewSpec` / `evaluateLoginGate` 用初始值 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / MTDataFilesProvider 自授 URI / `am kill` 杀前台 / shell `appops set` 授 AFA / 无线 adb 开飞行模式
- 详见 `.handoffs/20260913021442-handoff.md` §4（含更多历史死路）

## 5. 已知坑
- ⚠️ **权限门视觉未实机验收** [?](继承)——设备当前**已授权**态，门控页不显示；需临时关 AFA 再开模块 App 看观感
- ⚠️ **配置热更新仍走广播** [?](继承)——ColorOS 关关联启动 + 游戏不在前台时广播可能丢（信箱覆盖冷启动）。用户已说"暂时保持现状"
- ⚠️ **信箱残留探针文件** [?](继承)——`/sdcard/Android/media/<游戏包>/` 下 `ala_probe_*` 残留，可手动删，无功能影响
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](继承,待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话"
- ⚠️ **bot 投递未端到端验证** [?]——本地模拟仅验证到"回复正文正确"，真实群投递需在群里发指令确认
- ⚠️ **围场指南 1.5× 行高下弹窗可视区变少** [?]——限高 `maxHeightFraction=0.55`，行高提升后一屏装不下几行；用户如觉得仍挤/仍读不完，调 `maxHeightFraction` 或行高倍数
- ⚠️ **对话纪律** [V](继承)——1 mid-turn 消息逐条消化 2 旧快照不当现在时断言 3 装机前先验设备上 APK 版本 4 调查日志前先对齐"几次"计数 5 修 UI 时序问题先拉触摸时间线
- ⚠️ 继承: 导出探活广播链 ROM 限流边界 / lint baseline 15 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码 / ABSdiag 降频 / 闪退未定案——详见 `.handoffs/20260913021442-handoff.md` §5

## 6. 下一步（有序）
1. **v1.0.4 正式发布待用户定版**（版本号红线：先问用户版本号定多少，不擅自升）。Release Notes 应含——围场指南卡+弹窗 + Markdown 排版分级 + 权限门控页 UI 统一 + 跨包信箱通道 + AFA 权限门 + 配置仲裁 null 源短路修复 + 反向红线修复（登出空 token）+ `drainQueue` 计数 + 配置写入遍历 allServices + 围场页「忘记用户名」+ bot 查询用户名动作
2. （设备侧，可选）验收权限门观感：临时关 AFA → 开模块 App → 再开回来
3. （可选）真实群里发「查询用户名」确认 bot 投递 / 清理设备信箱探针残留 / ABSdiag·TCdiag 降频 / 闪退 crash 现场定案

## 7. 留给用户的开放问题
- 围场指南弹窗限高与行高是否需要再调（当前 0.55 屏 / 1.5× 行高）
- 围场指南图标是否换更贴赛车气质的（现为 Material `MenuBook`；若要可仿 `ChequeredFlagIcon` 搬 SVG 进 `CustomIcons.kt`）
- 指南自动弹出范围：目前仅已登录（未登录无围场主页内容卡），是否要给未登录也留入口
- 信箱通道是否给游戏侧加"配置热更新"路径（现走广播）？用户已说"暂时保持现状"
- 永久 4xx 的处理：先提示后静默丢弃（现状）vs 无限重试
- v1.0.4 正式版版本号与发布时机；官版游戏（无 AFA 声明）是否也要加权限门
