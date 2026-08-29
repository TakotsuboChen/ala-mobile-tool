# HANDOFF — 读全文再开始干活

生成时间: 2026-08-30T00:25:30+08:00 · Git HEAD: `50c26e7`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `50c26e7` (2026-08-30)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `50c26e7`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `ABS_LEVEL_DESIGN.md` §4 v6 定案条 + §6 验证记录第 6 条（本轮核心）+ `MODULE_ABS_NOTES.md` §2b + `TECHNICAL_ANALYSIS.md` §2.8.2 修正块

## 1. 当前目标
ABS"最大制动压力"滑条语义经 v2→v6 五轮重定义，v6（饱和重映射）已实装并实机验证定案。主线等待项不变：①多指踏板污染修复等复现用户日志回传裁决；②Bug B（重启 remote 配置旧值）无新证据。

## 2. 已验证状态 — 工作实际停在哪
- [V] **v6 实装（`56e6baf`，native+Java 8 文件）**：`abs_remap_brake_request`（pedal_hook.c）——proxy_fixed_update **orig 后**每轮覆写 `wheel.brake(0xF0)`：ABS 段 `min(1, s·T_b·p_raw/F_base(v))`、跳过段线性 `p_raw·s`。输出 = min(s·T_b·p, F_base(v))：行程 0-100% 线性映射 0-s·T_b，任意车速允许上限封顶在**原生** F_base 曲线，与 ABS 档位/开关零耦合。T_b(0x88) 字段覆写通道退役（v2/v3/v4/v5 全否决，演化史见 ABS_LEVEL_DESIGN §4）。`abs_tb_scale`→`brake_scale` 改名，Java `setAbsParams` 签名不变语义重解释。
- [V] **v6 实机验证（90% 档，日志 ala_tool_log_20260829_235624，数据已入 ABS_LEVEL_DESIGN §6.6）**：段①关 ABS 全速域（343→10 km/h）bp=0.900/tf=4500 → 扭矩恒 4050；段②开 ABS bp 逐位吻合 min(1,4050/F_base)——255 km/h 处 0.910（饱和签名，排除线性 v5）、**187.6 km/h bp 翻 1.000 命中解析交点 2r−r²=0.9→188 km/h**；轮序（w0 前 780、w2 后 520）核实正确。
- [V] **构建+lint**：`./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL，**EXIT=0**，lint 0 errors。
- [V] **实机 APK 已装**（`adb install -r` Success，含 UI summary 回退后的最终版）。
- [V] **UI summary 用户裁决**：滑条描述保持原文"调整游戏制动摩擦扭矩上限"，不得改动（用户两次纠正后定案）；README 功能描述 v6 语义**保留**（用户只要求回退 UI 那句）。
- [V] 文档四份同步已提交（`50c26e7`）：ABS_LEVEL_DESIGN/MODULE_ABS_NOTES/TECHNICAL_ANALYSIS/README；v1.0.2 release notes 段按历史保真不改。
- 工作区: 干净（仅 `.handoffs/` 归档随本次提交），`main` 与 origin 同步。
- 继承 [?]：多指污染修复等红米用户日志回传；Bug B 未复现未修。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 1m 19s，EXIT=0（80 tasks）
adb install -r → Success
git push → 53c55e2..56e6baf..50c26e7 main（工作+文档两切片，成功）
```

## 3. 决策与理由
- **制动压力作用点=请求入口饱和重映射，非字段覆写** [V]——F_base 曲线顶端 `F_base(≥288)≡T_b` 字段直通（无 clamp），覆写 T_b 必压缩曲线；0xF0 每帧被 CC 重写 → orig 后覆写天然无复利。用户两条硬约束（曲线绝对值永久原生 + 满标重映射）在乘法结构下的唯一交集。否决：字段缩放（v2-v4，曲线联动压缩或与 ABS 状态耦合）、输入端线性缩放（v5，满踩被压 0.9 倍违反封顶可达）。
- **"曲线"语义三轮澄清** [V]——用户指的曲线=速度→上限**绝对值**（100 km/h→2916、288→4500），非百分比形状（v3 误读）非字段值；最终用用户给的数字钉死。教训：多个数学对象共用"压力上限"一词，动手前先用具体数字对齐 target。
- **ABSdiag 扩展 w0/w2 行** [V]——tb/p0f/p0r/0xF0 输出用于轮序判定与重映射校验；前后轮经 wheelRL(0xB0)/wheelRR(0xB4) 引用动态比对，不硬编码轮序。
- 继承：stash+遍历恢复（hide_pedals）、双源校验（踏板多指）、三档阈值等——见 `.handoffs/20260830120000-handoff.md` §3。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增（制动压力滑条语义演化，均已实装后撤销）
- [X] **v2 全局缩 T_b（现状起点）** → F_base 曲线联动压缩（100 km/h 2916→2624），用户："2916 在 100 根本不会锁死，限死 2624 是乱来"。不要再试。
- [X] **v3 p₀ 与 T_b 同缩** → 保百分比形状但**绝对值**仍缩，误解"曲线"为归一化占比图。不要再试。
- [X] **v4 门控分流（仅关闭档生效）** → 曲线锁死但滑条与 ABS 开关耦合，用户："谁说跟 ABS 有关系，怎么调是玩家自己的事"。不要再试。
- [X] **v5 输入端线性缩放** → 满踩恒 0.9×上限，"2916 被限死 2624"违反封顶可达。不要再试。
- [V] **UI 擅改 summary 文案（v4 加"仅关闭档生效"、v6 改重映射描述）** → 用户两次纠正"没让你改描述，原来的描述又简洁又没问题"。UI 文案不动，除非用户明示。
- [?] **ESC 干预与重映射通道叠加**（分析推演未实测）——ESC 触发写单侧后轮 0xF0 在重映射上游，其制动量会被一并重映射；ESC 多数用户关闭，接受影响面，实机若发现 ESC 校准异常再做通道隔离。

