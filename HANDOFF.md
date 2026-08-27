# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T11:50+08:00 · Git HEAD: `7e34f6a`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `7e34f6a` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `7e34f6a`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md`（Key Code Conventions 的 hook/日志约定 + lint 约定）+ `TC_LEVEL_DESIGN.md` §10（TC 四轮演化史，本次最大教训所在）+ §2 的触摸现象原话（用户原话，上次丢过一次，禁止再丢）

## 1. 当前目标
TC 档位调节已**完整落地并实机验收**（本会话主线，四轮迭代闭环）；**多指触摸 bug（头号任务）仍处于"插桩完毕、等用户复现回传日志"状态**——本会话未动触摸逻辑，继续等数据，不盲修。

## 2. 已验证状态 — 工作实际停在哪

- [V] **TC 档位调节 v1.4 实机验收通过**（用户原话"可以了，所有都正常了"）——四档时机全部生效、默认档打滑回归、切回默认基线恢复正确。commit `4f29ea1`。
- [V] **TC 根因链反汇编定案**——`TractionFilter`(0x1A64CE4) 门控顺序：①`carSpeed < TCLminSPD(0x38)` 透传**在读 ε 之前**；②`TCLSlip(0x34)==0` = TC 关闭；③`tclEnable(0xc6)`；④`(1-ε)·W > 1` 介入。运行时真实参数 **ε=0.40 / minSPD=11.0 m/s**（TCdiag 写前值实测），与 ctor 默认 0.45/1.0 不同；游戏**不每帧重写**这两字段（进赛道 SetPlayerSettings 写一次后保持）。
- [V] **v1.4 档位参数**——更早 (0.30, 8.0)、非常早 (0.18, 4.0)、实时 (0.02, 0.5)（ε, minSPD m/s 配对）；默认档不写任何字段，切回时恢复首次覆写前捕获的基线。
- [V] **配置页 TC 区 UI 改版实机验收通过**（用户"ok 了"）——命名/描述/档位名/图标（Tune/Bolt）/分隔线成组（游戏默认无线，自定义上下各一条且下线随收回动画贴最后可见项）。commit `ffb7c0c`。
- [V] **技术文档同步**——TECHNICAL_ANALYSIS.md TC 篇按运行时实测参数修正（削减窗口图 ε=0.40 重算、79 km/h"高速关闭"勘误贯穿=维修区限速逻辑非 TC 上限、TC 全速域活跃）；README TC 描述同步档位语义。commits `925c823`/`7e34f6a`。
- [V] **构建验证**——`./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL，0 lint errors（42 warnings 与基线一致）；装机测试为 release 版（adb force-stop → install -r，Success）。
- [V] **工作区**：干净（仅 `.handoffs/` 新归档未跟踪，随本提交入库），`main` 与 `origin/main` 同步。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 2m 9s，lint 0 errors 阻断
git push ×4 → 6dd7ad9..4f29ea1..ffb7c0c..925c823..7e34f6a main -> main（全部成功，退出码 0）
adb install -r → Success
```

## 3. 决策与理由
- **介入时机 = (ε, minSPD) 成对覆写** [V]——反汇编铁证：minSPD 门控①在读 ε 之前，只调 ε 时起步打滑区间（0~40km/h）整段被挡死。否决：σ̄ 模块侧触发层（用户明确拒绝："不用换成什么 σ̄"）。
- **基线捕获/恢复，不用 ctor 默认恢复** [V]——游戏运行时参数 ≠ ctor 默认（0.40/11.0 vs 0.45/1.0），且游戏不每帧重写；基线在首次覆写前捕获，切回默认时还回去。
- **只在用户配置偏离原厂时写字段** [V]——v1.2/v1.3 每帧无条件写"ctor 默认"覆盖了游戏 SetPlayerSettings 真实参数（教训）。TC 关闭（mix=0）仍走 return accel 短路路径 [?]（继承）。
- **UI 命名去"TC"前缀 + 分隔线放 Column 末尾** [V]——用户指定规格；下分隔线借 AnimatedVisibility 收缩动画自动贴住最后可见项，无需手动判断档位挪位置。
- **触摸 bug 不盲修，插桩拿数据** [?]（继承）——否决：继续猜根因改代码。
- **native `g_throttle_active` 语义暂不改** [?]（继承）——候选修复未经日志证实，等数据。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增（TC 调节四轮迭代）
- [X] **只写 TCLSlip=eps 调介入时机** → 用户实测四轮"怎么调都是游戏默认" [V]——根因：minSPD 门控①（运行时 11.0 m/s）在读 ε **之前**，0~40km/h 起步段走不到读 ε 那行；ε 只影响高速段阈值（日常很少触发 W>1.67）。不要再做"只调 ε"的时机档。
- [X] **每帧无条件写"ctor 默认" 0.45/1.0 修粘连** → 所有档位变成"非常早介入" [V]——写的不是真实原厂值；游戏 SetPlayerSettings 实写 0.40/11.0，minSPD 被压到 1.0 吞掉整个起步打滑区间，且无条件写把所有档位拉平。**"ctor 默认 ≠ 运行时真实值"，覆写前必须先实测写前值**。
- [X] **切回默认用 ctor 默认（0.45/1.0）恢复残留** → 恢复值错，应写捕获基线（0.40/11.0）[V]。
- [X] **llvm-objdump --start-address 传文件偏移** → 反汇编出完全不相干的方法 [V]——该参数吃 vaddr；dump.cs 的 `RVA:`/`VA:` 域才是 vaddr，`Offset:` 才是文件偏移（差 0x4000）。与 MODULE_ABS_NOTES §4 三坑同族。
- [X] "本地 assembleRelease 绿 = CI 绿" → assembleRelease 不跑 lint [V]（继承，约定已入 CLAUDE.md：推送前必须跑 `:app:lint`）。

