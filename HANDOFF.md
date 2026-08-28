# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T13:35+08:00 · Git HEAD: `f470aa8`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `f470aa8` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f470aa8`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md`（hook/日志/lint 约定）+ `ABS_LEVEL_DESIGN.md`（本次最大成果的全文）+ `TC_LEVEL_DESIGN.md` §10（TC 四轮演化史）+ §2 的触摸现象原话（用户原话，上次丢过一次，禁止再丢）

## 1. 当前目标
ABS 档位调节已**实装并实机定版**（本会话主线：三方研究 → 设计 v1 → 用户实测推翻 → 深挖重设计 → 实装 → 修 bug → 用户标定定版）；**多指触摸 bug（头号任务）仍处于"插桩完毕、等用户复现回传日志"状态**——本会话未动触摸逻辑，继续等数据，不盲修。

## 2. 已验证状态 — 工作实际停在哪

- [V] **ABS 档位实装并定版**（commit `0b5710c`）：双旋钮——干预强度（pulse 释放深度 b 覆写，5 档：关闭 ABS/低 0.80/中等 0.60/高 0.50/最高默认不写）+ 最大制动压力（T_b=0x88 等比缩放，0-100% 无级，独立于 ABS 模式生效）。用户确认"功能完全正常"，档位值已按用户标定定案。
- [V] **运行时真值定案**：ABSdiag 实测前轮 T_b=4500（75×bias60）、后轮 3000（75×40）、b=0.000、uses 基线=1——与 SetBrakeBiasValues(0x1762BF4) 计算式逐位吻合。**b=0（原厂方波完全泄压）实锤**，全段过度保护主因（TECHNICAL_ANALYSIS §2.8.2 修正块已写入）。
- [V] **usesABS 残留 bug 修复**：关闭路径写 false 后游戏永不自己写回（Awake 唯一写者）→ 恢复通道（基线捕获/taking_over 置位/一次性恢复）+ 覆写块前移防基线污染。修复后日志实证 `usesABS baseline restored (1)`，用户确认生效。
- [V] **制动压力字段写入生效**：ABSdiag 实证 tbCfg=0.75 → tb=3375.0 写入保持；75% 下限太温和导致"无法判断"→ 改 0-100% 无级（0%=T_b 清零，高速 F_base→0，观察信号极强）。
- [V] **配置页 UI**：ABS 区（默认/自定义下拉+每次切换弹窗+干预强度卡）、最大制动压力独立项（BrakeCurveIcon）、分隔线与 TC 区条件互斥（防双线重叠）、全部卡片标题/描述字体统一对齐 miuix BasicComponent 标准（headline1 17sp Medium / body2 14sp，手写 SliderPreference 原为 15sp/13sp 不粗）。
- [V] **技术文档定版**：ABS_LEVEL_DESIGN.md（设计+标定史+验证记录）、TECHNICAL_ANALYSIS.md ABS 篇勘误、MODULE_ABS_NOTES.md §2b。commit `77d3bf1`。
- [V] **构建验证**：`./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL，**EXIT=0**，lint 0 errors（42 warnings 与基线一致）。
- [V] **工作区**：干净（工作 `0b5710c` + 技术 docs `77d3bf1` + 持久 docs `f470aa8` 全部已推送），`main` 与 `origin/main` 同步。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 50s，EXIT=0，lint 0 errors（42 warnings=基线）
git push ×3 → 224b3be..0b5710c..77d3bf1..f470aa8 main -> main（全部成功）
adb install -r ×5 → Success（历轮修复版）
实机日志：baseline b=0.000 tb=4500/3000 uses=1；usesABS baseline restored (1)；tbCfg=0.75→tb=3375.0
```

