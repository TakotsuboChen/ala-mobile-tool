# HANDOFF — 读全文再开始干活

生成时间: 2026-08-30T04:12:00+08:00 · Git HEAD: `39f54bb`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `39f54bb` (2026-08-30)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `39f54bb`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `MODULE_ABS_NOTES.md` §2c（指示灯信号链四轮演化定案，含全部实机死路）+ `native/src/pedal_hook.c` 的 `abs_rf_intercept_pre`

## 1. 当前目标
TC/ABS 介入指示灯功能已完成并实机验收通过（用户裁决"ok，测试通过"）。主线等待项不变：①多指踏板污染修复等复现用户日志回传裁决；②Bug B（重启 remote 配置旧值）无新证据。

## 2. 已验证状态 — 工作实际停在哪
- [V] **TC/ABS 介入指示灯已实装（`60da0e2`，10 文件）并实机验收通过**：屏幕上边缘中点椭圆弓形（宽 1/3 屏 × 高 1/30 屏），TC 绿/ABS 红/同亮黄，直边中点 75% alpha 渐变到弧边 0%；介入时 25Hz 闪烁与游戏实际介入节奏同频。配置开关 `enable_tc_abs_indicator`（Overlay 控件区末尾，默认开启）六路径同步。计时赛实测：起步只闪绿、重刹红闪、巡航双灭。
- [V] **信号链核心 = ShadowHook 指令级拦截**：`shadowhook_intercept_instr_addr` 拦截 RoadForce 内 `str s0,[x19,#0x3EC]`（base+0x1A7B7DC，反汇编实证只有滑移超阈帧流经）。回调 x19 轮指针过滤玩家车 + 该轮 0xF0>0.01 过滤油门打滑空转；帧号 age（≤2 帧）判介入替代帧清零；`g_frame_phase` 25Hz 相位叠加闪烁。完整演化史与死路见 MODULE_ABS_NOTES §2c。
- [V] **构建+lint**：`./gradlew :app:assembleDebug :app:lint` → BUILD SUCCESSFUL，EXIT=0（lint 0 errors，baseline 提示为既有）。`:app:assembleRelease` EXIT=0，实机已装（设备 381QYFCN22B9A）。
- [V] **持久文档已同步（`39f54bb`）**：README 功能列表/架构图/路线图勾选；MODULE_ABS_NOTES §2c 定案 + pulseBrakes 旧记载证伪修正；CLAUDE.md Files to Know（197 行 < 200 ✓）。
- [V] **记忆已写**：`tcabs-indicator-signal-pitfalls.md`（五条信号链陷阱 + How to apply）。
- 工作区: 干净，`main` 与 origin 同步（`60da0e2` 工作 + `39f54bb` 持久文档 + 本次 handoff 提交）。
- 继承 [?]：多指污染修复等红米用户日志回传；Bug B 未复现未修。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleDebug :app:lint → BUILD SUCCESSFUL in 1m 13s，EXIT=0（lint 0 errors）
./gradlew :app:assembleRelease → BUILD SUCCESSFUL，EXIT=0
adb install -r app-release.apk → Success（多轮迭代）
git push → 0305f53..60da0e2..39f54bb main（工作+持久文档两切片，成功）
```

## 3. 决策与理由
- **指令级拦截而非字段读取** [V]——游戏无任何 ABS 介入信号字段（absTriggered 恒 false 死字段、HandleABS 零调用者、车辆级 ABS 整层死代码），介入状态只存在于 RoadForce 控制流。"读真信号"唯一路 = 拦截只有介入时才流经的指令。否决：条件复算（σ>0.15 判定），油门打滑同超阈误报，被用户否决。
- **拦截命中叠加 0xF0 物理效果过滤** [V]——ABS 调制段执行条件不含"正在刹车"（油门打滑驱动滑移同样超阈命中），不滤则起步红绿齐闪（实机实证）。0xF0≈0 时泄压无物理效果，不算介入。用户接受此为"真信号 + 效果过滤"而非回到条件复算。
- **帧号 age 判介入而非帧头清零** [V]——RoadForce 与 CC.FixedUpdate 是独立 MonoBehaviour 物理回调，Unity 不保证同帧先后；清零被"命中先于清零"每帧抹掉（灯恒灭，实机实证）。生产者记帧号、消费者判年龄，零同步点。
- **相位时钟单一写点** [V]——fixed_update 帧头唯一翻转；traction_filter 写点若再翻，双重翻转抵消、读相位恒 0，TC 灯死（实机实证）。
- **透明度 75%** [V]——用户实测 50% 太淡，mid-turn 指示改 75%（CENTER_ALPHA=191）。
- 继承：双轨 position 机制、stash+遍历恢复、v6 饱和重映射定案——见 `.handoffs/20260830041200-handoff.md` §3。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录 + MODULE_ABS_NOTES §2c。

### 本轮新增（指示灯信号链 v1→v5，全部实机实证）
- [X] **v1 读 pulseBrakes(0x408) 电平** → 车动即闪、低速残留上一帧电平。0x408 是每帧无条件翻转的 25Hz 相位时钟，不是介入标志（MODULE_ABS_NOTES 旧记载已证伪修正）。不要再试。
- [X] **v2 条件复算（|slipRatio|>0.15 && pulse && usesABS && 0xF0）** → 用户否决"不是拿条件自行判断，要真信号"；且起步打滑仍误报。不要再试。
- [X] **v3 拦截器 + 帧头清零** → 灯恒灭（偶尔一两下）：Unity 物理回调同帧顺序不定，清零抹掉同帧稍早命中。不要再用清零跨回调传递信号。
- [X] **v4 相位双写点** → fixed_update 帧头 + traction_filter 写点各翻一次 = 双重翻转抵消，两灯全灭。共享时钟必须单一写点。
- [X] **拦截命中不滤 0xF0** → 起步红绿齐闪（油门打滑驱动滑移也超阈命中）。已由 0xF0>0.01 物理效果过滤修复。

### 继承死路（制动压力滑条 v2-v5 演化，均已实装后撤销）
- [X] **v2 全局缩 T_b** → F_base 曲线联动压缩。**v3 p₀ 同缩** → 绝对值仍缩。**v4 门控分流** → 与 ABS 开关耦合。**v5 输入端线性缩放** → 满踩被压违反封顶可达。四条全否决，v6 饱和重映射定案（详见 ABS_LEVEL_DESIGN §4）。
- [V] **UI 擅改 summary 文案** → 用户两次纠正"没让你改描述"。UI 文案不动，除非用户明示。
- [?] **ESC 干预与重映射通道叠加**（推演未实测）——ESC 多数用户关闭，实机若发现 ESC 校准异常再做通道隔离。

### 继承死路（更早，全部仍有效）
- [V] 断言"show taps 圆点不跟随"→ 官方文档证伪；GameTurbo 设置层 A/B 无效；MotionEvent.isResampled 编译失败；getRawX/Y 直调 API 29+ lint 报错（统一走 helper）。
- [?] OnTouchListener 探 decorView 层事件（框架推理未实测）。
- [V] 边框缝隙数学无解必须层内不透明；档位值 0.90 贴 native clamp 静默截断；押 p0 主旋钮被推翻；usesABS 恢复漏置位；只写 absEnable 不够；每帧写 ctor 默认≠真值；画笔模式技术成功被用户否决；commit 时间推 APK 内容单因推断；adb force-stop 用户在用设备；滑块 v1/v2 帧数 1/6 归因错误、GestureAxisBlocker 整向量消费、perfetto shorthand、测量期间手触、miuix 查源码先联网；proxy_shift_up 打日志洪水（18810 条/21min）；IL2CPP 不能 dlopen/直接调 RVA；后台 pthread 调 Unity API 崩溃。

## 5. 已知坑
- ⚠️ **多指污染待回传裁决** [?]（继承）——修复已给复现用户，回传后按三分支裁决（`.handoffs/20260829205358-handoff.md` §6）。
- ⚠️ **隐藏踏板"重启后仍隐藏"= remote 配置旧值（Bug B）** [?]（继承）——无新证据，实测未复现则搁置。
- ⚠️ **ConfigReceiver 与 15s init 竞态窗口** [?]（继承，窄窗口暂不修）。
- ⚠️ **日志推送分片丢失** [?]（继承，用户裁决搁置）。导出为空排查经验 [V]：72 字节=游戏侧 logEnabled 未开；release 包可直读 `/sdcard/Android/data/<游戏包>/files/ala_tool*.log` 兜底。
- ⚠️ **v6 重映射的 pulse 相位边界** [?]（继承）——pulse 帧 tempBrakeF=0 时 bp 采样可能异常，未深究。
- ⚠️ **ABSdiag 的 0x414 死参数/0x3D4 读法/offsets_sheet 0x1A62E10 勘误** [?]（继承，未恶化不动）。
- ⚠️ **指示灯拦截地址 0x1A7B7DC 硬编码** [V]——8.0.4 专用，与其他 IL2CPP 偏移同受 VersionGate 门控；升级游戏版本必须重新反汇编 RoadForce 定位（MODULE_ABS_NOTES §3 验证清单）。
- ⚠️ **指示灯诊断日志 rfHits/hitAge 行** [V]——abs_diag_log 内，验收已过；下一版可整段移除（与 TCdiag/ABSdiag 同属"标定完可移除"）。
- ⚠️ 曲线编辑器 QP 高频路径/pager settle 回抓/滑块 v3 快弹簧/冷启动 janky [?]（继承，未恶化不动）。

## 6. 下一步（有序）
1. **等复现用户回传多指日志**（核心等待，继承）——回传后按三分支裁决。
2. （可选）追 ABS 档位 0.80/0.60/0.40 实机手感（继承，v6 定案后档位语义未变）。
3. （可选清理）删 `NativeBridge.hidePedalsApply()` 死代码（继承，grep 确认零调用）+ 移除 abs_diag_log 的 rfHits 诊断行。
4. 若用户反馈 ESC 场景制动异常 → 按 `.handoffs/20260830004912-handoff.md` §4 做通道隔离。

## 7. 留给用户的开放问题
- 工具按钮记忆位置无 UI 重置入口——用户若想回默认位置，需清应用数据或等未来加"长按重置自身"（当前长按=进编辑模式）。是否需要单独重置手势？
- TC/ABS 指示灯是否需要亮度/闪烁频率调节项？（当前规格固定：75% 中心 alpha、25Hz 闪烁、无滑条）
- 继承：v6 手感验收（刹车点/循迹刹车实战）；复现用户 HyperOS 版本；分片丢失修复优先级、冷启动验收标准、ABS 高档手感是否可感。