### 继承死路（全部仍有效）
- [V] 断言"show taps 圆点不跟随"→ 官方文档证伪；GameTurbo 设置层 A/B 无效；MotionEvent.isResampled 编译失败；getRawX/Y 直调 API 29+ lint 报错（统一走 helper）。
- [?] OnTouchListener 探 decorView 层事件（框架推理未实测）。
- [V] 边框缝隙数学无解必须层内不透明；档位值 0.90 贴 native clamp 静默截断；押 p0 主旋钮被推翻；usesABS 恢复漏置位；只写 absEnable 不够；每帧写 ctor 默认≠真值；画笔模式技术成功被用户否决；commit 时间推 APK 内容单因推断；adb force-stop 用户在用设备；滑块 v1/v2 帧数 1/6 归因错误、GestureAxisBlocker 整向量消费、perfetto shorthand、测量期间手触、miuix 查源码先联网；proxy_shift_up 打日志洪水（18810 条/21min）；IL2CPP 不能 dlopen/直接调 RVA；后台 pthread 调 Unity API 崩溃。

## 5. 已知坑
- ⚠️ **ABSdiag 的 0x414 死参数/0x3D4 读法/offsets_sheet 0x1A62E10 勘误** [?]（继承，未恶化不动）。
- ⚠️ **多指污染待回传裁决** [?]（继承）——修复已给复现用户，回传后按三分支裁决。
- ⚠️ **隐藏踏板"重启后仍隐藏"= remote 配置旧值（Bug B）** [?]（继承）——无新证据，实测未复现则搁置。
- ⚠️ **ConfigReceiver 与 15s init 竞态窗口** [?]（继承，窄窗口暂不修）。
- ⚠️ **日志推送分片丢失** [?]（继承，用户裁决搁置）。**导出为空排查经验**（本会话 [V]）：导出 72 字节=游戏侧 logEnabled 未开（ala_tool.log 停在旧日期）+推送链路本身正常；release 包不能 run-as，可直读 `/sdcard/Android/data/<游戏包>/files/ala_tool*.log` 兜底。
- ⚠️ **v6 重映射的 pulse 相位边界** [?]——pulse 帧 tempBrakeF=0 时 bp 采样可能看到异常比值（本会话日志 tf=0 行 bp 正常，未深究）；if 异常再分析。
- ⚠️ 曲线编辑器 QP 高频路径/pager settle 回抓/滑块 v3 快弹簧/冷启动 janky [?]（继承，未恶化不动）。

## 6. 下一步（有序）
1. **等复现用户回传多指日志**（核心等待，继承）——回传后按三分支裁决（`.handoffs/20260829205358-handoff.md` §6）。
2. （可选）追 ABS 档位 0.80/0.60/0.40 实机手感（继承，三轮未答；v6 定案后档位语义未变，可继续）。
3. （可选清理）删 `NativeBridge.hidePedalsApply()` 死代码（继承，grep 确认零调用）。
4. 若用户反馈 ESC 场景制动异常 → 按本会话 §4 最后一条做通道隔离。

## 7. 留给用户的开放问题
- v6 手感验收：90% 标尺的"72% 行程踩出 2916"在实战（刹车点、循迹刹车）中是否可用？标尺交点 188 km/h 以下的"封顶平台"（满踩=原生曲线）是否符合预期？
- 继承：复现用户 HyperOS 版本/长按类全局功能？"无其他小米用户反馈"样本量？分片丢失修复优先级、冷启动验收标准、ABS 高档手感是否可感。
