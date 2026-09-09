# HANDOFF — 读全文再开始干活

生成时间: 2026-09-09T21:54:12+08:00 · Git HEAD: `45640e8`（模块仓已推送）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: main @ `45640e8`（2026-09-09，工作+持久文档提交后的 HEAD）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `45640e8`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: 记忆 `pedal-lag-probe-device-facts.md`（踏板案已定案）+ 本文件 §2

## 1. 当前目标
轻量 UI 会话：**排行榜两处微调（已完成并实机验收"OK 了，完美"）**。悬而未决旧目标：游戏进程闪退定案（等 crash 现场）、模块 App 闪退定案（等用户日志）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **排行榜奖牌放大 50%**：BoardRow 名次槽前三名（rank ≤ 3）fontSize 15→22.5sp，槽位 28→32dp，数字名次维持 15sp
- [V] **刷新指示器移到筛选卡下方**：筛选卡搬出 PullToRefresh 手势区（钉顶不随滚动），结构 = Column{ 筛选卡（吃 topPadding）+ PullToRefresh(weight(1f)){ LazyColumn } };  指示器出现在筛选卡与榜单行之间
- [V] **滚动缺角修复**：LazyColumn 加视口顶缘 `.clip(RoundedCornerShape(topStart/topEnd 16.dp))`——滚动中行滚出视口顶边保持圆角（用户验收：完美）
- [V] `:app:lint` EXIT=0；`assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease` BUILD SUCCESSFUL；`adb install -r` Success（版本号未动 `1.0.4 Alpha 1`）
- 工作区: 干净全推送。`28fd2af`(排行榜UI) → `45640e8`(CLAUDE.md)

### 测试/build 输出（真实退出码）
```
./gradlew :app:lint → BUILD SUCCESSFUL, EXIT=0
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease → BUILD SUCCESSFUL
adb install -r → Success
用户实机验收："OK 了，完美"（滚动圆角场景）
```

## 3. 决策与理由
- **筛选卡必须搬出 PullToRefresh 手势区** [V]：miuix RefreshHeader 是 content 之前的第一个孩子，指示器永远出现在 PullToRefresh 内容最上方——筛选卡若还是列表第一项就随内容下移，指示器只能落在页面大标题下。钉出去是唯一解法。代价：筛选卡不再随列表滚动（语义上"刷新加在筛选下面"即预期）
- **视口圆角归容器管，不归内容管** [V]：行级 `boardRowShape` 的首行圆角是"数据第一行"的内容属性，滚动后视口顶部露出的是中间行（直角）被平直截断。`clip` 上移到视口层与内容无关。Modifier 顺序：`padding(horizontal)` 在 `clip` 之前 → 裁切轮廓与行宽对齐
- 继承：LTPO 案不再追模块侧对策（魔盒白名单是终解）/ ForceUpdateGate 判定先于一切 hook / 版本号红线 / token 三级回落 / 配置 saved_at 仲裁 / 弹窗两变量契约

## 4. 失败的尝试 — 不要再试
- 继承（前向有效）[X]：采样率差异假说（Takotsubo 480Hz 没事，当场否证）/ 游戏插帧假说（魔盒昨晚已移出+游戏不在白名单，双死）/ scheduleDraw 治 LTPO 乱跳（实测不够，通用加固保留）/ iQOO 12 应用自定义高刷入口（OriginOS 4 已移除）/ REQUEST_LOGS 广播探活 V1 / UsageStatsManager 深权限 / 日志 mtime 判活 / 单变量弹窗挂载+show / 页内 Box 当全屏遮罩 / 磁盘缓存原图字节 / 凭印象 scp root@ / grep FATAL 零命中≠没崩 / IL2CPP 扫描三坑 / FPSIMD 污染 / 未经同意改版本号——详见 `.handoffs/20260909215000-handoff.md` §4

## 5. 已知坑
- ⚠️ **游戏闪退真凶未定案** [?]（继承）——crash_hook 已装机，等用户复现导出日志；crash 文件 pc 偏移对拍 `OffsetTable.kt` 定案
- ⚠️ **模块 App 闪退排查未结案** [?]（继承）——Java CrashCatcher 在分发版里，等用户日志
- ⚠️ **lintVitalAnalyzeRelease 本机崩溃** [V]（继承）——Kotlin FIR 在 AboutScreen.kt 内部错误，环境问题；跳过法已入 CLAUDE.md；未根治（低优先级，CI 不受影响）
- ⚠️ **ABSdiag/TCdiag 高频诊断扰度未处理** [?]（继承）——0.5s 一组常驻刷 logcat 与 2MB 文件，用户未拍板
- ⚠️ **对话纪律（前向有效）** [V]——①mid-turn 消息逐条消化 ②旧快照不当现在时断言 ③不编造未发生的状态 ④开口前先推演消息内含逻辑
- ⚠️ 继承：导出探活广播链 ROM 限流边界 / token 链路只防"无值"不防"陈旧值" / lint baseline 13 条失效 / NPatch 管理 binder 时序 / 双仓赛道中文名两份硬编码——详见 `.handoffs/20260909215000-handoff.md` §5

## 6. 下一步（有序）
1. **等反馈用户回话**（踏板案/闪退案均已排雷或等现场，无需主动动作）
2. （可选）FAQ 条目「iQOO/OriginOS 用户：开线性踏板掉帧请将游戏添加进游戏魔盒」——待拍板放哪（README/设置页/群公告）
3. v1.0.4 正式发布待用户定版（版本号红线；踏板修复+门控+排行榜微调已随 Alpha 1 验证）
4. （可选，继承）ABSdiag/TCdiag 降频 / 游戏闪退 crash 现场定案

## 7. 留给用户的开放问题
- FAQ 条目要不要加、加在哪（README / 设置页说明 / 群公告）
- v1.0.4 正式版版本号与发布时机（踏板修复+强制门控+排行榜 UI 是否写进 Release Notes）
- 强制升级门控的 Toast 文案与触发阈值（当前：落后最新 Release 即触发）等真实升级场景验证
