# HANDOFF — 读全文再开始干活

生成时间: 2026-09-09T21:16:13+08:00 · Git HEAD: `a324e30`（模块仓已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `a324e30`（2026-09-09，工作+持久文档提交后的 HEAD）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `a324e30`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: 本文件 §2（踏板案定案结论）+ 记忆 `pedal-lag-probe-device-facts.md`（含 2×2 矩阵与对话教训）

## 1. 当前目标
双线会话：①**踏板 overlay 卡顿排查（已完整定案）**——反馈用户 iQOO 12/OriginOS 4 开线性踏板掉帧/锁30fps，经三轮迭代+网络研究+用户实测视频定案根因；②**强制升级门控（已实现）**——ForceUpdateGate 全链路就位。悬而未决旧目标：游戏进程闪退定案（等 crash 现场）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **踏板案根因定案**：OriginOS 4 的 LTPO 逐帧刷新率重估被 overlay 绘制节奏扰动。证据链 = 用户视频（触摸踏板时刷新率悬浮显示在 120/72/60/51/45/30 疯狂跳动）+ Scene CSV（锁30段 CPU 32%/GPU 24% 全空闲、GPU 反而低于满帧段）+ 魔盒双向开关实验（加魔盒→不卡→移出→又卡）。最终用户侧解法 = **只要把游戏添加进游戏魔盒即可**（白名单身份锁刷新率策略），与任何具体设置无关（帧率优先/模式均无关，21:03 用户实测排雷）
- [V] **模块侧性能修复五件套全部保留**（修真实的性能问题，只是压不住 LTPO）：①值不变早退（NaN sentinel 消灭静止按住的 JNI+invalidate）②Logger 文件写入异步化（HandlerThread 单写者，主线程只入队）③ThreadCpuProbe（10s 线程级 CPU+rss 探针随导出日志自带）④rawY 污染 sticky 记忆化（免每帧 getLocationOnScreen IPC+零分配 scratch 缓冲）⑤scheduleDraw（invalidate 合并到 vsync 帧边界）。scheduleDraw 已实测**不够**对抗 LTPO（用户装的是含⑤的最新 APK 后仍乱跳）
- [V] **rawY 污染指纹**：反馈用户设备 rawY 通道恒定注入 −1260（=屏高）偏移，100% MOVE 帧命中；Takotsubo 设备（MEIZU 20/Android 16/Flyme/480Hz）双源零分叉
- [V] **ForceUpdateGate 强制升级门控**：版本落后最新 Release 时游戏进程全部 hook 禁装（onModuleLoaded 缓存秒判 → BillingHook 主线程 200ms 轮询守门 → doPackageReadyDeferred 判定未完成重推 → 15s 路径复查）+ 循环 Toast（2.5s）；fail-open（离线/检查失败不激活）；skip 版本机制删除、更新弹窗不可关（唯一出路=退出模块 finish/完成更新自动安装）；`latest_known_version_code` 缓存双进程写（OverviewPager 检查更新 + 游戏进程后台检查）
- [V] `:app:lint` EXIT=0（0 errors）；装机 Success（版本号未动 `1.0.4 Alpha 1`；assembleRelease 需 `-x lintVitalAnalyzeRelease -x lintVitalRelease` 跳环境性崩溃，已入 CLAUDE.md）
- 工作区: 干净全推送。`3e41cb9`(踏板性能+Logger) → `d7b9591`(ForceUpdateGate) → `a324e30`(CLAUDE.md)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL
adb install -r → Success（1.0.4 Alpha 1，未升版）
用户侧实测：新APK+盒外=刷新率乱跳（视频）；新APK+盒内=不卡；移出=又卡（21:03 定案）
```

## 3. 决策与理由
- **LTPO 案不再追模块侧对策** [V]——scheduleDraw（vsync 帧边界规整化）已是最后一刀且实测不够；系统级调度反应无合法 API 可干预。魔盒白名单是零成本用户侧解法。否决方案：继续压绘制开销（①-⑤已做尽，与调度失稳正交）
- **归因教训——"狂暴模式治好"是张冠李戴** [V]：起效的是魔盒白名单成员资格本身（模式无关、设置全开不了也行），伴随动作差点污染结论。21:03 对话逐项排雷定案
- **2×2 版本矩阵消解"昨晚盒内也卡"矛盾** [V]：昨晚卡 = 旧版 APK 真实模块开销（逐事件 JNI+invalidate+主线程同步写日志，魔盒救不了）；今晚盒外卡 = LTPO 调度（模块开销已被①-⑤清零后剩余层）。两层因果都真实、各有解
- **ForceUpdateGate 判定先于一切 hook**（用户定案红线）：不允许 hook「先生效零点几秒再停」；verdictDone 守门 + 主线程轮询（绝不阻塞主线程，阻塞会 ANR）；fail-open 宁放过不误伤
- **ThreadCpuProbe 设计**：用户 99% 无电脑不能 adb（记忆红线），诊断数据必须随「导出日志」自动带出；/proc/self/task 差分采样 10s 一次 <0.1% 单核
- 继承：版本号红线 / token 三级回落 / order==2 挂圈 / 配置 saved_at 仲裁 / 弹窗两变量契约 / NPatch 管理器唤醒注入

## 4. 失败的尝试 — 不要再试
- **采样率差异假说（踏板案）** [X]——"iQOO 高触摸采样率→事件密度大→扰动仲裁"被用户当场否证：Takotsubo 自己 480Hz/Android 16 完全没事。不要再捡起采样率解释
- **游戏插帧假说（踏板案）** [X]——iQOO 12 Q1 芯片"游戏插帧被 overlay 打断"推断精美但双死：游戏魔盒昨晚就被移出（今晨确认），且 Ala Mobile 不可能在 vivo 插帧白名单。精美解释≠真相，先核对前提再展开
- **scheduleDraw 治 LTPO 乱跳** [X]——已实测（用户装的 15:14 APK 含⑤）刷新率仍 120/72/60/51/45/30 乱跳。作为通用加固保留，但不要再期待它解决 OriginOS 调度问题
- **应用自定义高刷入口（iQOO 12）** [X]——OriginOS 4 该代已移除此入口，用户无手动兜底，别再指路
- **继承（前向有效）**：REQUEST_LOGS 广播探活 V1 游戏后台场景 [X] / UsageStatsManager 深权限（用户否决）[X] / 日志 mtime 判活 [X] / 单变量弹窗挂载+show [X] / 页内 Box 当全屏遮罩 [X] / 磁盘缓存原图字节 / 凭印象 scp root@ / grep FATAL 零命中≠没崩 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260909215000-handoff.md` §4

