# HANDOFF — 读全文再开始干活

生成时间: 2026-08-27T21:57+08:00 · Git HEAD: `f49af03`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `f49af03` (2026-08-27)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f49af03`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见下方标记
- 先读: `HANDOFF.md` + `CLAUDE.md` + `TECHNICAL_ANALYSIS.md`（§1 方法论 + §2 ABS 篇 + §3 TC/ESC 篇）+ `MODULE_ABS_NOTES.md`（ABS+TC 工程笔记）

## 1. 当前目标

TC（牵引力控制）深度反汇编分析与技术报告**已完成并提交推送**（`TECHNICAL_ANALYSIS.md` §3 车辆动力学篇成篇）。多指触摸修复（commit `2b5997c`）**用户测试不成功**，仍是当前头号任务。

## 2. 已验证状态 — 工作实际停在哪

- [V] **TC/ESC/转向辅助篇成篇** — `git show f49af03 --stat`（2 commits：9793120 + 4fac57e）。§3 共 12 小节 + 附录 C/D，19 Mermaid + 20 math 块，校验脚本（/tmp/abs-analysis/check_doc.py）输出"全部通过"。
- [V] **TC 控制律闭合** — `τ' = τ·(1 − 0.85·smoothstep(clamp01(0.55W−1)))`；执行体 `TractionFilter` (0x1A64CE4)，由 `carController`+0xBC 调用；削减上限 85%、零时间常数、一挡豁免、生效区间约 3.6–79 km/h（> 22 m/s 被 `TractionControlDynamicAssist` 每帧强制关闭）。全部细节见 TECHNICAL_ANALYSIS.md §3。
- [V] **车辆级 TC/ESC/SteerHelp 全部是活代码** — 与 ABS 的"死车辆级层"（§2.2）形成对照；三者共享 carController 管线（TractionFilter→SteerHelp→escFilter）与轮级感知（slipRatio/slipAngle 均出自 RoadForce）。
- [V] **设置链分级结论** — ESC/SteerHelp 完整有效（`escEnable` 唯一写入者 = `SetPlayerSettings`）；TC 参数链（tcl=0.45/tclMinSpd=1.0，双字传递）有效，但 `tclEnable` 开关被每帧重算器覆写，游戏内关 TC 能否生效 [?] 待实测。
- [V] **模块已有 TC hook** — `pedal_hook.c` 的 `proxy_traction_filter`：TC 关闭时 hook 入口直接返回原始 accel，绕过覆写陷阱；与补偿合成数学兼容（Δ=0 → τ'=τ）。README 的 TC 功能描述准确。
- [V] **修正旧扫描脚本两个系统性 bug** — ① 地址域混用（代码段 file = VA−0x4000，旧 find_bl.py 直接拿 RVA 匹配文件偏移 → 调用扫描全错；修正后复核 HandleABS 仍零调用者，ABS 死方法结论保住）；② 浮点 ldr/str imm12 需 <<2。③ 补充双字 `ldr d0`/`stur d0` 漏报（SetPlayerSettings 双字写 TCLSlip+TCLminSPD）。已固化 MODULE_ABS_NOTES.md §4 + 持久记忆 il2cpp-arm64-scan-pitfalls。
- [V] **持久记忆新增** — il2cpp-arm64-scan-pitfalls（三坑 + 交叉验证要求）。
- [X] **多指触摸修复不成功** — commit `2b5997c` 的 findPointerIndex/active pointer 跟踪未解决，用户明确反馈"依然存在问题"。头号未解任务。
- 工作区: 干净。本次 4 个切片已提交推送（9793120 工作 / 4fac57e 工作修正 / f49af03 持久文档 / 本 handoff）。

### 验证输出（本次交接 run）
```
python3 /tmp/abs-analysis/check_doc.py → 退出码 0 → "全部通过"
（公式内中文/表格裸竖线/跨行行内公式/operatorname 四类检查）
git push → 4216a65..f49af03 main → main
```

## 3. 决策与理由

- **TC 篇取号 §3（追加式）** [V]——ABS 是 §2，TC/ESC 成篇取下一个可用编号；空气动力学仍是占位不占号。否决：预写死编号——用户明确要求顺序随内容动态联动。
- **TC 篇含 ESC/SteerHelp** [V]——三者共享 carController 管线与感知字段（SteerHelp 写 driftAngle、escFilter 读），拆开写会丢失管线级结论；正好填满篇章标题"TC / ESC / 转向辅助"。
- **工程笔记补 proxy_traction_filter 对齐** [V]——发现模块既有 TC hook 后，把 §5.2 的"理论路径"改为"现行实现 + 原理注解"，避免下会话误以为关 TC 是未做功能。
- **"设置链有效"分级表述** [V]——ESC/SteerHelp 无保留成立；TC 开关位标 [?]（覆写事实 [V]、最终效果未实测）。不把未实测结论写成事实。

## 4. 失败的尝试 — 不要再试

> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本次会话新增（TC 分析）
- [X] 全 so bl/b 调用扫描直接拿 dump.cs 的 RVA 匹配 `文件偏移 + imm×4` → 系统性零命中。代码段文件偏移 = VA − 0x4000，必须换算同一地址域。修正后 HandleABS 仍零调用者（ABS 死方法结论靠 proxy hook 无日志交叉验证保住，但扫描本身当时是错的）。
- [X] 浮点 ldr/str 的 imm12 直接当偏移 → 大量误报（imm12=0x34 实为偏移 0xD0 = inputClutch）。实际偏移 = imm12 << 2；ldrb/strb scale=1 不受影响。
- [X] 单 float 偏移扫描漏报双字拷贝 → 差点误判"TC 设置链断裂"。SetPlayerSettings 用 `ldr d0`/`stur d0` 一条指令写 TCLSlip+TCLminSPD 两个 float。
- [X] "TractionControlDynamicAssist 是 AI 车的 TCL 管理器" → 调用点（carModifier.Update+0xAC）之前有 playercar (0x9C) 门控，仅玩家车每帧执行，AI 车不经过。修正见 §3.10。
- [X] "TC 设置链无保留完整有效" → tclEnable 有 3 处每帧覆写（TractionControlDynamicAssist），开关位被重算；参数链（TCLSlip/TCLminSPD）有效。
- [X] "escFilter 制动量与侧偏角 β 成正比" → 实为 min(escFactor, 2000/BFT)，β 只做触发判据；escFactor 是强度上限。
- [X] "IRDSPlayerControls.tractionControl (0x38) 驱动油门斜率调制" → 死字段（全类零读者）；真实斜率调制在 CarControllerMobile 直接比较 drivetrain.slipRatio (0xCC) ≥ 0.2。
- [X] math 公式内放中文（\text{默认}/\text{释放斜率}/\text{度}/\max_{\text{驱动轮}}）→ KaTeX 坑，改 \mathrm{}/\text{英文} 或移出公式。已固化持久记忆。
- [X] 表格单元格内行内公式放裸竖线（max(|a|,|b|)）→ 破坏表格列解析，改 \lvert\rvert。
- [X] 行内公式 $`...`$ 跨行 → GitHub 不渲染，合并单行。
- [X] 行内公式反引号误写为 \`（反斜杠+反引号，13 处）→ $`..`$ 配对破坏（179≠192），sed 全局修复后 192=192。

### 从旧 HANDOFF 搬运
- [X] "HandleABS 被内联到 carController"推断方式 → 证伪：hook 无日志 ≠ 被内联，可能是死方法。判断方法死活须做全 so bl/b 目标解码扫描（且注意地址域换算）。
- [X] 只修 operatorname 宏白名单 → 根因是 Markdown 预处理破坏 $..$ 内的下划线/花括号，与宏白名单是两层独立防线。行内 $`..`$、块 ```math。
- [X] 把"当前正在写的篇章"当成"文档全部范围" → TECHNICAL_ANALYSIS.md 是全引擎多篇章文档，编号追加式。
- [V] 只写 absEnable=false 不写 per-wheel usesABS=false → ABS 仍工作——唯一有效门控是 per-wheel usesABS。
- [V] 后台 pthread 调 Unity API / 主线程 Handler.postDelayed 调 il2cpp_runtime_invoke / 直接调 SetActive RVA + NULL MethodInfo* → 崩溃。不要再试。
- [V] dlopen("libil2cpp.so") → LSPosed 独立 linker namespace 失败，改用 ELF 符号查找。
- [V] Component.get_gameObject() RVA 直接调用 → 返回 this 非 GameObject。
- [V] Transform.Find via il2cpp_runtime_invoke / 直接调 RVA + NULL MethodInfo* → 崩溃/不稳定。
- [V] GameObject.Find 作为唯一路径 → 重新开始后返回 NULL。
- [V] 递归遍历每帧执行 / + GameObject.Find 双路径每帧 → 卡顿 / 误隐藏按键。
- [V] proxy_fixed_update is_player 块外调 hide_pedals_tick → 重新开始后 is_player 返回 false。
- [?] event.rawY 不做 active pointer 跟踪 → 多指漂移。findPointerIndex 修复用户测试不成功，需重新排查。
- [V] Path.op(INTERSECT)/(DIFFERENCE) → 圆角缝隙/弧线折角。
- [V] alphaOf(ratio)=ratio*255 语义反 / borderPaint 用 WHITE 不读 alpha / drawRoundRect 边框不内缩 / clipPath 裁到 (0,0,w,h) → 绘制类坑。
- [V] LAST_TOUCHED 每次 MOVE 更新 → 先按的手指微动夺回优先。
- [V] adb install -r 覆盖安装时旧版运行 → force-stop 再安装。
- [V] awaitLsposedSettled → NPatch 太慢；等"非 Connecting" → App 1.5s 兜底；等 Connected 5s 超时 → NPatch binder 先到；NONROOT 立即写缓存 → LSPosed 后到补不上。
- [V] clearAll 调 App.clearService() / 保留 onResume 重新检测 / onServiceDied 不检查身份 / 去掉 evaluate() service 检查 / onServiceBind 不区分框架 / 弹窗并行 / hasShownDialog 只弹一次 → 激活检测各类坑，均已修。
- [V] kkgithub.com 404 / mirror.ghproxy.com DNS 失败 → 用 gh-proxy.com。
- [V] CHUNK_SIZE=256K → TransactionTooLargeException / ANR。
- [V] 手动 rememberNavigationEventDispatcherOwner → 弹窗收不到返回键。
- [V] LSPosed 下 ContentProvider IPC / Remote Preferences commit() → Unknown authority / UnsupportedOperationException。

