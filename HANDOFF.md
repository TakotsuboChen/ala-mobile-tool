# HANDOFF — 读全文再开始干活

生成时间: 2026-08-27T23:49+08:00 · Git HEAD: `4afef26`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `4afef26` (2026-08-27)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `4afef26`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` 全文 + `CLAUDE.md`（Key Code Conventions 的 hook 约定 + Common Commands 的 lint 约定）+ §2 的现象描述（用户原话，上次丢过一次，禁止再丢）

## 1. 当前目标

**多指触摸 bug（头号任务）处于"插桩完毕、等用户复现回传日志"状态**——不变，本会话只修了 CI（副线），未动任何触摸修复逻辑。等数据，不盲修。

## 2. 用户补充的现象（用户原话，勿再丢失）

> 一只手按着踏板，另一只手点屏幕上别的空白处，**行程填充就会漂到 100%**，**反复点空白处就反复抽搐**。

- 环境：**共存版（NPatch）**，pairip 壳确认存在（log 有 `com.pairip.application.Application@6af64a2`）[?]（继承）
- **SINGLE 和 DUAL 两种模式都漂**（用户 AskUserQuestion 确认）[?]（继承）
- findPointerIndex 修复（`2b5997c`）后实测不成功 [?]（继承）
- 旧 log `/mnt/d/Downloads/ala_tool_log_20260826_223006.txt` 等对触摸诊断**零价值**（无插桩版本）

## 2b. 已验证状态 — 工作实际停在哪

- [V] **CI lint 修复已落地**（commit `5d02653`）——根因：`getRawY(pointerIndex)` 多指重载是 API 29+，minSdk 26 lint 报 `NewApi` error；8月26 `2b5997c` 引入，CI 从 8-27 凌晨 04:59 起连红 20+ run 无人察觉（本地 `assembleRelease` 不跑 lint）。修法：`PedalOverlayView.rawYAt(pointerIndex)` 顶层私有扩展——SDK≥29 走官方 API，以下走数学等价 `getRawY() − getY() + getY(pointerIndex)`（同事件 raw 偏移对所有 pointer 一致），两处调用点（updateValuesFromPointer + diagMaybeLogMove）已换。
- [V] **验证**——本地全新 shell `./gradlew :app:lint` → BUILD SUCCESSFUL，`LINT_EXIT=0`，0 errors（42 warnings 与基线不变）；CI run 33088924441（headSha=`5d026536…`）conclusion=**success**，lint/APK/上传全绿。
- [V] **assemble 不跑 lint 约定入 CLAUDE.md**（commit `4afef26`，Common Commands lint 段）——改 Kotlin/Java 后推送前必须跑 `:app:lint`。
- [V] **触摸诊断插桩在位**——本会话读过 `PedalOverlayView.kt` 全源（DOWN 布局对比 / 全 action 决策日志 / MOVE 双条件节流 / findPointerIndex==-1 无条件打）且 lint 编译过；commit `18c8d87`。
- [?] **透传 hook 无日志 + 拦截白名单两条约定已入 CLAUDE.md**（`f858cc3` / `a01a6ec`，git log 可查）。
- [?] **插桩版 APK 已交付用户**（8-27 22:35 `app-release.apk`，10429601 字节）——继承上会话，用户安装状态未复核。
- [V] **工作区**：干净，`main` 与 `origin/main` 同步（`git status --short --branch -uall`）。

### 验证输出（本次交接 run）
```
git status --short --branch -uall → ## main...origin/main（干净同步）
./gradlew :app:lint → BUILD SUCCESSFUL in 2s，LINT_EXIT=0
gh run view 33088924441 → {"conclusion":"success","status":"completed"}，Run lint step success
```

## 3. 决策与理由

- **触摸 bug 不盲修，插桩拿数据** [?]（继承）——index/id 层修复已失败一次；穷举静态推演到极限无解。否决：继续猜根因改代码。
- **getRawY 修复用 SDK guard + 数学等价，不提 minSdk、不用 @Suppress** [V]——guard 让新设备走官方 API、老设备走等价式，两路语义一致；提 minSdk 是项目决策不倒逼于 lint；@Suppress 掩盖未来同类错误。helper 扩展函数两处调用点共享，lint 认识 SDK_INT≥Q guard。
- **proxy_shift_up/down 保留 hook 只删日志** [?]（继承）——卸载后用户切配置 hook 不重装，挡位失效。
- **native `g_throttle_active` 语义暂不改** [?]（继承）——候选修复未经日志证实，改动风险面大。等数据。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增
- [X] "本地 assembleRelease 绿 = CI 绿" → assembleRelease **不跑 lint**；`getRawY(pointerIndex)`（API 29）自 `2b5997c` 起让 CI 连红 20+ run 无察觉 [V]。已修（`5d02653`），约定已入 CLAUDE.md。**以后改 Kotlin/Java 推送前必须跑 `:app:lint`**。

### 触摸 bug（上次会话）
- [X] "id 跟踪 + findPointerIndex 能修多指漂移" → 修复 `2b5997c` 后用户实测仍复现。**不要再提同类 index/id 层修复**——根因不在此层。
- [X] "静态审查能定位触摸根因" → 穷举六类机制推演全部无法自洽产生现象。必须走日志数据。
- [X] "用户发来的 log 里能找到触摸现场" → 插桩前版本 log 触摸数据为零。复现前必须先装插桩版。
- [X] "proxy_shift_up 打日志无副作用" → 21 分钟 18810 条洪水。已修，透传 hook 禁止无差别 LOGI 已入 CLAUDE.md。

### 从旧 HANDOFF 搬运
- [X] 全 so bl/b 调用扫描直接拿 dump.cs 的 RVA 匹配文件偏移 → 地址域混用系统性零命中（file = VA−0x4000）；浮点 imm12 需 <<2；双字拷贝漏报。三坑固化 MODULE_ABS_NOTES §4。
- [X] "TractionControlDynamicAssist 是 AI 车的 TCL 管理器" → 仅玩家车每帧执行（playercar 0x9C 门控）。
- [X] "escFilter 制动量与侧偏角 β 成正比" → 实为 min(escFactor, 2000/BFT)，β 只做触发判据。
- [X] "IRDSPlayerControls.tractionControl (0x38) 驱动油门斜率调制" → 死字段；真实调制在 CarControllerMobile 比较 slipRatio ≥ 0.2。
- [X] math 公式内放中文 / 表格内裸竖线 / 行内公式跨行 / operatorname → GitHub 渲染坑，已固化持久记忆。
- [X] "HandleABS 被内联"推断方式 → hook 无日志 ≠ 内联；死活判定须全 so bl/b 扫描（注意地址域）。
- [X] "AI 车经 is_player_controller 过滤天然豁免" → AI 车 playerControls (0x108) 可能非空；拦截类 hook 一律白名单，已入 CLAUDE.md。
- [X] "油门修复成功说明 is_player_controller 判据可靠" → 判据可靠与否看调用面是否全量覆盖。
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

- ⚠️ **多指触摸 bug 未解**——现象见 §2（用户原话）。插桩已就位，等复现日志。三个候选根因待裁决：(a) 意外事件（DOWN/CANCEL 重排）(b) 坐标混入他指 (c) pairip relayout 布局漂移；附加链：reset→游戏接管抽搐（native `g_throttle_active` 被清 → FixedUpdate hook 停写 → 游戏原生输入接管 → MOVE 夺回）。
- ⚠️ TC 开关位被每帧重算 [?]——`tclEnable` 被 TractionControlDynamicAssist 覆写；模块走 hook TractionFilter 入口 + 白名单实测生效。"游戏内设置关 TC 是否生效"未实测。
- ⚠️ 游戏内 ABS 设置对物理无效 [?]——设置链终点死字段，模块才有真开关。
- ⚠️ is_player_controller 判据边界 [?]——setter 透传宽松过滤可用；拦截类 hook 必须白名单（CLAUDE.md 已立约）。
- ⚠️ .rodata 常数不可直接改 [?]——TC 补偿系数 −0.85 (0x929E7C)、ABS 阈值 0.15 (0x929A54) 在只读段。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化晚 ~2s [?]；重新开始后 proxy_player_controls_update 每 ~2s 一次 [?]。
- ⚠️ LSPosed 下 Remote Preferences/Files 只读 [?]；游戏进程对模块包不可见 [?]——用 setComponent 显式广播。
- ⚠️ BillingHook 在 NPatch 模式永远失败 [?]——解锁靠 native hook。
- ⚠️ flyme 后台白名单 [?]；miuix TopAppBar spring [?]；广播 JSON 不含 position [?]；miuix 无 LinearProgressIndicator [?]；OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]。
- ⚠️ GitHub 公式/Mermaid 渲染坑 [?]——写前先读持久记忆 github-math-rendering-pitfalls、mermaid-chart-pitfalls。
- ⚠️ IL2CPP ARM64 扫描三坑 [?]——死方法结论必须双证据交叉验证。
- （原"lint NewApi 拦 minSdk 26 下高版本 API [?]"已证实并修复，移入 §4，不再搬运。）

## 6. 下一步（有序）

1. **等用户回传复现日志**——用户需 force-stop 后安装插桩版 APK、**确认日志开关开启**、复现"一手踏板+一手点空白"、导出日志发回。
2. **分析日志裁决三个候选根因**——(a) 跳变瞬间 action 序列（有无意外 DOWN/CANCEL/POINTER 重排）；(b) MOVE 值链 rawY/idx 是否混入他指坐标；(c) DOWN 布局对比是否显示 pairip relayout 漂移（onScreen ≠ cfgTop）。
3. 若日志显示 reset 后游戏接管抽搐 → 评估 native 写入策略：踏板功能开启期间每帧强制写（写 0 也是写），仅 pedalMode=OFF 停写。
4. 若 (c) 证实布局漂移 → 评估运行时 getLocationOnScreen 做换算基准（权衡 pairip 漂移 vs 配置稳定性）。

## 7. 留给用户的开放问题

- 复现日志回传后：跳变瞬间日志显示的 action 序列是什么？（决定走根因 a/b/c 哪条）
- 用户测试时游戏原生踏板按钮是隐藏还是显示状态？（影响"游戏原生输入接管"假设权重）