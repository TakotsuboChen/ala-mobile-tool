# HANDOFF — 读全文再开始干活

生成时间: 2026-08-29T20:53:58+0800 · Git HEAD: `845d970`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `845d970` (2026-08-29)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `845d970`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `PedalOverlayView.kt` 的 `updateValuesFromPointer` 双源校验注释块（本会话新增，含完整设计动机）+ `PedalOverlayView.kt` 的 `onDraw` KDoc（边框缝隙方案单一事实源）+ `TC_LEVEL_DESIGN.md` §10 v1.5 + `ABS_LEVEL_DESIGN.md` §4 + `.handoffs/20260828235219-handoff.md` §4（死路总账）

## 1. 当前目标
多指触摸 bug（"另一手指按下→踏板瞬间跳满"）已完成定位链条：复现日志分析→数值模型→四路 subagent 深度研究→根因模型（MIUI 输入管线 per-window 加工污染 raw 通道），防御修复（双源交叉校验+源切换）已实装（`845d970`）并经 Takotsubo 自测正常。**当前等待复现用户装新 APK 回传日志**，以裁决污染层（rawX 二分）并证实症状消除。

## 2. 已验证状态 — 工作实际停在哪
- [V] **修复已实装**：commit `845d970`（`PedalOverlayView.kt` +72/-1）。双源校验：raw 通道（`rawYAt`，mRawTransform 管道）vs transform 通道（`getY+getLocationOnScreen`）；分叉 >100px（`MISMATCH_SWITCH_PX`）判污染帧切 y 源继续输出（踏板仍跟手），>20px 记 `RAW_MISMATCH` 全通道日志（rawY/yT/rawX/xT/idx/ptc/loc/screenH+计数，节流 500ms）。新增 `rawXAt` API29 等价式 helper。
- [V] **构建+lint**：`./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL，**0 errors**（44 warnings + 3 hints，基线量级）。注意 `assembleRelease` 不跑 lint，推送前必须 `:app:lint`。
- [V] **Takotsubo 自机实测**：adb install 成功（先 force-stop），用户确认"测试正常，没有因此引入新问题"。
- [V] **复现日志结论**（`/mnt/d/Downloads/ala_tool_log_20260829_122108.txt`，红米 MIUI15 复现用户）：三候选裁决——事件重排 ✗（idx 恒 0/无 NOT_FOUND/无 POINTER_DOWN）、pairip relayout ✗（DOWN 布局三次一致）、**坐标污染 ✓**：`rawY_wrong = rawY_true + (y2 − screenH)`，两段偏移常数 -1080/-1075 且随第二指微动同步变化。污染帧全为 MOVE。
- [V] **双场景模型验证**：单踏板+点空白→间歇跳满（日志实证）；双踏板→**两个行程同时漂满 100%**（用户实测，公式的定量预测吻合——事件级 rawTransform 注入会作用于窗口副本内全部指针）。
- [V] **四路 subagent 研究**（子报告见会话记录，核心结论）：①pairip 出局——8.0.4 是 lite 形态（无 libpairipcore），共存版 `checkLicense` 已 patch 成空方法=死代码，静态 grep 输入 API 零命中；②AOSP 机制穷举排除（split/offsetLocation/transform/globalScaleFactor/resample/palm rejection 均无法产生该形状）；③GameTurbo 参数直达 TP IC 固件（MiCode 源码 `aim_sensitivity`/`tap_stability`/`edge_filter`，网格计算有 `y_resolution−x` 运算——形状同构）；④公开社区零同构案例。
- [V] **GameTurbo 设置 A/B 无效**（复现用户试遍游戏相关设置）→ 设置面不是根因路径；且"无其他小米用户反馈"→ MIUI 通用 bug 假设弱化，环境特异性方向（复现用户设备配置/长按全局手势，BRAK 段第二指长按 2.9s）。
- [V] 演示前提已成立：复现会话（08-29 pid=6154）有 `pedal[` 插桩、零 proxy 洪水——模块更新/NPatch 注入成功。
- [?] ABS 档位 0.80/0.60/0.40 已随 v1.0.2 发布（memory 确认），实机手感未回传。
- 工作区: 干净，`main` 与 origin 同步。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 1m 9s，Lint 0 errors 44w 3h
git push → 0df9739..845d970 main（成功）
adb install -r → Success（设备 381QYFCN22B9A，先 force-stop）
```

## 3. 决策与理由
- **双源校验+源切换，而非值过滤/冻结旧值** [V]——校验的是通路一致性，与运动模式正交：直接按满/快速拉动两源同步变化天然放行（用户质疑后确认的设计核心）；冻结旧值在持续污染下会让踏板卡死，源切换则持续跟手。否决：跳变过滤当主防线（会与真实快速操作打边界仗）。
- **三档 20/100px 阈值** [V]——污染偏移 1080px 级与切换阈值差一个数量级；小分叉（窗口动画瞬态）只记日志不切源（保守：切源是状态变化）。
- **v1 放弃 decorView 层探针** [?]——OnTouchListener 挂 content 只在"无 child 消费"时触发（ViewGroup 分发结构），MOVE 有 touch target 时收不到；真实现需 hook Activity.dispatchTouchEvent，成本推迟到有需要时。
- **移除 isResampled** [V]——编译期 Unresolved（flagged API 未进 API surface），诊断价值次要。
- **坐标源语义澄清** [V]——旧注释"不能用 getY 因 pairip relayout"已被日志证伪（DOWN 布局三次一致），yOnScreen 兼任 relayout 防御+污染 fallback 双角色，KDoc 已订正。
- 复现用户设备=红米 MIUI15（BillingHook classloader 路径实证）；Takotsubo 自机=Flyme——两人设备勿混（已落 memory `multi-touch-reporter-device`）。pairip 结论落 memory `pairip-lite-form-dead-code`。
- 继承：边框缝隙层内不透明终态、ABS v2 双旋钮架构、TC_LEVEL_DESIGN v1.5。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增
- [V] **断言"show taps 圆点不跟随"** → 用户实测纠正+官方文档证实圆点实时跟随 MOVE（"follows you as you move"）。教训：对系统组件行为作断言前先查证，别让错误断言污染推理链。
- [V] **GameTurbo 设置层 A/B 作为验证路径** → 复现用户试遍全部游戏相关设置无效。设置面被排除或深于设置面（组件冻结/pm disable 未做）。
- [V] **MotionEvent.isResampled** → 编译 Unresolved reference（flagged API 剔除 android.jar surface）。不要再引入；诊断用其他通道。
- [V] **`getRawX(pointerIndex)`/`getRawY(pointerIndex)` 直调** → API 29+，minSdk 26 lint 报错。统一走 `rawXAt`/`rawYAt` 等价式 helper。
- [?] **OnTouchListener 探 decorView 层事件** → ViewGroup listener 只在无 child 消费时触发（框架推理，未实测）。要窗口级对照需 hook Activity.dispatchTouchEvent。
- 继承死路全部有效（按上一份标注搬运）：
- [V] 边框缝隙：逐像素半透明下所有"精确互补"方案全缝，over 链数学上无解，必须层内不透明。
- [V] 档位值 0.90 贴 native clamp 上限（若低档 ≥0.9 被静默截断差异化消失）；把未实机口头数值标"定版"。
- [V] 押 p0 主旋钮被推翻；usesABS 恢复漏置位；只写 absEnable 不够（唯一门控 per-wheel usesABS）；只写 TCLSlip=eps 挡死起步；每帧写 ctor 默认≠真值。
- [V] 画笔模式技术成功被用户否决（"只认控制点"）；"换算法任意形状"撞信息论边界；数学误差降低≠可感知。
- [V] 触摸诊断：用 commit 时间推 APK 内容（用运行时指纹）；"导出零数据=模块旧"单因推断；adb force-stop 用户在用设备。
- [V] 手势/测量：滑块 v1/v2 帧数 1/6、"设备性能"归因、GestureAxisBlocker 整向量消费、perfetto shorthand、测量期间手触、miuix 查源码先联网。
- [V] proxy_shift_up 打日志洪水（已修）；LL2CPP 调用不能 dlopen/直接调 RVA；后台 pthread 调 Unity API 崩溃。

## 5. 已知坑
- ⚠️ **多指污染待回传裁决** [V]（已从"未解"推进到"修复待验证"）——新 APK 已给复现用户。回传日志判定：`RAW_MISMATCH` 出现+症状消失 → 污染实时被拦，rawX 是否同步分叉裁决层级；零 mismatch+症状消失 → 外部条件已解除。双踏板"漂满"是间歇还是持续未确认，影响跳变兜底必要性。
- ⚠️ **日志推送分片丢失** [?]（继承，用户裁决搁置）——LogReceiver 分片拼装无完整性校验；修复：序号对齐或直读 externalFilesDir。
- ⚠️ **flyme 冻结后台致 REQUEST_LOGS 延迟** [?]（继承，仅 Takotsubo 自机）——加固方向：游戏 onPause 主动推送。
- ⚠️ **配置页"任何时候满帧"未达成** [?]（继承）——冷启动 18-19% janky，baseline profile 缺失候选；动手前重跑 A/B 基线。
- ⚠️ 曲线编辑器 QP 求解高频路径 / pager settle 回抓 / 滑块 v3 快弹簧 [?]（继承，未恶化不动）。
- ⚠️ 0x414 死参数 / 0x3D4 读法 / 游戏内 ABS 设置对物理无效 / 计时赛 IRDSUIMobileControls 晚 2s / offsets_sheet 0x1A62E10 勘误未订正 [?]（继承）；GitHub 公式/Mermaid 渲染坑、IL2CPP ARM64 扫描三坑（先读持久记忆）。

## 6. 下一步（有序）
1. **等复现用户回传新日志**（核心等待）——装 `845d970` 构建 APK → 开 NPatch 管理器 → force-stop 游戏 → 复现双场景（单踏板+点空白 / 双踏板双满）→ 前台导出。
2. 日志到手裁决：有 `RAW_MISMATCH` 且 `rawX` 同步分叉 → PointerCoords 本体被改（评估跳变过滤兜底 v2）；`rawX` 干净 → mRawTransform 层注入（归因 MIUI 输入管线 per-window 加工，考虑整理材料报小米/社区）；零 `RAW_MISMATCH` → 复现条件已随环境变化消失（对照其设备设置变化）。
3. **追 ABS 档位 0.80/0.60/0.40 实机手感**（继承，两轮未答）。
4. （可选）hook `Activity.dispatchTouchEvent` 做 decorView 层二分插桩——仅当 RAW_MISMATCH 不足以裁决时。
5. （搁置，用户裁决重启时做）分片丢失修复；（可选）onPause 主动推送；冷启动 baseline profile。

## 7. 留给用户的开放问题
- 复现用户 HyperOS 具体版本？是否开长按类全局功能（长按识别/AI 圈选）？是否装触控辅助 APP？——环境特异性方向的未答问题。
- 双踏板漂满是间歇还是持续？（决定 v2 跳变兜底是否需要）
- "无其他小米用户反馈"在多大样本上成立？（影响"环境特异"vs"沉默多数"的先验）
- 继承：分片丢失修复优先级、冷启动验收标准（首分钟不卡 vs 完全追平）、ABS 高档 0.40 手感是否可感。