## 5. 已知坑

- ⚠️ 多指触摸修复不成功 [V]——findPointerIndex 修复后仍存在踏板值漂移，需重新排查根因（头号任务）。
- ⚠️ TC 开关位被每帧重算 [V]——`tclEnable` 被 TractionControlDynamicAssist（仅玩家车）覆写：每帧先置 true，高速 > 22 m/s 或条件 A 时置 false。模块关 TC 已用正确路径（hook TractionFilter 入口），但"游戏内设置关 TC 是否生效"未实测 [?]。
- ⚠️ 游戏内 ABS 设置对物理无效 [V]——设置链终点死字段（absEnable 无运行时读者），模块才有真开关。
- ⚠️ .rodata 常数不可直接改 [V]——TC 补偿系数 −0.85 (0x929E7C)、ABS 阈值 0.15 (0x929A54) 均在只读段。
- ⚠️ flyme 后台白名单限制 [?]——非白名单应用 checkAllowBackgroundLocked 返回 DISABLED。
- ⚠️ miuix TopAppBar spring 不跟随 fraction / 内部自带状态栏 inset [?]。
- ⚠️ 广播 JSON 不含 position 字段 [?]——从本地 externalFilesDir 合并。
- ⚠️ miuix 无 LinearProgressIndicator [?]——用 Text 显示百分比。
- ⚠️ lint NewApi 拦 minSdk 26 下高版本 API [?]——照搬 KernelSU 注意 minSdk 差异。
- ⚠️ OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]——introSound 用真 AudioSource.set_volume。
- ⚠️ 计时赛 IRDSUIMobileControls 初始化比正赛晚 ~2s [V]；计时赛重新开始后 proxy_player_controls_update 每 ~2s 才调用一次 [V]。
- ⚠️ LSPosed 下 Remote Preferences/Files 在 Hook 进程只读 [V]；游戏进程对模块包不可见 [V]——用 setComponent 显式组件广播。
- ⚠️ BillingHook 在 NPatch 模式下永远失败 [V]——解锁靠 native hook。
- ⚠️ GitHub 公式/Mermaid 渲染坑 [V]——已全部修复并固化持久记忆；再写带公式/图表的 GitHub 文档前先读 github-math-rendering-pitfalls、mermaid-chart-pitfalls。
- ⚠️ IL2CPP ARM64 扫描三坑 [V]——地址域混用、浮点 imm12 缩放、双字拷贝；已固化 MODULE_ABS_NOTES.md §4 + 持久记忆 il2cpp-arm64-scan-pitfalls；死方法结论必须双证据交叉验证。

## 6. 下一步（有序）

1. **重新排查多指触摸 bug** — 用户明确反馈 active pointer 跟踪修复不成功。需重新复现问题、抓 logcat、分析实际 pointer 行为（PedalOverlayView）。
2. **实测验证游戏内 TC 开关** [?] — §3.7 存疑项：enableTCL 被 TractionControlDynamicAssist 每帧覆写，游戏内关 TC 是否真实生效需实机确认；顺带实测 TC 参数（tcl 0.45）调整手感。
3. **计时赛延迟优化（可选）** — 当前 0.5s 延迟可接受但不够好。
4. **真机验证 NPatch 未安装路径** — 未装 NPatch 点卡片弹 Toast 路径仍未验证。
5. **清理 ConfigProvider 无用代码** — pushGameLog/readGameLog 已被广播方案替代。
6. **全量替换裸 `Log.*` 为 `Logger.*`**
7. **继续排查 janky 根因** — R8 映射文件对比。
8. **正规化 carController offset** — 当前固定偏移 0xA8，可改配置传递。
9. **V10 第二阶段（可选）** — 游戏内引擎声浪。
10. **ABS 增强功能（可选）** — 见 MODULE_ABS_NOTES.md §2：ABS 强度旋钮、ABS 灯（读 pulseBrakes）、per-wheel 选择性 ABS。
11. **技术解析后续篇章（可选）** — 空气动力学与 DRS（下一个可用编号 §4）/ 传动与多线程轮子物理（占位见 TECHNICAL_ANALYSIS.md，编号追加式）。

## 7. 留给用户的开放问题

- 多指触摸 bug 的实际复现条件和根因是什么？用户说"依然存在问题"但未描述具体现象。
- 游戏内 TC 开关实际能否关掉 TC？（tclEnable 覆写链的实测）用户是否需要"TC 强度调节"功能（游戏设置链已通，模块增益有限）？
- 计时赛 0.5s 延迟是否需要进一步优化？
- V10 游戏内引擎声浪是否继续实现？