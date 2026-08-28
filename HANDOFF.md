# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T22:20+08:00 · Git HEAD: `8816ee0`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `8816ee0` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `8816ee0`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `ModConfig.kt` 的 `monotoneCubic`/`solveCurveTangents` KDoc（曲线算法单一事实源）+ `TC_LEVEL_DESIGN.md` §10 v1.5 + `ABS_LEVEL_DESIGN.md` §4 + `.handoffs/20260828222013-handoff.md` §4（死路总账）

## 1. 当前目标
多指触摸 bug（头号任务）的插桩数据链路**全链路打通并实证**：查明复现用户日志零插桩数据的根因（其设备模块代码旧），插桩验证通过、MEIZU 20 无 bug 复现；顺手落地日志导出 24h 过滤（`0cd1cbd`）。日志推送分片丢失 bug 用户裁决先不修。等待复现用户装新 APK 复现回传，拿数据裁决三候选根因。

## 2. 已验证状态 — 工作实际停在哪
- [V] **24h 导出过滤已落地**：`LogExporter.kt`（+40/-3，commit `0cd1cbd`）新增 `filterRecent`——带时间戳行开新条目、异常堆栈续行跟随条目、段头/提示文本在过滤外、解析失败保留（宁多勿少）；兼容 Java `2026-08-28 21:54:08.291` 与 native `[2026-08-28T21:54:08.290` 两种行首（regex 需可选 `\[` 前缀）。真实导出文件验证：641KB→209KB、8/19-8/27 零残留、pedal 916 条全保留。
- [V] **插桩链路端到端全通**（MEIZU 20 实测）：游戏进程 dex 含插桩字符串（安装包 md5 与本地构建一致 `d182ca8d`）→ logcat 413 条 + `ala_tool.log` 916 条 + 前台导出 916 条全进。DOWN 布局对比 `onScreen==(1944,252) == cfgTop` 无 relayout 漂移。
- [V] **MEIZU 20 无 bug 复现**：10 轮触摸（22:04 五轮 + 22:16 多轮）全干净——ptrCount 恒 1、DOWN/UP 配对、rawY 连续、无 t 跳变。用户本人无此 bug，与预期一致。
- [V] **复现用户 13:39 日志零插桩根因 = 模块代码旧**：08-28 会话仍有 487 条 `proxy_shift_up called`（native 洪水，删除提交 `e3d9815` 之前构建的 .so 指纹）+ java 段 13:37:44 后静默（旧 dex 无插桩）。proxy 洪水的有无是 APK 构建时间窗的可靠指纹。
- [V] **flyme 冻结导致导出拿旧快照**：22:06 导出 REQUEST_LOGS 广播 10s 超时（游戏退后台被冻结），迟至 22:08:40 才送达（延迟 2min），两次导出文件 md5 完全相同。游戏在前台导出则正常（22:09 实证 916 条全进）。
- [V] **日志推送分片丢失（新 bug，用户裁决先不修）**：发送量 vs 缓存实收对不上——21:54:54 发 476632 缓存 216619（丢尾）、22:04:25 发 311568 缓存 0 增量（全丢）、22:08:40 发 398124 全到。丢尾部 = pedal 行（文件后半）被截掉，是 21:55/22:06 两次"零触摸导出"的真根因。
- [X] **修正两个本会话误判**：①"发出去的 APK 不含插桩"——错，用户构建完才 commit 是常态流程；②"21:54 会话跑旧模块 dex"——错，该会话 pedal 行（21:54:33-47）一直写在文件里（22:16 导出可见），零导出是分片丢失。
- [V] 工作区：干净，`main` 与 `origin/main` 同步（`0cd1cbd` 工作 + `8816ee0` README）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 2m 3s，0 errors（42 warnings=基线）
真实导出文件过滤验证（python 模拟 filterRecent）→ 8/19-8/27 零残留、pedal 916 全保留、体积 -67%
装机实测导出（22:16，前台）→ 277KB、仅含 2026-08-28、pedal 1149 条（含三轮会话全量）
adb install -r（22:15:24）→ Success（前已 force-stop）
git push ×2 → 269f81e..0cd1cbd..8816ee0 main（成功）
```

## 3. 决策与理由
- **24h 过滤按条目不按单行** [V]——单行过滤会把异常堆栈从异常头切走；段头在 `filterRecent` 之外由调用方 append，不受过滤影响。否决：按单行正则删——日志碎片化。
- **推送分片丢失 bug 搁置** [V]——用户原话"先不管了，就这样吧"。修复方向已记录（见 §5），未来做日志相关工作时再启。
- **复现用户指引更新** [V]——装 22:14 构建 APK（含插桩+24h 过滤）→ **先开 NPatch 管理器**（唤醒注入/刷新模块缓存）→ force-stop 游戏 → 开游戏复现 → 前台导出或切出立即导。成功标志：日志里不再出现 `proxy_shift_up called`。
- **继承（曲线收口）**：曲率最小化已是插值语义数学最优（信息论边界），重启曲线走预设档+强度滑块；数学误差 -40% ≠ 感知改善，先做感知阈值实验。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增（触摸诊断链路）
- [X] **用 commit 时间推 APK 构建内容** → 插桩提交 23:27:47 晚于 APK 发送 23:25，据此断言"APK 无插桩"被实测推翻（设备安装包 md5 与含插桩构建一致）。**判 APK 内容用运行时指纹**（日志字符串在 dex/.so 里的有无，如 `pedal[`、`proxy_shift_up called`），不用时间线推断。
- [X] **"导出零数据 = 代码没跑到/模块旧"的单因推断** → MEIZU 上插桩工作、数据干净但导出仍可为零：推送分片丢失（丢尾/全丢）同样产生零数据。单次导出缺数据 ≠ 模块旧，先对比发送方日志量与缓存实收量再定罪。
- [X] **验证脚本与实现同人同构盲区** → python 模拟与 Kotlin 共享同一个 regex 错误（漏 native 行首 `[`），第一轮验证假绿。教训：regex 类断言先拿真实样例行匹配；模拟失真时修脚本并标注与实现的差异。
- [X] **以"最坏情况"揣测用户操作**（怀疑没踩踏板/装错包/没杀进程）→ 用户实测数据推翻。用户明确执行了操作时，优先查代码与链路，不质疑操作。
- [V] **adb 调试时 force-stop 用户正在使用的设备/切走前台** → 用户明确不满。动设备前确认用户不在操作。
- 继承（曲线研究，全部 [V]）：画笔模式技术成功但被用户否决（"只认控制点"）；"换算法任意形状"三次撞墙（信息论边界）；Python 镜像两截获 bug（能量展开丢交叉项、单调盒初值 ±∞）；"数学误差降低≈可感知"被实测推翻。
- 继承（档位调优，全部 [V]）：enum 重命名顺手改存档 value 字符串 → 旧配置静默 fallback；把过期注释当需求事实源（"0.75 幽灵"）。
- 继承（手势/测量，全部 [V] 证伪，勿重试）：滑块 v1 无取取值消费层/v2 无差别独占（帧数 1/6）/"设备性能"归因/GestureAxisBlocker 整向量消费/封锁条件语义写反/`pointerSlop()` API 无/perfetto shorthand tags/测量期间手触/miuix 查源码先联网。
- 继承（更早，[?] 除标注外）：押 p0 主旋钮被推翻；usesABS 恢复漏置位；档位差异化不足（已响应）；0x3D4 读法未解；bang-bang 方向反直觉；只写 TCLSlip=eps 挡死起步；每帧写 ctor 默认≠真值；llvm-objdump 传文件偏移；assembleRelease 不跑 lint；TractionControlDynamicAssist 仅玩家车；IRDSPlayerControls.tractionControl 死字段；math 公式内中文/裸竖线/operatorname；AI 车 is_player_controller 过滤不可靠；触摸 bug id 跟踪/findPointerIndex 修不了（2b5997c 修复是否真上过用户设备存疑，本轮证明用户设备一直跑旧模块，该死路结论是假阴性候选）；proxy_shift_up 打日志洪水（已修）。
- [V] 继承：只写 absEnable 不够（唯一门控 per-wheel usesABS）；后台 pthread 调 Unity API 崩溃；dlopen 不可用改 ELF 符号查找；adb install -r 前必须 force-stop；awaitLsposedSettled 不可靠；kkgithub 404 用 gh-proxy；CHUNK_SIZE=256K 防 TransactionTooLargeException（但分片仍会丢，见 §5）。

## 5. 已知坑
- ⚠️ **日志推送分片丢失（待修，用户裁决搁置）** [V]——LogReceiver 分片拼装无完整性校验：21:54:54 发 476632 缓存 216619（丢尾）、22:04:25 发 311568 缓存 0 增量（全丢）。修复方向：分片序号+总数对齐才落盘、缺片重发，或模块进程直读游戏 externalFilesDir 为主路径（策略 3 实证 adb 可读）。
- ⚠️ **flyme 冻结后台游戏进程，REQUEST_LOGS 延迟送达** [V]——22:06:40 发的广播 22:08:40 才被处理（冻结 2min）。导出时游戏在后台拿旧快照。加固方向：游戏 onPause 时主动推送（趁活着推）。
- ⚠️ **多指触摸 bug 未解（头号任务）** [V]——插桩链路已打通（`pedal[...]` 日志 + 24h 导出），等复现用户装新 APK 复现回传。三候选根因：(a) 意外事件重排 (b) 坐标混入他指 (c) pairip relayout；附加链：reset→游戏接管抽搐（native `g_throttle_active` 被清 → FixedUpdate 停写 → 游戏原生输入接管）。注意：复现用户设备一直跑旧模块，2b5997c 修复从未被真正验证，bug 可能已被部分修复。
- ⚠️ **配置页"任何时候满帧"未达成** [?]（继承）——冷启动 18-19% janky，主因候选 baseline profile 缺失；动手前重跑 A/B 基线。
- ⚠️ 曲线编辑器拖点高频路径含 QP 求解 [?]（继承）——LRU 缓存 + 早停，未见性能问题。
- ⚠️ pager settle 回抓 / 滑块 v3 快弹簧回落 [?]（继承）——用户未抱怨，暂不动。
- ⚠️ 0x414 疑似死参数 / 0x3D4 读法未解 / TC 开关位每帧重算（已绕过）/ 游戏内 ABS 设置对物理无效 / 计时赛 IRDSUIMobileControls 晚 ~2s / LSPosed 下 Remote Preferences 受限 / BillingHook NPatch 永远失败 / flyme 后台白名单 / 广播 JSON 不含 position / offsets_sheet 0x1A62E10 勘误未订正 [?]（继承）；GitHub 公式/Mermaid 渲染坑（先读持久记忆）；IL2CPP ARM64 扫描三坑。

## 6. 下一步（有序）
1. **等复现用户回传新日志**（头号，等待中）——指引：装 22:14 构建 APK（`app/build/outputs/apk/release/app-release.apk`，md5 以 `git log 0cd1cbd` 后构建为准）→ 开 NPatch 管理器 → force-stop 游戏 → 复现"一手踏板+一手点空白" → 前台导出。成功标志：日志无 `proxy_shift_up called` 且有 `pedal[` 行。
2. **日志到手后裁决三候选根因**——(a) 跳变瞬间 action 序列；(b) MOVE 值链是否混入他指；(c) DOWN 布局对比是否漂移。若 reset→游戏接管抽搐成立 → 评估 native 踏板开启期间每帧强制写。
3. （用户裁决搁置，重启时做）**日志推送分片丢失修复**——完整性校验或直读主路径。
4. （可选加固）**onPause 主动推送**——防 flyme 冻结导致导出旧快照。
5. 冷启动满帧结构性修复（长期）——baseline profile + 配置页拆分，动手前重跑 A/B 基线。
6. ABS 二期（低优先级）；AI 车白名单长期观察。

## 7. 留给用户的开放问题
- 复现用户装新 APK 后：proxy 洪水是否消失（NPatch 唤醒/模块更新成功的标志）？若仍出现 → NPatch 注入机制需要单独排查。
- 分片丢失修复的优先级：等下一轮日志回传若再次零数据就升级优先级。
- 冷启动验收标准：首分钟不卡（baseline profile）还是完全追平 KernelSU 满帧（拆配置页结构）？
- TC 新档位 + 制动压力 50% 下限的实机手感（继承，两轮未答）。
- 曲线话题若重启：预设档+强度滑块方案是否采用（见上会话 handoff §3）。