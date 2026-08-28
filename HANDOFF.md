# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T21:35:40+08:00 · Git HEAD: `82a53ba`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `82a53ba` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `82a53ba`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `ModConfig.kt` 的 `monotoneCubic`/`solveCurveTangents` KDoc（曲线算法单一事实源）+ `TC_LEVEL_DESIGN.md` §10 v1.5 + `ABS_LEVEL_DESIGN.md` §4 + `.handoffs/20260828213540-handoff.md` §4（死路总账）

## 1. 当前目标
踏板响应曲线"超强适应性"研究收口：三路调研 + 数值验证定位 S 形根因（FC 切线策略），落地曲率能量最小化切线替换（控制点交互不变），用户实测"有局部改善但差别不大，先这样吧"——**曲线话题按用户裁决收口**。两个长期遗留不变：①多指触摸 bug 等复现日志（头号）；②冷启动满帧（baseline profile 缺失）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **曲线算法升级已落地**：`ModConfig.kt`（+98/-22）`monotoneCubic` 切线从 Fritsch–Carlson 调和平均 → **曲率能量最小化**（盒约束凸 QP 坐标下降，FC 单调盒内全局最优；初值=FC 切线，未收敛极端情形自动退回旧行为）。签名不变，调用方（PedalOverlayView/ConfigurePagerMiuix）零改动；新增 LRU 切线缓存（预览每帧 41 次采样同点集复用）。
- [V] **S 形根因（数值证实）**：FC 段内导数是二次多项式，两端切线不等时段内必出斜率超调（想要"陡→缓"实测混入 1.4→1.59→0.6 鼓包）；端点切线钉在 secant → "两端缓中间陡"观感；FC 原文自认非局部性（移动一点波及全曲线）。
- [V] **Python 镜像全量回归**：缓→陡/陡→缓单点误差 0.047→**0.028（-40%）**、S 形 0.056→0.052、直线/对角多点精确 0.000、非单调点序/空点集/极窄段(0.001)/折线拐角全安全。
- [V] **混合形状边界（三次撞墙）**：单点 (0.6,0.6) 编码不了"拐点在 0.7"——点位置不含意图信息，是信息论边界；多点编码混合形状两算法打平（FC 0.150 vs 曲率最小化 0.158）。**"更强算法"到此为止，是插值语义下的数学最优**。
- [?] **用户实测裁决**："还是不太行，跟原来效果差不太多，有局部改善，先这样吧"——数学误差 -40% 与实机感知改善不对等（用户用例可能贴近混合形状场景）；曲线迭代按用户裁决收口，预设+滑块方案（调研强烈推荐）**未实施**（用户未要求）。
- [V] 工作区：干净，工作切片 `82a53ba` 已推送，main 与 origin/main 同步。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:lint :app:assembleRelease（全新 shell）→ BUILD SUCCESSFUL in 3s，EXIT_CODE=0
  （42 warnings=基线，3 errors/17 warnings 被 baseline 过滤——与上会话完全一致，零新增）
