# HANDOFF — 读全文再开始干活

生成时间: 2026-09-10T00:30:00+08:00 · Git HEAD: `f2dbc06`（工作+持久文档提交后，已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `f2dbc06`（2026-09-10，工作+持久文档提交后的 HEAD）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f2dbc06`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: 记忆 `pedal-lag-probe-device-facts.md`（踏板案已定案）+ CLAUDE.md ForceUpdateGate.kt 条目

## 1. 当前目标
启动门控扩展：**游戏版本不匹配 + 模块不是最新版 + 双失配三态全部零 Hook + 循环 Toast**——已完成并实机验收通过（用户确认"可以了"）。悬而未决旧目标：游戏进程闪退定案（等 crash 现场，注意启动门控已排除"错版本装 hook"这一类）、模块 App 闪退定案（等用户日志）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **游戏版本不匹配零 Hook**：`ForceUpdateGate.evaluate` ① 非游戏包（GMS/WebView 等被 scope 注入的进程）按包名直接放行 verdict；② 游戏包内 `isSupportedVersion`（包名+versionName+versionCode 三重）不匹配 → 终局激活（无 fail-open），Toast 循环 + 所有 hook 路径禁装
- [V] **实机验证（官版降级 8.0.4 + 新模块）**：`adb logcat` 全程 "Successfully hooked" 出现 **0 次**；FATAL/SIGSEGV 零命中；游戏进主菜单正常运行；GMS 进程不再出现激活日志
- [V] **三态 Toast**（无版本号，原模块过期 Toast 同格式）：仅游戏不匹配「游戏版本不匹配，功能失效，请到 QQ 群下载最新版本游戏！」/ 仅模块过期「模块已更新…」（原文案保留）/ 双失配「游戏版本不匹配且模块已更新，功能失效，请到 QQ 群更新游戏和模块！」——文案动态取（模块过期可能晚于版本判定命中，双失配文案自动升级）
- [V] **isVerdictDone 语义收紧** = 游戏版本已确认 **且** 模块判定出结果；context==null 时 verdict 不放行（版本读不出 ≠ 放行装 hook），hook 路径 200ms 主线程轮询重试（绝不阻塞）
- [V] README「版本门控」章节改写为「启动门控」真实行为（零 Hook + Toast，不再说"不会生效"）
- [V] `:app:lint` EXIT=0（全新 shell）；`assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease` BUILD SUCCESSFUL；`adb install -r` Success（版本号未动 `1.0.4 Alpha 1`）
- 工作区: 干净全推送。`d619d35`(启动门控 feat) → `f2dbc06`(CLAUDE.md)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL, LINT_EXIT=0
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL
adb install -r → Success
实机（8.0.4 官版 + 新模块）：0 次 "Successfully hooked"、无崩溃、游戏正常跑、用户确认"可以了"
```

## 3. 决策与理由
- **游戏版本判定无 fail-open，模块过期判定保留 fail-open** [V]：版本读不出来时继续等（verdict 不放行）——误放行代价=闪退，误等待代价=hook 晚几秒；模块过期沿用离线放行（宁可放过不误伤离线最新版用户）
- **Toast 文案不带版本号** [V]：用户定案，三态文案与原模块过期 Toast 同格式（"XX已更新/不匹配，功能失效，请到…下载更新"）
- **零 Hook 的实现机制** [V]：判定本身需要 Context（读 PackageManager），而游戏进程早期 context 常为 null——`isVerdictDone` 只在版本确认后置位，hook 安装路径轮询等待而非抢跑。修的是真根因：旧"版本门控"只拦 15s native 主路径，early unlock/intro（~2s）+ BillingHook 先装，错版本上打错位偏移即开屏 SIGSEGV（本轮实测复现：旧 APK 在 8.0.4 上加载条阶段崩，日志 "attempting hooks anyway for debugging"）
- **非游戏包进程必须显式放行** [V]：scope.list 注入 GMS/WebView 等包，这些进程 isSupportedVersion 必然 false——旧门控只拦 hook 无感，新门控加 Toast 后误激活=凭空弹 Toast（首轮实测踩到，evaluate 入口按包名放行后消失）
- 继承：LTPO 案魔盒白名单终解 / 版本号红线 / token 三级回落 / 配置 saved_at 仲裁 / 弹窗两变量契约

## 4. 失败的尝试 — 不要再试
- 继承（前向有效）[X]：采样率差异假说 / 游戏插帧假说 / scheduleDraw 治 LTPO 乱跳（通用加固保留）/ iQOO 12 应用自定义高刷入口 / REQUEST_LOGS 广播探活 V1 / UsageStatsManager 深权限 / 日志 mtime 判活 / 单变量弹窗挂载+show / 页内 Box 当全屏遮罩 / 磁盘缓存原图字节 / 凭印象 scp root@ / grep FATAL 零命中≠没崩 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260910003000-handoff.md` §4
- **本轮新增 [X]：`Unsupported game version, attempting hooks anyway for debugging` 旧路径 = 开屏闪退元凶**——旧设计"非匹配版本照样装 hook 便于调试"在错版本上必崩（1.0.3 开 8.0.4 / 1.0.2 开 8.0.6 用户实证），调试动机已由导出日志替代，此路径已删

## 5. 已知坑
- ⚠️ **门控激活 Toast 未在本轮实测亲眼确认** [?]——用户验收"可以了"以零 Hook+不闪退+游戏正常跑为准（日志证实），Toast 视觉弹出未单独确认；双失配文案升级路径（模块过期晚于版本判定命中）同样未实测
- ⚠️ **判定期间 hook 推迟的弱网代价** [V]（设计使然）——在线判定 1~3s；完全离线+无缓存时后台检查 15s 超时才 fail-open 放行模块过期判定（游戏版本判定不受影响，本地同步完成）
- ⚠️ **游戏闪退真凶未定案** [?]（继承）——crash_hook 已装机，等用户复现导出日志；启动门控上线后"错版本装 hook"类闪退已排除，新 crash 现场价值更高
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——Java CrashCatcher 在分发版里，等用户日志
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V]（继承）——环境问题，跳过法已入 CLAUDE.md
- ⚠️ **ABSdiag/TCdiag 高频诊断扰度未处理** [?]（继承）——用户未拍板
- ⚠️ **对话纪律（前向有效）** [V]——①mid-turn 消息逐条消化 ②旧快照不当现在时断言 ③不编造未发生的状态 ④开口前先推演消息内含逻辑。本轮新证：**装机前先验设备上 APK 版本**（`dumpsys package` versionName）——本轮首测崩因是设备跑旧 APK，一行日志（新旧代码的分水岭文案）即可验版本不用猜
- ⚠️ 继承：导出探活广播链 ROM 限流边界 / token 链路只防"无值"不防"陈旧值" / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260910003000-handoff.md` §5

## 6. 下一步（有序）
1. **等反馈用户回话**（启动门控已验收；踏板案已定案；闪退案等现场）
2. （可选）FAQ 条目「iQOO/OriginOS 用户：开线性踏板掉帧请将游戏添加进游戏魔盒」——待拍板放哪（README/设置页/群公告）
3. v1.0.4 正式发布待用户定版（版本号红线；踏板修复+门控+排行榜微调+启动门控已随 Alpha 1 验证，启动门控应写进 Release Notes）
4. （可选，继承）ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- FAQ 条目要不要加、加在哪（README / 设置页说明 / 群公告）
- v1.0.4 正式版版本号与发布时机（Release Notes 是否含启动门控三态 Toast 行为）
- 启动门控 Toast 文案是否需要用户实测微调（当前"QQ 群下载"指向是否符合分发实际）