## 5. 已知坑
- ⚠️ **游戏闪退真凶未定案** [?]（继承）——crash_hook 已装机，等用户复现导出日志；crash 文件 pc 偏移对拍 `OffsetTable.kt` 定案
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——Java CrashCatcher 在分发版里，等用户日志
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V]——Kotlin FIR 在 AboutScreen.kt 内部错误，干净基线复现 = 环境问题；跳过法已入 CLAUDE.md；未根治（低优先级，CI 不受影响）
- ⚠️ **ABSdiag/TCdiag 高频诊断扰度未处理** [?]（继承）——0.5s 一组常驻刷 logcat 与 2MB 文件，用户未拍板
- ⚠️ **对话纪律（本会话连续犯错，用户三次纠正）** [V]——①mid-turn 消息必须逐条消化（丢一条=全盘错）②旧快照不当现在时断言③不编造未发生的状态（"发给他测"≠"他测过"）④开口前先推演消息内含逻辑（时间线闭合的不反问）
- ⚠️ 继承：导出探活广播链 ROM 限流边界 / token 链路只防"无值"不防"陈旧值" / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260909215000-handoff.md` §5

## 6. 下一步（有序）
1. **等反馈用户回话**：他已知"加魔盒就不卡"；待办 = 若他回报具体机制线索（不会有了，已排雷完毕）或新问题再开；无需主动动作
2. **（可选）文档加 FAQ**：设置页/README 加一条「iQOO/OriginOS 用户：开线性踏板掉帧请将游戏添加进游戏魔盒（无需任何具体设置）」——需用户拍板放哪
3. v1.0.4 正式发布待用户定版（版本号红线；踏板修复+门控已随 Alpha 1 验证）
4. （可选，继承）ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- FAQ 条目要不要加、加在哪（README / 设置页说明 / 群公告）
- v1.0.4 正式版版本号与发布时机（踏板修复+强制门控是否写进 Release Notes）
- 强制升级门控的 Toast 文案与触发阈值（当前：落后最新 Release 即触发）是否符合预期，等真实升级场景验证