## 3. 决策与理由
- **主旋钮 = b（0x3E0）而非 p0（0x3E4/0x3E8）** [V]——用户实测推翻 v1 低速锚点：p0 权重 (1−r)² 高速趋零只影响低速；b=0 方波泄压才是全段过度保护主因（开 ABS 0.32-0.45 T_b vs 关 ABS 1.0，2.2-3 倍）。否决：v1 的 p0 主旋钮方案（整个被推翻重写）。
- **首版单旋钮 + 制动压力独立项** [V]——行业单旋钮主流（LMU 复合语义反面教材）+ 游戏侧 0x414 在两态模式（kP≡0）下疑似死参数；制动压力（T_b 缩放）修"关 ABS 秒锁死"与干预档正交，独立项形态使"关 ABS 也能修基数"。否决：双滑条（TC 式时机维）——游戏侧无独立时机量。
- **enableAbs 派生化**（照 TC enableTc 先例）[V]——关闭 = CUSTOM+OFF；旧 bool 迁移 false→CUSTOM+OFF（红线：老用户"ABS 关闭"不得变"默认"）。
- **档位绝对值语义** [V]——b 覆写为绝对值非相对系数（防复利）；用户配平≠60 时基线 b≠0 的相对关系首版接受。
- **制动压力等比缩放非截断** [V]——踏板响应曲线（输入端 0-1）完全正交，用户确认此语义。
- **触摸 bug 不盲修** [?]（继承）——插桩就绪等日志。
- **native `g_throttle_active` 语义暂不改** [?]（继承）——等触摸日志裁决。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增（ABS 档位）
- [X] **押 p0（低速压力上限）做主旋钮** → 用户实测推翻："低速 32% 限压刹不住不是痛点"，真问题是制动力基数过强 + ABS 全段过度保护 [V]——p0 权重 (1−r)² 高速趋零。**设计前先做"该事实在用户痛点里排第几"检查，反汇编最显眼的事实 ≠ 用户最痛的问题**。
- [X] **usesABS 恢复漏置位** → 第一轮修复后用户实测"还是一模一样" [V]——恢复段代码在但关闭路径没置 `g_abs_uses_taking_over=1`（当时一个 Edit 的 old_string 写成与 new_string 相同，误判已应用）。教训：Edit 报错"exactly the same"= 没改任何东西，必须重发；日志缺失关键行（`usesABS baseline restored` 从未出现）是代码未生效的第一信号。
- [X] **档位首版 0.15/0.30/0.50** → 用户实测"差异化不足，低档只有一丁点锁死" [V]——有效手感区间远窄于理论窗口（0-0.9），0.15 档距不可感；终版 0.50/0.60/0.80 由用户标定。
- [X] **制动压力 75% 下限** → 用户"无法判断是否生效" [V]——低速 p0 稀释 + 方波泄压掩盖，75% 太温和；改 0-100%（0% = T_b 清零）后可观察。
- [X] **0x3D4 读 currentBrakeBiasFront** → float 读出 denormal≈0、int 读出 2049 垃圾值 [V]——类型/偏移未解，abs_diag 已移除该列；bias 真值从 T_b 反推（4500/75=60）。
- [X] **bgang-bang 方波压到最保守角落的直觉**（第一版把"低干预档"设计成抬 p0）→ 方向性错误 [V]——ABS"增强"方向与 TC 相反（p0 越大干预越弱）。

### 触摸 bug（前几轮会话，继承）
- [X] "id 跟踪 + findPointerIndex 能修多指漂移" → 修复 `2b5997c` 后用户实测仍复现。**不要再提同类 index/id 层修复**——根因不在此层。
- [X] "静态审查能定位触摸根因" → 穷举六类机制推演全部无法自洽产生现象。必须走日志数据。
- [X] "用户发来的 log 里能找到触摸现场" → 插桩前版本 log 触摸数据为零。复现前必须先装插桩版。
- [X] "proxy_shift_up 打日志无副作用" → 21 分钟 18810 条洪水。透传 hook 禁止无差别 LOGI 已入 CLAUDE.md。

### 继承（TC 调节四轮 + 更早，摘要）
- [X] 只写 TCLSlip=eps 调介入时机 → minSPD 门控①在读 ε 之前，起步段整段被挡死 [V]。
- [X] 每帧无条件写"ctor 默认" 0.45/1.0 → 写的不是真实原厂值（实写 0.40/11.0），全部档位拉平 [V]。**"ctor 默认 ≠ 运行时真值"**。
- [X] 切回默认用 ctor 默认恢复残留 → 应写捕获基线 [V]。
- [X] llvm-objdump --start-address 传文件偏移 → 吃 vaddr（差 0x4000）[V]。
- [X] "本地 assembleRelease 绿 = CI 绿" → assembleRelease 不跑 lint，推送前必须 `:app:lint` [V]（已入 CLAUDE.md）。
- [X] "TractionControlDynamicAssist 是 AI 车的 TCL 管理器" → 仅玩家车每帧执行 [V]。
- [X] "IRDSPlayerControls.tractionControl (0x38) 驱动油门斜率调制" → 死字段 [V]。
- [X] math 公式内放中文 / 表格内裸竖线 / operatorname → GitHub 渲染坑（见持久记忆）。
- [X] "AI 车经 is_player_controller 过滤天然豁免" → AI 车 playerControls (0x108) 可能非空；拦截类 hook 一律白名单 [V]。
- [V] 只写 absEnable=false 不写 per-wheel usesABS=false → ABS 仍工作；唯一有效门控是 per-wheel usesABS（本会话新增：写 false 后需模块恢复，见 §3）。
- [V] 后台 pthread 调 Unity API / 直接调 SetActive RVA + NULL MethodInfo* → 崩溃。
- [V] dlopen("libil2cpp.so") → 改 ELF 符号查找；get_gameObject RVA 返回 this 非 GameObject。
- [V] proxy_fixed_update is_player 块外调 hide_pedals_tick → 重新开始后 is_player 返回 false。
- [V] adb install -r 覆盖安装时旧版运行 → force-stop 再安装。
- [V] awaitLsposedSettled → NPatch 太慢；NONROOT 立即写缓存 → LSPosed 后到补不上。
- [V] kkgithub 404 → gh-proxy.com；CHUNK_SIZE=256K → TransactionTooLargeException；手动 rememberNavigationEventDispatcherOwner → 弹窗收不到返回键；LSPosed 下 Remote Preferences/ContentProvider IPC 受限 → setComponent 显式广播。