### 触摸 bug（前两轮会话）
- [X] "id 跟踪 + findPointerIndex 能修多指漂移" → 修复 `2b5997c` 后用户实测仍复现。**不要再提同类 index/id 层修复**——根因不在此层。
- [X] "静态审查能定位触摸根因" → 穷举六类机制推演全部无法自洽产生现象。必须走日志数据。
- [X] "用户发来的 log 里能找到触摸现场" → 插桩前版本 log 触摸数据为零。复现前必须先装插桩版。
- [X] "proxy_shift_up 打日志无副作用" → 21 分钟 18810 条洪水。透传 hook 禁止无差别 LOGI 已入 CLAUDE.md。

### 从旧 HANDOFF 搬运（更早，摘要）
- [X] 全 so bl/b 调用扫描直接拿 dump.cs 的 RVA 匹配文件偏移 → 地址域混用系统性零命中（file = VA−0x4000）；浮点 imm12 需 <<2；双字拷贝漏报。三坑固化 MODULE_ABS_NOTES §4。
- [X] "TractionControlDynamicAssist 是 AI 车的 TCL 管理器" → 仅玩家车每帧执行（playercar 0x9C 门控）。
- [X] "escFilter 制动量与侧偏角 β 成正比" → 实为 min(escFactor, 2000/BFT)，β 只做触发判据。
- [X] "IRDSPlayerControls.tractionControl (0x38) 驱动油门斜率调制" → 死字段；真实调制在 CarControllerMobile 比较 slipRatio ≥ 0.2。
- [X] math 公式内放中文 / 表格内裸竖线 / 行内公式跨行 / operatorname → GitHub 渲染坑，已固化持久记忆。
- [X] "HandleABS 被内联"推断方式 → hook 无日志 ≠ 内联；死活判定须全 so bl/b 扫描（注意地址域）。
- [X] "AI 车经 is_player_controller 过滤天然豁免" → AI 车 playerControls (0x108) 可能非空；拦截类 hook 一律白名单，已入 CLAUDE.md。
- [V] 只写 absEnable=false 不写 per-wheel usesABS=false → ABS 仍工作；唯一有效门控是 per-wheel usesABS。
- [V] 后台 pthread 调 Unity API / 主线程 Handler.postDelayed 调 invoke / 直接调 SetActive RVA + NULL MethodInfo* → 崩溃。
- [V] dlopen("libil2cpp.so") → LSPosed namespace 失败，改 ELF 符号查找。
- [V] Component.get_gameObject() RVA 直接调用 → 返回 this 非 GameObject。
- [V] Transform.Find via invoke / GameObject.Find 唯一路径 / 递归遍历每帧 → 崩溃/NULL/卡顿。
- [V] proxy_fixed_update is_player 块外调 hide_pedals_tick → 重新开始后 is_player 返回 false。
- [V] Path.op(INTERSECT)/(DIFFERENCE) → 圆角缝隙/弧线折角。
- [V] alphaOf 语义反 / borderPaint 不读 alpha / drawRoundRect 边框不内缩 / clipPath 裁到 (0,0,w,h) → 绘制坑。
- [V] LAST_TOUCHED 每次 MOVE 更新 → 先按的手指微动夺回优先。
- [V] adb install -r 覆盖安装时旧版运行 → force-stop 再安装。
- [V] awaitLsposedSettled → NPatch 太慢；NONROOT 立即写缓存 → LSPosed 后到补不上。
- [V] 激活检测各坑（clearAll / onResume / onServiceDied / 不分框架 / 弹窗并行 / 只弹一次）→ 均已修。
- [V] kkgithub 404 / mirror.ghproxy DNS 失败 → gh-proxy.com。
- [V] CHUNK_SIZE=256K → TransactionTooLargeException / ANR。
- [V] 手动 rememberNavigationEventDispatcherOwner → 弹窗收不到返回键。
- [V] LSPosed 下 ContentProvider IPC / Remote Preferences commit() → Unknown authority / UnsupportedOperationException。

