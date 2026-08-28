# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T20:53:48+08:00 · Git HEAD: `9673a32`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `9673a32` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `9673a32`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `TC_LEVEL_DESIGN.md` §10 v1.5 调优注记 + `ABS_LEVEL_DESIGN.md` §4 旋钮2（档位现行值以 `ModConfig.kt` 为单一事实源）+ `.handoffs/20260828165411-handoff.md` §2（触摸现象用户原话指针）

## 1. 当前目标
档位系统实机调优完成并推送（TC 强度/时机新值 + 档位标识符对齐中文 + 制动压力 50-100%），release APK 已上设备等用户手感回传。**两个长期遗留不变**：①多指触摸 bug 等复现日志（头号）；②冷启动满帧未达成（baseline profile 缺失，结构性主攻方向）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **TC 档位调优**：强度 mix 0.25/0.5/0.75 → **0.15/0.4/0.6**（MAX=1 原厂保留，用户裁决）；时机 ε 0.30/0.18 → **0.35/0.25**（minspd 配对 8.0/4.0 不变；ε 越小越早，中间两档是放缓）——`ModConfig.kt` TcStrength/TcTiming。
- [V] **档位标识符对齐中文词汇表**：WEAK/STRONG/STOCK → LOW/HIGH/MAX（TcStrength+AbsStrength 对称），TcTiming DEFAULT/EARLIER → LATE/EARLY。**存档 value 键值保留历史值**（"weak"/"stock"/"earlier"/"default"），零迁移；enum 处有注释说明。
- [V] **制动压力 50-100%**：UI `valueRange = 0.5f..1.0f` + `migrateAbs`/`ConfigReceiver` coerceIn(0.5f,1f)（旧配置 <0.5 抬到 0.5）；**native 不动**（tbScale<0 = 禁用 T_b 通道的独立语义）。native `pedal_set_abs_params` 本无 0.75 下限。
- [V] **"0.75 下限"是注释腐化**：实际 clamp 一直是 [0,1]，"[0.75,1.0]"只在两处过期注释里（`NativeBridge.kt`/`migrateAbs` KDoc），均已修正。
- [V] **构建/lint**：`./gradlew :app:lint :app:assembleRelease` 全新 shell → BUILD SUCCESSFUL，EXIT_CODE=0。
- [V] **上设备**：force-stop → `adb install -r` → Success（MEIZU 20 `381QYFCN22B9A`）。
- [V] 工作区：干净，工作切片 `88bdb60`、README `9673a32` 已推送，main 与 origin/main 同步。
- [?] **实机手感未回传**：TC 新档位（尤其"较早"ε=0.35 阈值 W>1.54 是否与默认 W>1.82 分得开）、制动压力 50% 下限。
- [?] 继承（aaa9093 系文案/弹窗、v3 手势手感）：本会话未触碰、用户未反馈。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:lint :app:assembleRelease → BUILD SUCCESSFUL in 6s，EXIT_CODE=0
adb install -r app-release.apk → Success（前已 force-stop 游戏进程）
git push ×2 → 88bdb60（工作）/ 9673a32（README）成功
```

## 3. 决策与理由
- **档位数值以用户实测定档** [V]——0.15/0.4/0.6 与 ε0.35/0.25 是用户实机手感结论；迭代只改 `mix`/`eps` 常数，不动存档结构（`ModConfig.kt` KDoc 已声明）。
- **标识符与存档 value 解耦** [V]——enum(value, data) 双字段：`LOW("weak", ...)` 标识符对齐中文，value 保留 JSON 历史键值，零迁移零风险。否决：value 一并改+JSON 迁移——收益仅 JSON 美观，成本是迁移代码与旧档兼容风险。
- **clamp 语义分层** [V]——Java 层管"用户可配范围"（50-100%，migrateAbs/ConfigReceiver），native 只管"参数契约"（<0=禁用、>1=非法）。否决：50% 下沉 native——会杀死 T_b=0 覆写的合法内部用法。
- **三主题单 commit** [V]——TC 数值/重命名同行交织（`LOW("weak", 0.15f)` 值名同格），拆分需 hunk 手术；对齐项目先例（aaa9093 多子项单切片）。
- **ABS_LEVEL_DESIGN.md 保演化史不改写历史表** [V]——§4 旋钮2 表更新为 50-100% 现行规格并附下限演化链（75%→0-100%→50-100%），顺带解决文档 line90/92 自相矛盾。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增（档位调优）
- [X] **enum 重命名顺手改存档 value 字符串**（`LATE("late")` 等）→ 旧配置 "weak" 找不到匹配 fallback `?: MAX`，"低"静默变"最高" [V]——提交前拦截修正，value 恢复历史键值。重命名标识符 ≠ 重命名持久化键。
- [V] **把过期注释当需求事实源**——"75% 下限"排查先 grep 到两处注释以为真有此 clamp，实际代码是 [0,1]。注释与代码矛盾时以代码为准、两处注释都修正。
- 继承（手势/测量，全部 [V] 证伪于上会话，勿重试）：滑块 v1 同节点无取值消费层（Compose slop 自由竞争+Final pass 复核，拖不动）；v2 DOWN 起无差别独占（吞竖滑，帧数 1/6）；"设备性能"归因（用户两次否决，A/B 才是路）；GestureAxisBlocker 整向量消费（全页竖滚死）；封锁条件语义写反；`pointerSlop()` API 项目版本无；perfetto shorthand tags 抓用户版数据；测量期间手触（免触碰+截图核对）；miuix 查源码先联网（先查 `references/miuix/`）。
- 继承（更早，[?] 除标注外）：押 p0 主旋钮被实测推翻；usesABS 恢复漏置位；档位差异化不足（本会话已响应）；0x3D4 读法未解；bang-bang 方向反直觉；只写 TCLSlip=eps 挡死起步段；每帧写 ctor 默认≠真值；llvm-objdump 传文件偏移；assembleRelease 不跑 lint（推送前必须 `:app:lint`）；TractionControlDynamicAssist 仅玩家车；IRDSPlayerControls.tractionControl 死字段；math 公式内中文/裸竖线/operatorname；AI 车 is_player_controller 过滤不可靠；触摸 bug id 跟踪/findPointerIndex 修不了（根因不在此层）；proxy_shift_up 打日志洪水（21min 18810 条）。
- [V] 继承：只写 absEnable 不够（唯一门控 per-wheel usesABS）；后台 pthread 调 Unity API 崩溃；dlopen 不可用改 ELF 符号查找；adb install -r 前必须 force-stop；awaitLsposedSettled 不可靠；kkgithub 404 用 gh-proxy。

## 5. 已知坑
- ⚠️ **配置页"任何时候满帧"未达成（用户核心不满）** [?]（继承）——A/B 实测 HEAD 冷启动即 18-19% janky（p50 7ms），热身后 0.7-2%。主因候选：**无 baseline profile**（build.gradle 零配置）+ 单 item 全量重组 + JIT 预热约 1 分钟。修复方向：androidx.baselineprofile（生成器 module + MEIZU 20 跑 profile 模板）+ 可能拆配置页单 item。动手前先重跑 A/B 基线。
- ⚠️ **多指触摸 bug 未解（头号任务）** [?]（继承）——一手踏板+一手点空白，行程填充漂 100%、反复抽搐（原话：`.handoffs/20260828132840-handoff.md` §2）；插桩就位等复现日志；三候选根因（意外事件重排/坐标混入他指/pairip relayout）。
- ⚠️ **注释腐化教训** [V]（本会话）——"0.75 幽灵"：两处注释与实际 clamp 不符，误导需求排查。改行为必须同步注释；发现矛盾时以代码为准。
- ⚠️ pager `startDragImmediately` settle 回抓在 Initial pass 无条件认领 DOWN [?]（继承）——滑块抓取瞬间 settle 中断闪动为已知残余。
- ⚠️ 滑块 v3 独占期间内部 isDragging 失效，快弹簧回落慢弹簧 [?]（继承）——用户未抱怨，暂不动。
- ⚠️ 0x414（fullAbsEnableSpeed）疑似死参数 [?]；0x3D4 读法未解 [?]；TC 开关位每帧重算（已绕过）[?]；游戏内 ABS 设置对物理无效 [?]；计时赛 IRDSUIMobileControls 晚 ~2s [?]；LSPosed 下 Remote Preferences 受限→setComponent 显式广播 [?]；BillingHook NPatch 模式永远失败 [?]；flyme 后台白名单/miuix TopAppBar spring/广播 JSON 不含 position/OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]；GitHub 公式/Mermaid 渲染坑（先读持久记忆）[?]；IL2CPP ARM64 扫描三坑 [?]；offsets_sheet 0x1A62E10 勘误未订正 [?]。

## 6. 下一步（有序）
1. **等触摸 bug 复现日志**（头号，等待中）——force-stop 后装插桩版、确认日志开关开启、复现"一手踏板+一手点空白"、导出日志回传；分析三候选根因。
2. **TC/制动压力新手感回传后按需微调**——只动 `ModConfig.kt` 的 mix/eps/bOverride 常数；若"较早"ε=0.35 与默认区分不开则再压。
3. **冷启动满帧结构性修复（长期目标）**——androidx.baselineprofile 基础设施 + 配置页单 item 拆分评估；动手前重跑 A/B 基线。
4. ABS 二期（低优先级）：kP/κ 解析、弯中自动收紧、overlay ABS 视觉指示、0x410 低速退出档。
5. AI 车白名单长期观察（继承）。

## 7. 留给用户的开放问题
- TC 新档位（强度 0.15/0.4/0.6、时机 ε0.35/0.25）实机手感如何？尤其"较早"与"较晚（默认）"是否分得开。
- 制动压力 50% 下限是否够用，50% 档手感是否符合预期。
- 触摸 bug 复现日志回传后：跳变瞬间 action 序列？游戏原生踏板按钮隐藏还是显示？（决定根因走向）
- 冷启动验收标准：首分钟不卡（baseline profile 大概率够）还是完全追平 KernelSU 满帧（需拆配置页结构，单独评估）？
- v3 手势手感（滑块横向独占、竖向放行）日常使用是否有新反馈？（继承，两轮未答）