## 5. 已知坑

- ⚠️ **多指触摸 bug 未解**——现象：一只手按着踏板，另一只手点屏幕上别的空白处，**行程填充就会漂到 100%**，**反复点空白处就反复抽搐**；SINGLE/DUAL 都漂；环境共存版 NPatch（pairip 壳）。插桩已就位，等复现日志。三个候选根因待裁决：(a) 意外事件（DOWN/CANCEL 重排）(b) 坐标混入他指 (c) pairip relayout 布局漂移；附加链：reset→游戏接管抽搐（native `g_throttle_active` 被清 → FixedUpdate hook 停写 → 游戏原生输入接管）。
- ⚠️ 0x414（fullAbsEnableSpeed）疑似死参数 [?]——αv 数值只进 kP 项（kP≡0 两态模式），压 0x414 无实效；未实证，二期 kP 解析时一并验。
- ⚠️ 0x3D4 读法未解 [?]——float/int 皆非；diag 已移除；需配平真值时重新确认类型/偏移。
- ⚠️ TC 开关位被每帧重算 [?]（继承）——模块 TC 走字段覆写已绕过。
- ⚠️ TC 削减窗口图基于 ε=0.40 单次实测 [?]（继承）——跨车型泛化前需再采点。
- ⚠️ 游戏内 ABS 设置对物理无效 [?]（继承）——模块才有真开关。
- ⚠️ .rodata 常数不可直接改 [?]（继承）——ABS 阈值 0.15 (0x929A54)、TC 补偿 −0.85 (0x929E7C)；现走字段覆写/返回值插值绕过。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化晚 ~2s [?]；重新开始后 proxy_player_controls_update 每 ~2s 一次 [?]（继承）。
- ⚠️ LSPosed 下 Remote Preferences/Files 只读 [?]；游戏进程对模块包不可见 → setComponent 显式广播 [?]（继承）。
- ⚠️ BillingHook 在 NPatch 模式永远失败 [?]（继承）——解锁靠 native hook。
- ⚠️ flyme 后台白名单 / miuix TopAppBar spring / 广播 JSON 不含 position / miuix 无 LinearProgressIndicator / OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]（继承）。
- ⚠️ GitHub 公式/Mermaid 渲染坑 [?]——写前先读持久记忆。
- ⚠️ IL2CPP ARM64 扫描三坑 [?]——死方法结论须双证据；llvm-objdump --start-address 吃 vaddr（本次新增实锤见 MODULE_ABS_NOTES §4）。
- ⚠️ offsets_sheet 勘误未订正 [?]——0x1A62E10 实为 setThrottleInput，setBrakeInput 真身 0x1a62df4（ABS_LEVEL_DESIGN §5.9 记录，代码未动防回归）。

## 6. 下一步（有序）

1. **等用户回传触摸 bug 复现日志**——force-stop 后安装插桩版 APK、**确认日志开关开启**、复现"一手踏板+一手点空白"、导出日志发回。（头号任务，等待中）
2. **分析日志裁决三个候选根因**——(a) 跳变瞬间 action 序列；(b) MOVE 值链 rawY/idx 是否混入他指坐标；(c) DOWN 布局对比是否显示 pairip relayout 漂移。
3. 若日志显示 reset 后游戏接管抽搐 → 评估 native 写入策略（踏板功能开启期间每帧强制写，仅 pedalMode=OFF 停写）。
4. ABS 二期（低优先级）：kP/κ 解析（解锁 0x414 时机维）、弯中自动收紧（M5 思路）、overlay ABS 介入视觉指示、0x410"模拟真实"低速退出档。
5. AI 车白名单长期观察：ABS 档位实装后长时间使用中留意 AI 异常（白名单纪律已守，但 RoadForce 全车必经，持续警惕）。

## 7. 留给用户的开放问题

- 触摸 bug 复现日志回传后：跳变瞬间日志显示的 action 序列是什么？（决定走根因 a/b/c 哪条）
- 用户测试触摸时游戏原生踏板按钮是隐藏还是显示状态？（影响"游戏原生输入接管"假设权重）
- ABS 档位日常使用后手感是否有新标定需求？（当前定版 0.50/0.60/0.80）
- 最大制动压力 0% 观察后（如有做），游戏是否还存在独立压力源？——若 0% 仍见明显制动，说明 T_b 之外另有压力路径，需重新研究。