Python 镜像全量回归 → 5 用例 + 边界 case 全过（数值见 §2）
adb install -r app-release.apk → Success（前已 force-stop 游戏进程，MEIZU 20 381QYFCN22B9A）
git push → 82a53ba 成功
```

## 3. 决策与理由
- **曲率最小化替换 FC，零交互变化** [V]——控制点交互是用户死守约束（见下条），算法层是唯一可动面；凸 QP 有全局最优解，"猜"变"优化"；FC 单调盒保留（[0,3·min] 带符号推广）保住无过冲。否决：回归学派（Whittaker/supsmu/I-splines）——曲线不过控制点，拖点不跟手，交互语义崩坏。
- **用户否决画笔模式** [V]——"不要画笔，我就认死只允许用户添加和拖动控制点"。数值验证本身成功（freehand+PAVA+dense LUT 误差 0.027-0.052，混合形状比 FC 减半），但交互形态被拒。
- **数学最优 ≠ 感知改善** [?]——单点弯意图误差 -40% 实测"差不太多"。若重启曲线工作：先做感知阈值实验（判断 0.02 级误差差是否可辨），别再直接信误差数字。
- **三主题调研收敛的结构** [V]——行业（Xbox/Sony/Apex/Steam 全是预设+强度滑块，无一做切线手柄）+ 赛车（Fanatec 词汇 linear/progressive/degressive；触屏踏板=纯行程零反力最需要曲线；移动赛车游戏无一内置曲线编辑器）+ 数学（FC 三缺陷文献确认；cubic-bezier y∈[0,1] 单调有仿射自证；工业终极答案=分段 Hermite+每段切线模式）。**未来若重启曲线：预设档+强度滑块是行业验证解法**，候选公式已验证——幂族 y=x^k（k∈[1,4]）、y=1-(1-x)^k、rational smoothstep xⁿ/(xⁿ+(1-x)ⁿ)（n=1 精确直线、有闭式逆、严格单调）。关键来源：iquilezles.org/articles/smoothsteps、Eilers "A Perfect Smoother"（2003）、Casiez 1€ Filter（CHI 2012）、Wikipedia Monotone cubic interpolation、Fanatec pedal curves 官方博客。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增（曲线研究）
- [X] **画笔模式（freehand + PAVA 保序回归 + dense LUT）**→ 数值成功但被用户否决："不要画笔，只认控制点" [V]。技术路线本身有效，若未来交互约束放开可复用（PAVA ~20 行 O(n)，LUT 求值，误差 0.027-0.052）。不要再主动提。
- [X] **"换更聪明的插值算法就能任意形状"** → 三次撞墙：曲率最小化单点纯弯 -40% 但混合形状打平；Akima/曲率最小化/有理样条都只是换猜法；supsmu/Whittaker 回归语义不过点。信息论边界：点位置不携带"拐点在哪"的意图信息。再往上只有两条路：预设参数化（直达意图）或更多控制点。
- [V] **Python 镜像先行的两个截获 bug**：①能量展开丢交叉项系数（(a+b+c)² 展开交叉项是 2 倍）→ 连直线都不收敛（误差 0.019）；②单调盒初值 ±∞ → `min(loI,s3,0)` 永远 -∞，clamp 静默失效，测试假绿。教训：凸 QP 修对系数后收敛解必是全局最优；盒约束构造要用"初值 0 再被 secant 收缩"。
- [X] "数学误差降低 ≈ 用户可感知" → 用户实测推翻（"差不太多，有局部改善"）。误差数字到感知之间隔着未标定的阈值，后续调"手感"类工作先做感知阈值实验。
- 继承（档位调优，全部 [V]）：enum 重命名顺手改存档 value 字符串 → 旧配置静默 fallback（重命名标识符 ≠ 重命名持久化键）；把过期注释当需求事实源（"0.75 幽灵"——注释与代码矛盾时以代码为准）。
- 继承（手势/测量，全部 [V] 证伪，勿重试）：滑块 v1 同节点无取值消费层（Compose slop 自由竞争+Final pass 复核，拖不动）；v2 DOWN 起无差别独占（吞竖滑，帧数 1/6）；"设备性能"归因（用户两次否决，A/B 才是路）；GestureAxisBlocker 整向量消费（全页竖滚死）；封锁条件语义写反；`pointerSlop()` API 项目版本无；perfetto shorthand tags 抓用户版数据；测量期间手触（免触碰+截图核对）；miuix 查源码先联网（先查 `references/miuix/`）。
- 继承（更早，[?] 除标注外）：押 p0 主旋钮被实测推翻；usesABS 恢复漏置位；档位差异化不足（已响应）；0x3D4 读法未解；bang-bang 方向反直觉；只写 TCLSlip=eps 挡死起步段；每帧写 ctor 默认≠真值；llvm-objdump 传文件偏移；assembleRelease 不跑 lint（推送前必须 `:app:lint`）；TractionControlDynamicAssist 仅玩家车；IRDSPlayerControls.tractionControl 死字段；math 公式内中文/裸竖线/operatorname；AI 车 is_player_controller 过滤不可靠；触摸 bug id 跟踪/findPointerIndex 修不了（根因不在此层）；proxy_shift_up 打日志洪水（21min 18810 条）。
- [V] 继承：只写 absEnable 不够（唯一门控 per-wheel usesABS）；后台 pthread 调 Unity API 崩溃；dlopen 不可用改 ELF 符号查找；adb install -r 前必须 force-stop；awaitLsposedSettled 不可靠；kkgithub 404 用 gh-proxy。

## 5. 已知坑
- ⚠️ **多指触摸 bug 未解（头号任务）** [?]（继承）——一手踏板+一手点空白，行程填充漂 100%、反复抽搐（原话：`.handoffs/20260828132840-handoff.md` §2）；插桩就位等复现日志；三候选根因（意外事件重排/坐标混入他指/pairip relayout）。
- ⚠️ **配置页"任何时候满帧"未达成（用户核心不满）** [?]（继承）——A/B 实测 HEAD 冷启动即 18-19% janky（p50 7ms），热身后 0.7-2%。主因候选：无 baseline profile（build.gradle 零配置）+ 单 item 全量重组 + JIT 预热约 1 分钟。修复方向：androidx.baselineprofile + 可能拆配置页单 item。动手前先重跑 A/B 基线。
- ⚠️ 曲线编辑器拖点高频路径现已含 QP 求解 [?]（本会话）——有 LRU 缓存 + 收敛早停（实测 <20 迭代），未见性能问题；若未来卡顿排查，先查 tangentCache 命中（key 是 sorted List equals）。
- ⚠️ pager `startDragImmediately` settle 回抓在 Initial pass 无条件认领 DOWN [?]（继承）——滑块抓取瞬间 settle 中断闪动为已知残余。
- ⚠️ 滑块 v3 独占期间内部 isDragging 失效，快弹簧回落慢弹簧 [?]（继承）——用户未抱怨，暂不动。
- ⚠️ 0x414（fullAbsEnableSpeed）疑似死参数 [?]；0x3D4 读法未解 [?]；TC 开关位每帧重算（已绕过）[?]；游戏内 ABS 设置对物理无效 [?]；计时赛 IRDSUIMobileControls 晚 ~2s [?]；LSPosed 下 Remote Preferences 受限→setComponent 显式广播 [?]；BillingHook NPatch 模式永远失败 [?]；flyme 后台白名单/miuix TopAppBar spring/广播 JSON 不含 position/OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]；GitHub 公式/Mermaid 渲染坑（先读持久记忆）[?]；IL2CPP ARM64 扫描三坑 [?]；offsets_sheet 0x1A62E10 勘误未订正 [?]。

## 6. 下一步（有序）
1. **等触摸 bug 复现日志**（头号，等待中）——force-stop 后装插桩版、确认日志开关开启、复现"一手踏板+一手点空白"、导出日志回传；分析三候选根因。
2. **TC/制动压力新手感回传后按需微调**——只动 `ModConfig.kt` 的 mix/eps/bOverride 常数；若"较早"ε=0.35 与默认区分不开则再压。
3. **冷启动满帧结构性修复（长期目标）**——androidx.baselineprofile 基础设施 + 配置页单 item 拆分评估；动手前重跑 A/B 基线。
4. ABS 二期（低优先级）：kP/κ 解析、弯中自动收紧、overlay ABS 视觉指示、0x410 低速退出档。
5. AI 车白名单长期观察（继承）。

## 7. 留给用户的开放问题
- 触摸 bug 复现日志回传后：跳变瞬间 action 序列？游戏原生踏板按钮隐藏还是显示？（决定根因走向）
- 冷启动验收标准：首分钟不卡（baseline profile 大概率够）还是完全追平 KernelSU 满帧（需拆配置页结构，单独评估）？
- TC 新档位（强度 0.15/0.4/0.6、时机 ε0.35/0.25）+ 制动压力 50% 下限的实机手感（继承，两轮未答）？
- 曲线话题若重启：是否采用调研推荐的**预设档+强度滑块**（行业四巨头验证形态，幂族/rational 公式已验证，见 §3）？曲率最小化已是插值语义最优，混合形状需预设或更多点——纯算法无提升空间（信息论边界）。