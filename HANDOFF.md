# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T15:06+08:00 · Git HEAD: `41dfbb6`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `41dfbb6` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `41dfbb6`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md`（hook/日志/lint 约定 + 本会话新增的弹窗 show 驱动/emoji VS16 两条 UI 约定）+ `ABS_LEVEL_DESIGN.md`（档名/描述文案已对齐 UI 现状）+ `TC_LEVEL_DESIGN.md` §10（TC 四轮演化史）+ 触摸现象用户原话（**出处: `.handoffs/20260827234945-handoff.md` §2**——指针已锚定，勿再断链）

## 1. 当前目标
配置页 UI 文案与弹窗体验修正已**全部完成并实装推送**（本会话：TC/ABS 档位词汇表合并 → ABS 警示弹窗退出动画修复 → ⚠️ VS16 彩色渲染 → 描述文案机制语义化，每轮构建+安装验证）；**多指触摸 bug（头号任务）仍处于"插桩完毕、等用户复现回传日志"状态**——继续等数据，不盲修。

## 2. 已验证状态 — 工作实际停在哪

- [V] **TC/ABS 档位词汇表合并**（commit `fbb4c81`）：TC 削减强度改为 关闭 TC/低/中/高/最高（默认）（原 中等/强/最强），ABS 干预强度"中等"→"中"——双旋钮现在共享同一套档位词。TC 模式下拉"游戏默认"→"默认"；介入时机默认档改"较晚（默认）"。
- [V] **描述文案定版**：牵引力控制"调整游戏原生 TC"；介入时机"修改介入的滑移指标条件"；ABS 干预强度"修改干预制动偏置"（机制语义，b 字段属 SetBrakeBiasValues 偏置域）；隐藏踏板开关改"隐藏油门和刹车按键"+"隐藏原生油门和刹车，保留离合"。
- [V] **ABS 警示弹窗退出动画修复**：原 `if (showAbsWarnDialog) { OverlayDialog(show=true) }` 条件挂载——关闭时组件被直接移出组合树，miuix 退出动画（show true→false 驱动）没机会播放，表现为闪现消失。改为常驻组合树 + `show=showAbsWarnDialog` 参数驱动（同 SupportDialog/EulaDialog 模式）。全项目排查其余 4 个 OverlayDialog 调用点均为正确模式。
- [V] **⚠️ 彩色渲染修复**：弹窗标题裸 U+26A0 走文本呈现（黑白线框）→ 补 U+FE0F 变体选择符；字节级验证 `e2 9a a0 ef b8 8f`。ABS_LEVEL_DESIGN §4 已记录"⚠️ 须带 VS16"要求。
- [V] **文档同步**：README 档位范围（关闭 TC~最高/较晚（默认）~实时）+ 制动压力"75–100%"→"0–100%"（上会话定版时漏改的残留）；CLAUDE.md Key Code Conventions 新增两条（弹窗 show 驱动模式 / emoji VS16）；ABS_LEVEL_DESIGN §4 档名与描述文案对齐 UI（commit `41dfbb6`）。
- [V] **工作区**：干净，`fbb4c81`（工作）+ `41dfbb6`（持久文档）已推送，`main` 与 `origin/main` 同步。
- [?] **上会话 ABS 定版结论继承**：b=0 泄压主因定案 / usesABS 恢复机制 / 档位标定 0.80/0.60/0.50 / T_b 0-100% 无级——本会话未动 native，细节见 ABS_LEVEL_DESIGN.md v2。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 12s，EXIT_CODE=0（42 warnings=基线，3 errors/17 warnings 被 baseline 过滤）
git push ×2 → 7d0d5a0..fbb4c81 → 41dfbb6 main -> main（全部成功）
adb install -r ×3 → Success（文案轮/VS16 轮/弹窗轮）
```

## 3. 决策与理由
- **TC/ABS 档位词汇表合并** [V]——TC STRONG"强"→"高"、STOCK"最强（默认）"→"最高（默认）"、MEDIUM"中等"→"中"，两个功能一套档位词，用户零重学。否决：保留 TC 原"强/最强"特色命名——无语义增益，徒增心智负担。
- **介入时机默认档名"较晚（默认）"** [V]——默认时机确实比"较早"晚，档名与档位序列（较晚/较早/非常早/实时）一致；"（默认）"后缀标注原厂档地位。否决：保留"默认"——与档位序列语义冲突。
- **描述文案写机制不写效果** [V]——"修改干预制动偏置"描述改了什么（b 属偏置域），"修改干预方波平均"描述改后效果；机制层归描述、效果层归档位名，两层互补。TC/ABS 三滑条描述统一"修改+作用对象"格式。
- **弹窗常驻 + show 驱动** [V]——miuix OverlayDialog 的退出动画由 show true→false 转换驱动；条件挂载直接移除组件。已沉淀 CLAUDE.md 约定。否决：if 条件挂载（本次 bug 形态）。
- **用户可见 emoji 必须带 VS16** [V]——裸码点在 Android 走文本呈现（黑白）。已沉淀 CLAUDE.md 约定。
- **触摸 bug 不盲修** [?]（继承）——插桩就绪等日志。
- **native `g_throttle_active` 语义暂不改** [?]（继承）——等触摸日志裁决。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。本会话无新增失败（三项修复均一次到位）。

### 本会话新增（UI 修正，防再犯）
- [X] **弹窗 if 条件挂载** → 退出动画被跳过，用户可见"闪现消失" [V]——组件被直接移出组合树。miuix OverlayDialog 必须常驻 + show 驱动，照 SupportDialog 模式（onDismissRequest 翻 show / onDismissFinished 跑副作用）。已沉淀 CLAUDE.md。
- [X] **UI 字符串手打裸 ⚠** → Android 渲染黑白文本字形 [V]——须 ⚠️（U+26A0+U+FE0F）。已沉淀 CLAUDE.md。

### 上会话（ABS 档位，继承）
- [X] **押 p0（低速压力上限）做主旋钮** → 用户实测推翻："低速 32% 限压刹不住不是痛点"，真问题是制动力基数过强 + ABS 全段过度保护 [V]——p0 权重 (1−r)² 高速趋零。**设计前先做"该事实在用户痛点里排第几"检查，反汇编最显眼的事实 ≠ 用户最痛的问题**。
- [X] **usesABS 恢复漏置位** → 第一轮修复后用户实测"还是一模一样" [V]——关闭路径没置 `g_abs_uses_taking_over=1`（一个 Edit 的 old_string 写成与 new_string 相同，误判已应用）。教训：Edit 报错"exactly the same"= 没改任何东西，必须重发；日志缺失关键行是代码未生效的第一信号。
- [X] **档位首版 0.15/0.30/0.50** → 用户实测"差异化不足" [V]——有效手感区间远窄于理论窗口（0-0.9），档距 0.15 不构成可感差异；终版 0.80/0.60/0.50 用户标定。
- [X] **制动压力 75% 下限** → 用户"无法判断是否生效" [V]——低速 p0 稀释 + 方波泄压掩盖；改 0-100%（0% = T_b 清零）后可观察。
- [X] **0x3D4 读 currentBrakeBiasFront** → float 读出 denormal≈0、int 读出 2049 垃圾值 [V]——类型/偏移未解，abs_diag 已移除该列；bias 真值从 T_b 反推（4500/75=60）。
- [X] **bgang-bang 方波压到最保守角落的直觉** → 方向性错误 [V]——ABS"增强"方向与 TC 相反（p0 越大干预越弱）。

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
- [V] 只写 absEnable=false 不写 per-wheel usesABS=false → ABS 仍工作；唯一有效门控是 per-wheel usesABS（写 false 后需模块恢复，机制见 ABS_LEVEL_DESIGN §6.2）。
- [V] 后台 pthread 调 Unity API / 直接调 SetActive RVA + NULL MethodInfo* → 崩溃。
- [V] dlopen("libil2cpp.so") → 改 ELF 符号查找；get_gameObject RVA 返回 this 非 GameObject。
- [V] proxy_fixed_update is_player 块外调 hide_pedals_tick → 重新开始后 is_player 返回 false。
- [V] adb install -r 覆盖安装时旧版运行 → force-stop 再安装。
- [V] awaitLsposedSettled → NPatch 太慢；NONROOT 立即写缓存 → LSPosed 后到补不上。
- [V] kkgithub 404 → gh-proxy.com；CHUNK_SIZE=256K → TransactionTooLargeException；手动 rememberNavigationEventDispatcherOwner → 弹窗收不到返回键；LSPosed 下 Remote Preferences/ContentProvider IPC 受限 → setComponent 显式广播。

## 5. 已知坑

- ⚠️ **多指触摸 bug 未解**——现象：一只手按着踏板，另一只手点屏幕上别的空白处，**行程填充就会漂到 100%**，**反复点空白处就反复抽搐**（用户原话：`.handoffs/20260827234945-handoff.md` §2）；SINGLE/DUAL 都漂；环境共存版 NPatch（pairip 壳）。插桩已就位，等复现日志。三个候选根因待裁决：(a) 意外事件（DOWN/CANCEL 重排）(b) 坐标混入他指 (c) pairip relayout 布局漂移；附加链：reset→游戏接管抽搐（native `g_throttle_active` 被清 → FixedUpdate hook 停写 → 游戏原生输入接管）。
- ⚠️ 0x414（fullAbsEnableSpeed）疑似死参数 [?]（继承）——αv 数值只进 kP 项（kP≡0 两态模式），压 0x414 无实效；未实证，二期 kP 解析时一并验。
- ⚠️ 0x3D4 读法未解 [?]（继承）——float/int 皆非；diag 已移除；需配平真值时重新确认类型/偏移。
- ⚠️ TC 开关位被每帧重算 [?]（继承）——模块 TC 走字段覆写已绕过。
- ⚠️ TC 削减窗口图基于 ε=0.40 单次实测 [?]（继承）——跨车型泛化前需再采点。
- ⚠️ 游戏内 ABS 设置对物理无效 [?]（继承）——模块才有真开关。
- ⚠️ .rodata 常数不可直接改 [?]（继承）——ABS 阈值 0.15 (0x929A54)、TC 补偿 −0.85 (0x929E7C)；现走字段覆写/返回值插值绕过。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化晚 ~2s [?]；重新开始后 proxy_player_controls_update 每 ~2s 一次 [?]（继承）。
- ⚠️ LSPosed 下 Remote Preferences/Files 只读 [?]；游戏进程对模块包不可见 → setComponent 显式广播 [?]（继承）。
- ⚠️ BillingHook 在 NPatch 模式永远失败 [?]（继承）——解锁靠 native hook。
- ⚠️ flyme 后台白名单 / miuix TopAppBar spring / 广播 JSON 不含 position / miuix 无 LinearProgressIndicator / OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]（继承）。
- ⚠️ GitHub 公式/Mermaid 渲染坑 [?]——写前先读持久记忆。
- ⚠️ IL2CPP ARM64 扫描三坑 [?]——死方法结论须双证据；llvm-objdump --start-address 吃 vaddr（MODULE_ABS_NOTES §4）。
- ⚠️ offsets_sheet 勘误未订正 [?]——0x1A62E10 实为 setThrottleInput，setBrakeInput 真身 0x1a62df4（ABS_LEVEL_DESIGN §5.9 记录，代码未动防回归）。
- ~~弹窗条件挂载无退出动画~~ [V] 本会话已修复并沉淀 CLAUDE.md；~~裸 ⚠ 黑白~~ [V] 已修复——均不再搬运。

## 6. 下一步（有序）

1. **等用户回传触摸 bug 复现日志**——force-stop 后安装插桩版 APK、**确认日志开关开启**、复现"一手踏板+一手点空白"、导出日志发回。（头号任务，等待中）
2. **分析日志裁决三个候选根因**——(a) 跳变瞬间 action 序列；(b) MOVE 值链 rawY/idx 是否混入他指坐标；(c) DOWN 布局对比是否显示 pairip relayout 漂移。
3. 若日志显示 reset 后游戏接管抽搐 → 评估 native 写入策略（踏板功能开启期间每帧强制写，仅 pedalMode=OFF 停写）。
4. ABS 二期（低优先级）：kP/κ 解析（解锁 0x414 时机维）、弯中自动收紧（M5 思路）、overlay ABS 介入视觉指示、0x410"模拟真实"低速退出档。
5. AI 车白名单长期观察：ABS 档位实装后长时间使用中留意 AI 异常（白名单纪律已守，但 RoadForce 全车必经，持续警惕）。

## 7. 留给用户的开放问题

- 触摸 bug 复现日志回传后：跳变瞬间日志显示的 action 序列是什么？（决定走根因 a/b/c 哪条）
- 用户测试触摸时游戏原生踏板按钮是隐藏还是显示状态？（影响"游戏原生输入接管"假设权重）
- ABS 档位日常使用后手感是否有新标定需求？（当前定版 0.80/0.60/0.50）
- 最大制动压力 0% 观察后（如有做），游戏是否还存在独立压力源？——若 0% 仍见明显制动，说明 T_b 之外另有压力路径，需重新研究。