## 5. 已知坑

- ⚠️ **多指触摸 bug 未解**——现象：一手按踏板一手点空白处，行程填充漂到 100%、反复点反复抽搐；SINGLE/DUAL 都漂；环境共存版 NPatch（pairip 壳）。插桩已就位，等复现日志。三个候选根因待裁决：(a) 意外事件（DOWN/CANCEL 重排）(b) 坐标混入他指 (c) pairip relayout 布局漂移；附加链：reset→游戏接管抽搐（native `g_throttle_active` 被清 → FixedUpdate hook 停写 → 游戏原生输入接管）。
- ⚠️ TC 开关位被每帧重算 [?]——`tclEnable` 被 TractionControlDynamicAssist 每帧无条件写 true，"游戏内设置关 TC 是否生效"仍未实测；模块 TC 调节走字段覆写已绕过此问题（实测生效）。
- ⚠️ TC 削减窗口图与公式基于 ε=0.40 单次实测 [?]——不同车型/难度配置下 SetPlayerSettings 可能写不同值，跨车型泛化前需再采点。
- ⚠️ 游戏内 ABS 设置对物理无效 [?]——设置链终点死字段，模块才有真开关。
- ⚠️ is_player_controller 判据边界 [?]——setter 透传宽松过滤可用；拦截类 hook 必须白名单（CLAUDE.md 已立约）。
- ⚠️ .rodata 常数不可直接改 [?]——TC 补偿系数 −0.85 (0x929E7C)、ABS 阈值 0.15 (0x929A54) 在只读段；TC 调节现走返回值插值/字段覆写绕过，未来改常数仍需 hook。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化晚 ~2s [?]；重新开始后 proxy_player_controls_update 每 ~2s 一次 [?]。
- ⚠️ LSPosed 下 Remote Preferences/Files 只读 [?]；游戏进程对模块包不可见 [?]——用 setComponent 显式广播。
- ⚠️ BillingHook 在 NPatch 模式永远失败 [?]——解锁靠 native hook。
- ⚠️ flyme 后台白名单 [?]；miuix TopAppBar spring [?]；广播 JSON 不含 position [?]；miuix 无 LinearProgressIndicator [?]；OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]。
- ⚠️ GitHub 公式/Mermaid 渲染坑 [?]——写前先读持久记忆 github-math-rendering-pitfalls、mermaid-chart-pitfalls。
- ⚠️ IL2CPP ARM64 扫描三坑 [?]——死方法结论必须双证据交叉验证；llvm-objdump --start-address 吃 vaddr 不吃文件偏移（本次新增实锤）。
- ⚠️ /tmp 逆向临时目录 WSL 重启即丢 [V]——逆向材料从项目 `build/v<版本>-official/base.apk`、`安装包/` 解，不 adb pull（已入持久记忆 game-apk-local-copies）。

## 6. 下一步（有序）

1. **等用户回传触摸 bug 复现日志**——用户需 force-stop 后安装插桩版 APK、**确认日志开关开启**、复现"一手踏板+一手点空白"、导出日志发回。（头号任务，等待中）
2. **分析日志裁决三个候选根因**——(a) 跳变瞬间 action 序列；(b) MOVE 值链 rawY/idx 是否混入他指坐标；(c) DOWN 布局对比是否显示 pairip relayout 漂移。
3. 若日志显示 reset 后游戏接管抽搐 → 评估 native 写入策略：踏板功能开启期间每帧强制写（写 0 也是写），仅 pedalMode=OFF 停写。
4. TC 后续（低优先级）：用户长时间使用后如反馈档位手感偏差，按 TC_LEVEL_DESIGN.md §10 参数表微调各档 (ε, minSPD) 数值。

## 7. 留给用户的开放问题

- 触摸 bug 复现日志回传后：跳变瞬间日志显示的 action 序列是什么？（决定走根因 a/b/c 哪条）
- 用户测试触摸时游戏原生踏板按钮是隐藏还是显示状态？（影响"游戏原生输入接管"假设权重）
- TC 各档位手感（更早/非常早/实时的 ε 与 minSPD 数值）是否需要按日常驾驶体验再标定？