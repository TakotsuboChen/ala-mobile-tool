# HANDOFF — 读全文再开始干活

生成时间: 2026-08-27T22:18+08:00 · Git HEAD: `669be92`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `669be92` (2026-08-27)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `669be92`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md` + `MODULE_ABS_NOTES.md`（ABS+TC 工程笔记，§5.2 白名单规则）+ `TECHNICAL_ANALYSIS.md`（§1 方法论 + §2 ABS + §3 TC/ESC）

## 1. 当前目标

**关 TC 误伤 AI 车已修复并实机验证通过**（白名单判定），已提交推送。头号未解任务回到**多指触摸 bug**（用户此前反馈 findPointerIndex 修复不成功）。

## 2. 已验证状态 — 工作实际停在哪

- [V] **TC 误伤 AI 车根因与修复** — commit `74f5ee5`（`git show 74f5ee5 --stat`）。根因：`proxy_traction_filter` 用 `is_player_controller`（0x108 字段探测）做拦截判定，AI 车该字段可能非空 → 关 TC 时 19 辆 AI 的 `TractionFilter` 一并被跳过 → AI 失去 TC 保护打滑失控。`TractionFilter` 是每车每物理帧必经路径，误判必现。
- [V] **修复方案：拦截类 hook 一律白名单比对** — 新增 `is_target_player_car()`（`this == g_player_controller`，由只挂玩家车的 `IRDSPlayerControls.Update` 设置）。改动四处：`proxy_traction_filter`（主修）、`proxy_car_controller`、`proxy_handle_abs`、`proxy_fixed_update` ABS 块（同根因 ABS 波及面顺带修）；`pedal_uninstall_hooks` 补清 `g_player_controller`。`proxy_player_controls_update` 不改——写入目标由组件挂载天然保证。
- [V] **实机验证通过** — 用户实测：关 TC 后 AI 正常跑（"AI 都正常跑了"），玩家车 TC 关闭仍生效。
- [V] **构建/安装链** — `./gradlew :app:assembleRelease -q` → 成功（APK 22:10 产出）；`adb install -r` → Success；`dumpsys package tools.alamobile.mod` lastUpdateTime 22:11:35。
- [V] **ABS 开关同样不再影响 AI** — 与 TC 同根因，本次一并白名单化；层 3（`proxy_player_controls_update`）写入目标由组件挂载结构保证，保持原判据。
- [V] **长期约定已入 CLAUDE.md** — commit `669be92`：拦截类 hook 必须白名单判定，禁用 `is_player_controller` 做拦截（Key Code Conventions 新条目）。
- [V] **工程笔记同步** — MODULE_ABS_NOTES.md §5.2（白名单规则 + 踩坑记录替换"天然豁免"错误断言）、§1 层 1 代码引用更新。
- 工作区: 干净（仅 `.handoffs/20260827221758-handoff.md` 未跟踪，随本 handoff 提交）。

### 验证输出（本次交接 run）
```
git push → 2625b44..74f5ee5（工作切片）→ 74f5ee5..669be92（CLAUDE.md）→ main
实机测试（用户）: 关 TC → AI 正常 + 玩家车 TC 关闭生效 → 通过
```

## 3. 决策与理由

- **拦截类 hook 一律白名单实例比对** [V]——AI 车误判实证（实测 AI 失误率暴增）。否决：继续用 `is_player_controller` 并加固探测——字段探测无法杜绝误报，白名单来源已实机验证。
- **ABS 波及面顺带修复** [V]——与 TC 同一根因、同一判定函数，不修则用户关 ABS 测试又是一轮往返。否决：只修用户报告的 TC——同类 bug 零容忍，单点修复会重演"修复不成功"往返。
- **`proxy_player_controls_update` 判据不改** [V]——写入目标 `car_inputs` 来自只挂玩家车的组件，结构保证强于字段探测。否决：统一白名单——无增益。
- **`proxy_fixed_update` 的 `hide_pedals_tick` 留在宽松判定内** [V]——计时赛 `PlayerControls.Update` 可能 2s 才一次，隐藏踏板依赖 FixedUpdate 50Hz 路径，换白名单会在加载早期破坏隐藏。
- **白名单加载早期为 NULL 的代价可接受** [V]——`IRDSPlayerControls.Update` 首跑前玩家车短暂保持默认 TC/ABS（约一个物理帧 20ms），不可感知；换来 AI 绝对不被波及。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增（TC 误伤 AI）
- [X] "AI 车经 `is_player_controller` 过滤天然豁免"（旧 HANDOFF/工程笔记原断言）→ 证伪：AI 车的 `playerControls` (0x108) 可能非空，关 TC 时 AI 全体被误拦。修正：拦截类 hook 一律白名单（`is_target_player_car`），已固化 CLAUDE.md + MODULE_ABS_NOTES §5.2。
- [X] "油门修复成功说明 `is_player_controller` 判据可靠" → 证伪：setter 路径 AI 未必经过，误判不可观测；`TractionFilter` 是每车必经路径才暴露。判据可靠与否要看调用面是否全量覆盖。

### 从旧 HANDOFF 搬运
- [X] 全 so bl/b 调用扫描直接拿 dump.cs 的 RVA 匹配文件偏移 → 地址域混用系统性零命中（代码段 file = VA−0x4000）；浮点 imm12 需 <<2；双字 `ldr d0`/`stur d0` 拷贝漏报。三坑已固化 MODULE_ABS_NOTES §4。
- [X] "TractionControlDynamicAssist 是 AI 车的 TCL 管理器" → 仅玩家车每帧执行（playercar 0x9C 门控）。
- [X] "escFilter 制动量与侧偏角 β 成正比" → 实为 min(escFactor, 2000/BFT)，β 只做触发判据。
- [X] "IRDSPlayerControls.tractionControl (0x38) 驱动油门斜率调制" → 死字段；真实调制在 CarControllerMobile 直接比较 slipRatio ≥ 0.2。
- [X] math 公式内放中文 / 表格内裸竖线 / 行内公式跨行 / 反引号误写 \` → GitHub 渲染坑，已固化持久记忆。
- [X] "HandleABS 被内联到 carController"推断方式 → hook 无日志 ≠ 被内联，可能死方法；死活判定须全 so bl/b 扫描（注意地址域）。
- [X] 只修 operatorname 宏白名单 → 根因是 Markdown 预处理破坏 $..$ 内下划线/花括号，两层独立防线。
- [X] 把"当前正在写的篇章"当成"文档全部范围" → TECHNICAL_ANALYSIS.md 是多篇章追加式。
- [V] 只写 absEnable=false 不写 per-wheel usesABS=false → ABS 仍工作；唯一有效门控是 per-wheel usesABS。
- [V] 后台 pthread 调 Unity API / 主线程 Handler.postDelayed 调 il2cpp_runtime_invoke / 直接调 SetActive RVA + NULL MethodInfo* → 崩溃。
- [V] dlopen("libil2cpp.so") → LSPosed namespace 失败，改 ELF 符号查找。
- [V] Component.get_gameObject() RVA 直接调用 → 返回 this 非 GameObject。
- [V] Transform.Find via invoke / GameObject.Find 唯一路径 / 递归遍历每帧 → 崩溃/NULL/卡顿。
- [V] proxy_fixed_update is_player 块外调 hide_pedals_tick → 重新开始后 is_player 返回 false。
- [?] event.rawY 不做 active pointer 跟踪 → 多指漂移。findPointerIndex 修复用户测试不成功，需重新排查。
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

- ⚠️ 多指触摸修复不成功 [V]——findPointerIndex 修复后仍存在踏板值漂移（用户反馈），头号任务，需重新复现+抓 logcat。
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

1. **重新排查多指触摸 bug**（头号）——用户此前反馈 findPointerIndex / active pointer 跟踪修复不成功。先向用户确认具体现象（漂移？跳变？哪块 overlay？），再抓 logcat 复现分析（PedalOverlayView）。
2. **实测游戏内 TC 设置开关** [?]——§3.7 存疑：enableTCL 被每帧覆写，游戏内关 TC 是否生效需实机确认（模块路径已实测生效，此项只关游戏自带设置）。
3. 计时赛延迟优化（可选）——当前 0.5s 延迟可接受。
4. 真机验证 NPatch 未安装路径（未装时点卡片弹 Toast）。
5. 清理 ConfigProvider 无用代码（pushGameLog/readGameLog 已被广播替代）。
6. 全量替换裸 `Log.*` 为 `Logger.*`。
7. 排查 janky 根因（R8 映射文件对比）。
8. 正规化 carController offset（固定 0xA8 → 配置传递）。
9. V10 第二阶段（可选）——游戏内引擎声浪。
10. ABS 增强（可选）——强度旋钮、ABS 灯（读 pulseBrakes）、per-wheel 选择性 ABS（MODULE_ABS_NOTES §2）。
11. 技术解析后续篇章（可选）——空气动力学与 DRS（§4）/ 传动与多线程轮子物理（编号追加式）。

## 7. 留给用户的开放问题

- 多指触摸 bug 的具体现象与复现条件？（单手双指？踏板+挡位同按？值漂移还是跳变？）
- 用户是否需要"TC 强度调节"功能（游戏设置链已通，模块增益有限）？
- 计时赛 0.5s 延迟是否需进一步优化？V10 游戏内声浪是否继续？