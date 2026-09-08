# HANDOFF — 读全文再开始干活

生成时间: 2026-09-08T21:58:40+08:00 · Git HEAD: `8f51d22`（模块仓已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `8f51d22`（2026-09-08，工作+持久文档提交后的 HEAD）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `8f51d22`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: 本文件 §2（导出 V5 契约）+ `native/src/pedal_hook.c` 注释（两版介入写点对拍结论）

## 1. 当前目标
本轮四特性全部完成并装机验证：①ABS 指示灯刹车必闪修复（8.0.6 偏移）②配置读取新鲜度仲裁（治 NPatch 陈旧 remote 快照）③日志收编红线（15 文件 148 处直用清零）④日志导出 V5（3s 探活门控+转圈遮罩+弹窗重做）。悬而未决旧目标仍是游戏进程闪退定案（等用户复现 crash 现场）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **ABS 指示灯修复**：根因 = `abs_rf_intercept_install` 硬编码 8.0.4 地址 `0x1A7B7DC`，升 8.0.6 后落在每帧必经的普通 tempBrakeF 写入路径 → 刹车必命中必闪。8.0.6 反汇编对拍（方法体同构仅平移 +0x27E0）：介入写点 = `0x1A7DFBC`（RoadForce RVA 0x1A7DB3C + 0x480，函数内偏移不变），已收进 `OffsetTable.IRDS_WHEEL_ROADFORCE_ABS_WRITE` 经 init 链注入，native 硬编码清零。用户确认功能正常
- [V] **配置新鲜度仲裁**：`ModConfig.readFromTargetProcess` 三源（remote prefs/ConfigProvider/本地文件）全取比 `saved_at`（epoch millis）最新者胜出；写入侧 `ModConfig.write` 带 `KEY_SAVED_AT`。实机验证：12:29/12:32 两次冷启动全部读到最新配置（关 ABS 读到关），证据 = remote JSON 长度 929（新）vs 旧快照 955
- [V] **日志收编**：`Logger` 新增带 tag 重载组（v/d/i/w/e(tag,msg[,throwable])，签名对齐 android.util.Log）；15 文件 148 处直用全部改 `Logger.x`，删除 15 个无用 import；残留 grep = 0。红线已入 CLAUDE.md（附自查 grep 命令）
- [V] **日志导出 V5**：`awaitFreshLogs`（发 REQUEST_LOGS 后 3s 内轮询 cache 文件 mtime，更新=游戏活体）→ true 才导出+分享，false 收圈+Toast「请先启动游戏！」**不导出**（禁止回落旧缓存，用户定案）；转圈 = 窗口级 Dialog 全屏遮罩 + miuix `InfiniteProgressIndicator`，最短显示 800ms（`holdMinLoadingThenHide`）；弹窗标题「确保游戏在运行中」居中粗体、正文左对齐、左灰「取消」右蓝「继续导出」；10 秒等待规则已删除（`requestFreshLogs` 整个函数删除）
- [V] **弹窗动画根治**：单变量当挂载条件+show 参数会跳过退出动画；拆 `gameDialogMounted`（挂载）+ `gameDialogShow`（show），`onDismissFinished` 里才摘除——EulaDialog 同款契约，已入 CLAUDE.md
- [V] 硬编码全面复查：native 代码级 RVA 常量 = 0（注释文档性地址除外）；Kotlin 绕过 OffsetTable 的 RVA = 0；`unlock_hook.c` 3 处 fallback 与 OffsetTable 逐一相等（CLAUDE.md 登记的合法例外）
- [V] `:app:assembleRelease` EXIT=0；`:app:lint` EXIT=0（0 errors, 54 warnings 全为既有）；装机 Success（版本号未动 `1.0.4 Alpha 1`）
- 工作区: 干净全推送。`e20be96`(指示灯) → `5603c48`(仲裁+导出V5) → `ad96bc5`(日志收编) → `8f51d22`(CLAUDE.md)

