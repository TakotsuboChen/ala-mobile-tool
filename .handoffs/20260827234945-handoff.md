# HANDOFF — 读全文再开始干活

生成时间: 2026-08-27T23:28+08:00 · Git HEAD: `f858cc3`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `f858cc3` (2026-08-27)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f858cc3`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` 全文 + `CLAUDE.md`（Key Code Conventions 两条 hook 约定）+ §2 的现象描述（用户原话，上次丢过一次，禁止再丢）

## 1. 当前目标

**多指触摸 bug（头号任务）已进入"插桩完毕、等用户复现回传日志"状态**。本会话完成诊断插桩 + 删日志洪水，未再改任何修复逻辑——等数据，不盲修。

## 2. 用户补充的现象（用户原话，勿再丢失）

> 一只手按着踏板，另一只手点屏幕上别的空白处，**行程填充就会漂到 100%**，**反复点空白处就反复抽搐**。

- 环境：**共存版（NPatch）**，pairip 壳确认存在（log 有 `com.pairip.application.Application@6af64a2`）[V]
- **SINGLE 和 DUAL 两种模式都漂**（用户 AskUserQuestion 确认）
- findPointerIndex 修复（`2b5997c`，8月26 21:50）后实测不成功：用户 8月26 22:27 会话复现（上一会话 handoff `a01a6ec` §5 记录的结论 [?]——修复版是否已装入 22:27 会话的 APK 无直接证据，但时间上来得及且上会话已与用户确认）
- 用户上一轮发的 log：`/mnt/d/Downloads/ala_tool_log_20260826_223006.txt`（23957 行）/ `ala_tool_log_20260826_223333.txt`（17597 行），Windows 原路径 `D:\Downloads\`

## 2b. 已验证状态 — 工作实际停在哪

- [V] **两个 log 里没有触摸数据**——PedalOverlayView 当时无任何日志输出（grep "pedal" 全是 `pedalMode=` 配置行）；native 段只有 `proxy_shift_up called`（18810 条，8月26 中午 12:51–13:12 会话，`enableManualShift=false` 也打）；8月26 22:27 复现会话（pid=24030，SINGLE+enableTc）连 native 日志都没有。**这批 log 对触摸 bug 零价值，诊断必须先插桩再复现。**
- [V] **onTouchEvent 静态审查自洽**——id 跟踪 + findPointerIndex 实现无 bug；穷举推演（事件流重排 / ViewGroup split / rawY 语义 / pairip relayout / 游戏原生按钮 / native active 语义）无一条能自洽产生"点空白→100%"。根因在"id 跟踪正确"假设覆盖不到处。
- [V] **native active 语义是真实存在的抽搐链**——`pedal_set_throttle_value(0)` 清 `g_throttle_active`（pedal_hook.c:815-840）→ FixedUpdate hook 停止每帧覆写 → **游戏原生输入接管油门**；指 A 下次 MOVE 又夺回。任何意外 reset 都触发此链。是否是当前根因待日志裁决。
- [V] **overlay 架构事实**——overlay 是 addView 进游戏 decorView 的 `android.R.id.content`（非独立 window，OverlayManager.kt:84）；同 window 内 ViewGroup split 分发，每个 view 只收自己 pointer 的事件。
- [V] **触摸诊断插桩已落地**（commit `18c8d87`）——DOWN 打布局对比（getLocationOnScreen vs 配置 topPx/cfgH，证伪/证实 pairip relayout）；POINTER_DOWN/UP/UP/CANCEL 全打带决策；MOVE 节流（t 变化>2% 或 500ms 心跳）记 rawY→relY→t→raw/mapped 值链；findPointerIndex==-1 无条件打。走 logEnabled 门控。
- [V] **日志洪水已修**（commit `e3d9815`）——proxy_shift_up/down 的 LOGI 删除，纯透传保留。
- [V] **构建通过**——`./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`（8月27 22:35，10429601 字节）。adb 设备 381QYFCN22B9A offline，安装由用户执行。
- [V] **透传 hook 无日志约定已入 CLAUDE.md**（commit `f858cc3`，Key Code Conventions）。
- 工作区: 干净（仅 `.handoffs/20260827232800-handoff.md` 未跟踪，随本 handoff 提交）。

### 验证输出（本次交接 run）
```
git log --oneline -5 → f858cc3(docs) 18c8d87(feat 插桩) e3d9815(fix 洪水) a01a6ec(docs handoff) 669be92(docs)
git push × 3 → a01a6ec..f858cc3 main
./gradlew :app:assembleRelease -q → 退出码 0，APK 22:35 产出
```

## 3. 决策与理由

- **不再盲修，插桩拿数据** [V]——id 跟踪修复已失败一次（用户实测复现），本会话穷举静态推演到极限无解。否决：继续猜根因改代码——每次盲修都是一轮低效往返（用户原话："一来一回不能现场调试效率就是这么低"）。
- **MOVE 日志节流用值变化+时间双条件** [V]——>2% 行程或 500ms 心跳。否决：每条 MOVE 都打——60Hz 事件会重演日志洪水；心跳保留"手指没动值在变"的竞态侦测。
- **proxy_shift_up/down 保留 hook 只删日志** [V]——hook 卸载后若用户游戏内切配置开手动挡，hook 不重装 → 挡位失效。否决：按 enableManualShift 条件装 hook——状态同步复杂度不值。
- **native `g_throttle_active` 语义暂不改** [V]——改成"踏板开启期间每帧写（写 0 也写）"是候选修复，但未经日志证实是根因，且改动涉及 Java→native 开关传递，风险面大。等数据。
- **插桩只加在 PedalOverlayView** [V]——GearShiftView 只处理 DOWN（点击换挡）无多指跟踪；用户现象聚焦踏板填充。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增（触摸 bug）
- [X] "id 跟踪 + findPointerIndex 能修多指漂移" → 修复 `2b5997c` 后用户实测仍复现（8月26 22:27 会话）。**不要再提同类 index/id 层修复**——根因不在此层。
- [X] "静态审查能定位触摸根因" → 本会话穷举六类机制推演全部无法自洽产生现象 [V]。下一步必须走日志数据。
- [X] "用户发来的 log 里能找到触摸现场" → 修复前版本无插桩，log 里触摸数据为零 [V]。让用户复现前必须先装插桩版。
- [X] "proxy_shift_up 打日志无副作用" → 挂所有车 + enableManualShift=false 也打，21 分钟 18810 条洪水（pedal_hook.c 旧代码）[V]。已修，透传 hook 禁止无差别 LOGI 已入 CLAUDE.md。

### 从旧 HANDOFF 搬运
- [X] 全 so bl/b 调用扫描直接拿 dump.cs 的 RVA 匹配文件偏移 → 地址域混用系统性零命中（代码段 file = VA−0x4000）；浮点 imm12 需 <<2；双字 `ldr d0`/`stur d0` 拷贝漏报。三坑已固化 MODULE_ABS_NOTES §4。
- [X] "TractionControlDynamicAssist 是 AI 车的 TCL 管理器" → 仅玩家车每帧执行（playercar 0x9C 门控）。
- [X] "escFilter 制动量与侧偏角 β 成正比" → 实为 min(escFactor, 2000/BFT)，β 只做触发判据。
- [X] "IRDSPlayerControls.tractionControl (0x38) 驱动油门斜率调制" → 死字段；真实调制在 CarControllerMobile 直接比较 slipRatio ≥ 0.2。
- [X] math 公式内放中文 / 表格内裸竖线 / 行内公式跨行 / 反引号误写 \` → GitHub 渲染坑，已固化持久记忆。
- [X] "HandleABS 被内联到 carController"推断方式 → hook 无日志 ≠ 被内联，可能死方法；死活判定须全 so bl/b 扫描（注意地址域）。
- [X] 只修 operatorname 宏白名单 → 根因是 Markdown 预处理破坏 $..$ 内下划线/花括号，两层独立防线。
- [X] 把"当前正在写的篇章"当成"文档全部范围" → TECHNICAL_ANALYSIS.md 是多篇章追加式。
- [X] "AI 车经 is_player_controller 过滤天然豁免" → AI 车 playerControls (0x108) 可能非空，关 TC 时 AI 全体被误拦；拦截类 hook 一律白名单（is_target_player_car），已固化 CLAUDE.md + MODULE_ABS_NOTES §5.2。
- [X] "油门修复成功说明 is_player_controller 判据可靠" → setter 路径 AI 未必经过；判据可靠与否看调用面是否全量覆盖。
- [V] 只写 absEnable=false 不写 per-wheel usesABS=false → ABS 仍工作；唯一有效门控是 per-wheel usesABS。
- [V] 后台 pthread 调 Unity API / 主线程 Handler.postDelayed 调 il2cpp_runtime_invoke / 直接调 SetActive RVA + NULL MethodInfo* → 崩溃。
- [V] dlopen("libil2cpp.so") → LSPosed namespace 失败，改 ELF 符号查找。
- [V] Component.get_gameObject() RVA 直接调用 → 返回 this 非 GameObject。
- [V] Transform.Find via invoke / GameObject.Find 唯一路径 / 递归遍历每帧 → 崩溃/NULL/卡顿。
- [V] proxy_fixed_update is_player 块外调 hide_pedals_tick → 重新开始后 is_player 返回 false。
- [V] Path.op(INTERSECT)/(DIFFERENCE) → 圆角缝隙/弧线折角。
- [V] alphaOf 语义反 / borderPaint 不读 alpha / drawRoundRect 边框不内缩 / clipPath 裁到 (0,0,w,h) → 绘制坑。
- [V] LAST_TOUCHED 每次 MOVE 更新 → 先按的手指微动夺回优先。
- [V] adb install -r 覆盖安装时旧版运行 → force-stop 再安装。
- [V] awaitLsposedSettled → NPatch 太慢；等"非 Connecting" 1.5s 兜底 / 等 Connected 5s 超时 → NPatch binder 先到；NONROOT 立即写缓存 → LSPosed 后到补不上。
- [V] 激活检测各坑（clearAll 调 clearService / onResume 重新检测 / onServiceDied 不查身份 / 不分框架 / 弹窗并行 / 只弹一次）→ 均已修。
- [V] kkgithub 404 / mirror.ghproxy DNS 失败 → gh-proxy.com。
- [V] CHUNK_SIZE=256K → TransactionTooLargeException / ANR。
- [V] 手动 rememberNavigationEventDispatcherOwner → 弹窗收不到返回键。
- [V] LSPosed 下 ContentProvider IPC / Remote Preferences commit() → Unknown authority / UnsupportedOperationException。

## 5. 已知坑

- ⚠️ **多指触摸 bug 未解** [V]——现象见 §2（用户原话）。插桩已就位，等复现日志。三个候选根因待裁决：(a) 意外事件（DOWN/CANCEL 重排）(b) 坐标混入他指 (c) pairip relayout 布局漂移；附加链：reset→游戏接管抽搐（§2b native 语义）。
- ⚠️ TC 开关位被每帧重算 [V]——`tclEnable` 被 TractionControlDynamicAssist 覆写；模块已用正确路径（hook TractionFilter 入口，白名单）实测生效。"游戏内设置关 TC 是否生效"仍未实测 [?]。
- ⚠️ 游戏内 ABS 设置对物理无效 [V]——设置链终点死字段，模块才有真开关。
- ⚠️ is_player_controller 判据边界 [V]——只可用于 setter 透传宽松过滤与野指针二次校验；拦截类 hook 必须白名单（CLAUDE.md 已立约）。
- ⚠️ .rodata 常数不可直接改 [V]——TC 补偿系数 −0.85 (0x929E7C)、ABS 阈值 0.15 (0x929A54) 在只读段。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化比正赛晚 ~2s [V]；重新开始后 proxy_player_controls_update 每 ~2s 才一次 [V]。
- ⚠️ LSPosed 下 Remote Preferences/Files 在 Hook 进程只读 [V]；游戏进程对模块包不可见 [V]——用 setComponent 显式广播。
- ⚠️ BillingHook 在 NPatch 模式永远失败 [V]——解锁靠 native hook。
- ⚠️ flyme 后台白名单限制 [?]；miuix TopAppBar spring 不跟随 fraction [?]；广播 JSON 不含 position 字段 [?]（从本地合并）；miuix 无 LinearProgressIndicator [?]；lint NewApi 拦 minSdk 26 下高版本 API [?]；OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]。
- ⚠️ GitHub 公式/Mermaid 渲染坑 [V]——写公式/图表文档前先读持久记忆 github-math-rendering-pitfalls、mermaid-chart-pitfalls。
- ⚠️ IL2CPP ARM64 扫描三坑 [V]——死方法结论必须双证据交叉验证（MODULE_ABS_NOTES §4 + 持久记忆）。

## 6. 下一步（有序）

1. **等用户回传复现日志**（用户已领走 22:35 APK `app/build/outputs/apk/release/app-release.apk`）——用户需 force-stop 后安装、**确认日志开关开启**、复现"一手踏板+一手点空白"、导出日志发回。
2. **分析日志裁决三个候选根因**——(a) 跳变瞬间 pedalView 收到的 action 序列（有无意外 DOWN/CANCEL/POINTER 重排）；(b) MOVE 值链里 rawY/idx 是否混入他指坐标；(c) DOWN 布局对比是否显示 pairip relayout 漂移（onScreen ≠ cfgTop）。
3. **若日志显示 reset 后游戏接管抽搐**（pedalView 收到 CANCEL/reset 后游戏内油门跳变）→ 评估 native 写入策略改动：踏板功能开启期间每帧强制写（写 0 也是写），仅 pedalMode=OFF 停写。
4. 若 (c) 证实布局漂移 → 评估改用运行时 getLocationOnScreen 做换算基准（需重新权衡 pairip 漂移与配置稳定性的取舍）。

## 7. 留给用户的开放问题

- 复现日志回传后：跳变瞬间日志显示的 action 序列是什么？（决定走根因 a/b/c 哪条）
- 用户测试时游戏原生踏板按钮是隐藏还是显示状态？（影响"游戏原生输入接管"假设的权重）