### 测试/build 输出（真实退出码）
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL, EXIT=0
./gradlew :app:lint → 0 errors, 54 warnings, 5 hints (3 errors+17 warnings filtered by baseline), EXIT=0
adb install -r → Success（1.0.4 Alpha 1，未升版）
实机日志：探活 754ms 命中（21:22:07 fresh logs arriving）/ 超时路径 3000ms 判死（21:21:12）
```

## 3. 决策与理由
- **介入写点定位法 = 函数内偏移不变 + 分支结构对拍** [V]：8.0.4 与 8.0.6 的 RoadForce 方法体完全同构（介入写点都在 RVA+0x480，`b.le` 绕过结构一致），比单看指令字节可靠。OffsetTable 注释已写升版核对清单（确认 RVA+0x480 仍是 `str s0,[x19,#0x3EC]` 且位于 b.le 后）
- **配置仲裁用 saved_at 时间戳而非通道优先级** [V]：三通道都可能陈旧（NPatch remote=陈旧快照实证/Provider 部分设备 Unknown authority/本地文件依赖广播送达），固定优先级隐含"高优先级永远更新"的假前提。写入方唯一（ConfigActivity）是时间戳可靠的前提
- **探活最终方案 = 3s 内游戏推送到达与否**（用户定案）：导出的游戏日志段必须来自本次推送，语义与导出物对齐；否决 UsageStatsManager（需深权限，用户否决"藏得深的权限"）、否决日志 mtime（主菜单不写日志，mtime 停更≠进程死）、否决 ActivityManager API（API 21+ 只返回调用者自身，官方文档"only intended for debugging"）
- **广播往返探针的边界**：部分 ROM 对后台应用定向广播限流（V1 实机 4 连败、游戏侧 0 次收到）——但本机（ColorOS 系）实测「游戏前台→切模块导出」往返正常（754ms 命中）。若未来有用户反馈导出误判，先查 ROM 广播限流
- **转圈最短 800ms** [V]：成功路径 754ms 完成，无下限则一闪而过（用户反馈"没有转圈"）；窗口级 Dialog 替代页内 Box（页内会被 pager 裁剪、盖不住底栏）
- **日志红线 + 弹窗两变量契约 + 排版铁律（标题居中粗体/正文左对齐）入 CLAUDE.md**：稳定约定每会话必达
- 继承：版本号红线 / token 三级回落 / order==2 挂圈 / 积分公式 v40 / NPatch 管理器唤醒注入

## 4. 失败的尝试 — 不要再试
- **REQUEST_LOGS 广播往返探活（V1 探针）** [X]——游戏前台玩着切到模块 4 秒后探测，广播送不到动态注册的 ConfigReceiver，实机 4 连败、游戏侧 `REQUEST_LOGS received` 0 次。ROM 对后台应用定向广播限流。**但注意**：V5 的 `awaitFreshLogs` 用的同一条广播链是用户实测可用的（前台切出场景）——区别在 V1 是"游戏在后台时探测"，V5 是"用户刚从游戏切出"（广播缓冲未清）。两场景勿混
- **UsageStatsManager 探活（V2）** [X]——需要 PACKAGE_USAGE_STATS 深权限，用户否决（"不希望麻烦地去授予什么藏得很深的权限"）；未授权时"点击没反应"（既不弹窗也不导出）是 V2 的第二个 bug
- **日志 mtime 判活（V3 候选）** [X]——游戏在主菜单/暂停时日志全停，mtime 停更 3 分钟而进程健在，实机 stat 验证
- **删除 10s 等待前直接弹 Toast 的 V3 流程** [X]——`export()` 内部残留的 10s REQUEST_LOGS 等待会顶掉 3s 兜底：Toast 弹了但 10s 后老流程照样导出（用户实测）。修复 = 彻底删除 `requestFreshLogs`，export 不再自带等待
- **单变量驱动弹窗挂载+show** [X]——退出动画被跳过（组件被立即摘出组合树）。两变量拆分已入 CLAUDE.md
- **页内 Box 当全屏转圈遮罩** [X]——被 pager 布局裁剪、盖不住底部导航栏。窗口级 Dialog 替代
- **磁盘缓存存原图字节 / pruneAvatarCache 按 URL 集合清理 / 凭印象 scp root@ / Edit 契约文档覆盖过宽** [V]（继承，前向有效，见 `.handoffs/20260908221500-handoff.md` §4）
- 继承（再往前，见 `.handoffs/20260907005000-handoff.md` §4）：grep FATAL 零命中≠没崩 / PaddockPagerMiuix 脏 key 推 URL / SettingsPagerMiuix 删 UI 块保住兄弟起始行 / native_log_init 悬空函数体 / 国旗 compact 逐码点剥空格 [X] / ConfigProvider 读 filesDir [X] / 未经同意改版本号 [V] / VPS 禁 cargo build / mdns 端口漂移 / lap_hook 全套 / IL2CPP 扫描三坑 / FPSIMD 污染

## 5. 已知坑
- ⚠️ **游戏闪退真凶未定案** [?]（继承）——crash_hook 已装机，等用户复现导出日志；crash 文件 pc 偏移对拍 `OffsetTable.kt` 定案
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——Java CrashCatcher 在分发版里，等用户日志
- ⚠️ **1.0.3 token missing 与配置旧值是同族不同病** [V]（本会话定案）——那批用户是 NPatch ② remote 返回 null（空壳）+③ ConfigProvider Unknown authority 双死；本机现证 NPatch ② 返回**陈旧快照**（行为已从"返回空"变成"返回旧值"，更危险）。token 链路 `loadAuth` 同样只防"无值"不防"陈旧值冒充权威"——若后续有 token 玄学报告，考虑给 token 也做时间戳对赌（本会话范围控制未动）
- ⚠️ **ABSdiag/TCdiag 高频诊断扰度未处理** [?]——0.5s 一组 ×4 行常驻刷 logcat 与 2MB 文件，标定已完成可考虑默认关/降频（用户未拍板）
- ⚠️ **导出探活依赖广播链，ROM 差异是边界** [?]——本机实测可用（754ms），激进 ROM 后台限流是理论边界；误判反馈先查广播送达（游戏侧 grep "REQUEST_LOGS received"）
- ⚠️ 继承：lint baseline 13 条失效 / LogExporter java/native 段可各自回落不同时期缓存 [?]（freshness 门控后风险已降，未验）/ ConfigProvider `Unknown authority` 该设备存在 / NPatch 管理器 binder 时序 / paddock 版本三处同步无校验 / 双仓赛道中文名两份硬编码 / Garage 206 测试对象 / SA_ONSTACK 64KB 未实测

## 6. 下一步（有序）
1. **等用户反馈游戏闪退 crash 现场**（继承主目标）→ 对拍 OffsetTable 定案 → 修元凶（嫌疑：pedal 写线程悬空写）
2. v1.0.4 正式发布待用户定版（版本号红线；本轮四特性已随 Alpha 1 装机验证）
3. （可选）ABSdiag/TCdiag 降频或默认关 / lint baseline 重生成 / 服务端 axum 访问日志层 / token 时间戳对赌

## 7. 留给用户的开放问题
- 闪退用户反馈何时能拿到（crash 现场定案的唯一依赖）
- v1.0.4 正式版版本号与发布时机（是否把本轮四特性写进 Release Notes）
- ABSdiag/TCdiag 诊断日志是否降频（标定期已过，现常驻刷屏）
- 1.0.3 token missing 用户群是否需要回访验证（本轮新鲜度仲裁可能已顺带治愈部分场景，未验）
