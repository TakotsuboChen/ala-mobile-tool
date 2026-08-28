# HANDOFF — 读全文再开始干活

生成时间: 2026-08-28T16:54:11+08:00 · Git HEAD: `bc63e2a`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `bc63e2a` (2026-08-28)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `bc63e2a`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md`（本会话新增 4 条手势层设计/帧率测量约定）+ `references/miuix/`（miuix 源码本地 vendor，查库实现先本地后联网）+ `.handoffs/20260828165411-handoff.md` §2（触摸现象用户原话指针）

## 1. 当前目标
配置页手势修复完成并推送（滑块手势 v3 横向主导裁决 + 页面级方向锁，冷启动帧率恢复基线水平）。**两个长期遗留**：①多指触摸 bug 仍在等复现日志（头号）；②用户明确不满：配置页卡顿问题排查修复过很多次（含 M37 假阳性），**始终达不到 KernelSU 那种任何时候都满帧**——本会话已用 A/B 定位到结构性原因（见 §5），下一阶段主攻方向。

## 2. 已验证状态 — 工作实际停在哪
- [V] **滑块手势 v3 横向主导裁决**（commit `a9e8f4b`）：`SliderGestureBox` 跨 slop 时 |dx|≥|dy| 才独占（消费+自行驱动取值+Edge 触边震动复刻），竖向主导完全放行。修复 v2 回归（见 §4）。功能验证：滑块取值 5.0→8.6dp ✓、横滑切页 ✓。
- [V] **页面级手势方向锁**（commit `a930a78`）：`GestureDirectionLock.kt`（观察器锁轴 + 双 NestedScrollConnection 按轴分量封锁）+ MainScreen/ConfigurePagerMiuix 接线。修复"横滑回位后同手势改竖滑，反之亦然"（Compose AwaitGesturePickup 复活机制）。
- [V] **冷启动帧率 A/B**（同协议：force-stop→start→tap 配置页→6×竖滑→gfxinfo）：HEAD 605/599 帧、18-19% janky、p50 7ms；v2 改动版 105/113 帧、43-49%、p50 17-20ms；**v3 修复后 555 帧、22.2%、p50 8ms**——回到基线水平。二分三轮定位元凶为 SliderGestureBox v2 设计（观察器/封锁器均排除）。
- [V] **构建/lint**：v3 最终态 `./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL，EXIT=0。
- [V] 工作区：干净，工作切片 `a930a78`+`a9e8f4b`、持久文档 `bc63e2a` 已全部推送，main 与 origin/main 同步。
- [?] 上会话继承（TC/ABS 档位文案、弹窗 show 驱动、VS16）：已 commit（aaa9093 系），本会话未触碰。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL，EXIT_CODE=0
冷启动 A/B gfxinfo（配置页 6×竖滑，免触碰窗口）：
  HEAD（stash 基线）   605/599 帧  18.0/19.2% janky  p50 7ms
  v2（回归版）         105/113 帧  43-49%     p50 17-20ms
  v3（当前）           555 帧      22.2%      p50 8ms
功能注入验证：滑块取值 ✓ / 配置→设置横滑切页 ✓（截图核对）
git push ×3 → a930a78 / a9e8f4b / bc63e2a 全部成功
```

## 3. 决策与理由
- **滑块 v3 横向主导裁决** [V]——竖向手势必须放行（v2 无差别独占吞掉全部起点在滑块条上的竖滑），横向才独占+自行驱动取值。否决：纯标准方向消歧（裸 draggable）——用户报过"拖滑块时页面上下左右都动"，独占层+方向锁双层覆盖。
- **方向锁做在增量层不做检测器层** [V]——Compose 被取消的检测器经 AwaitGesturePickup 在同手势内复活（Draggable.kt:1032,1062），检测器层锁不死；NestedScrollConnection 按轴分量吃增量是稳定契约。
- **轴封锁按分量消费** [V]——祖先位置的连接会看到子树所有 scrollable 的增量，整向量消费=劫持邻轴合法滚动（v3 修正前全页竖滚死亡）。
- **帧率问题归因：A/B 实测而非设备论** [V]——用户两次否决"设备性能"归因是对的；同协议 A/B 锁定真实回归。但设备环境负载确有漂移（SystemUI 16-51% CPU 波动），测量必须免触碰+截图核对。
- **冷启动卡顿结构性定位** [V]——app 无 baseline profile（build.gradle 无任何相关配置），配置页单 item 全量重组重页面，JIT 预热造成"冷启动卡约一分钟"；HEAD 本身冷启动即 18-19% janky——**与我的手势代码无关的既有结构性差距**，这就是达不到 KernelSU 满帧的主因候选。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增（手势/测量）
- [X] **滑块手势 v1：同节点 modifier"只消费不取值"独占层** → 滑块完全拖不动（仅快甩偶尔存活）[V]——Compose slop 竞争是自由竞争，`awaitPointerSlopOrCancellation` 在每个 below-slop 事件后走 Final pass 复核，"被任何人消费过"即取消检测；不存在"只挡父级不挡子级"的消费层。不要再试无取值的消费层。
- [X] **滑块手势 v2：DOWN 起无差别独占+自行取值** → 冷启动配置页帧数掉到基线 1/6、用户感知"滑动触发不了" [V]——全宽滑块条密集页面上，起点在条上的竖滑全被吞。独占手势层必须方向裁决（v3）。
- [X] **"设备性能/环境负载"归因卡顿** → 被用户两次否决，A/B 证实真实回归 [V]——但注意设备负载确有漂移（SystemUI 16-51%），测量必须免触碰+截图核对，且环境归因只能作为最后排查项而非第一反应。
- [X] **GestureAxisBlocker 整向量消费** → 全页竖滚死亡 [V]——祖先位置连接会看到子树所有 scrollable 增量，必须按轴拆分量（`Offset(available.x, 0f)`）。
- [X] **封锁条件两次写反**（`lockedAxis == axis` 语义混淆）→ 左右滑全灭/配置页竖滚死 [V]——封锁器参数语义定为"axis=节点自身轴，另一轴持锁时封锁自己"。
- [X] **`viewConfiguration.pointerSlop()`** → 项目解析的 compose-ui 1.11.4 无此 API（仅 M3 main 有），用 `touchSlop` [V]——读上游 main 源码定 API 前先核对项目解析版本。
- [X] **perfetto shorthand tags（`-t 30s sched gfx view`）抓应用数据** → 用户版 0 sched 事件、无应用 slice [V]——改用 `dumpsys gfxinfo` 同协议 A/B + 截图核对，够用且快。
- [X] **测量期间用户手触** → 注入滑动与真实触摸混流，0 帧渲染假象/读数漂移 [V]——注入前 screencap 核对页面状态 + 请用户免触碰。
- [X] **查 miuix 源码先 WebFetch GitHub（404）再想 Tavily** → 源码就在项目 `references/miuix/` [V]——已入持久记忆（references-dir-vendored-deps）。
- [X] **横滑 A/B 测试"左右往返"后误判测过配置页** → 结束页恰回原位，截图才发现一直在概览页 [V]——每轮测量前后截图核对。

### 继承（上会话及更早，全部 [?] 除标注外）
- [?] 押 p0 做主旋钮→用户实测推翻；usesABS 恢复漏置位（Edit "exactly the same"=没改）；档位 0.15/0.30/0.50 差异化不足；制动压力 75% 下限不可观察；0x3D4 读法未解；bang-bang 方向反直觉。
- [X] 只写 TCLSlip=eps 挡死起步段；每帧写 ctor 默认≠真值；切回默认写 ctor 默认残留；llvm-objdump 传文件偏移；assembleRelease 不跑 lint（推送前必须 `:app:lint`）；TractionControlDynamicAssist 仅玩家车；IRDSPlayerControls.tractionControl 死字段；math 公式内中文/裸竖线/operatorname；AI 车 is_player_controller 过滤不可靠。
- [V] 只写 absEnable 不够，唯一有效门控是 per-wheel usesABS；后台 pthread 调 Unity API 崩溃；dlopen 不可用改 ELF 符号查找；proxy_fixed_update is_player 块外调 hide_pedals_tick 失效；adb install -r 覆盖安装前必须 force-stop；awaitLsposedSettled 不可靠；kkgithub 404 用 gh-proxy。
- [X] 触摸 bug：id 跟踪+findPointerIndex 修不了（根因不在此层）；静态审查六类机制推演均无法自洽；插桩前版本日志零数据；proxy_shift_up 打日志洪水（21min 18810 条）。

## 5. 已知坑
- ⚠️ **配置页"任何时候满帧"未达成（用户核心不满，本会话确认）** [V]——A/B 实测 HEAD 自身冷启动配置页竖滑即 18-19% janky（p50 7ms），热身后 0.7-2%；主因候选：**app 无 baseline profile**（build.gradle 无任何相关配置）+ 配置页单 item 全量重组重页面 + JIT 预热约一分钟。KernelSU 对比目标：任何时候满帧。修复方向：搭建 androidx.baselineprofile 基础设施（需生成器 module + 连接设备跑 profile 模板）+ 可能需要拆分配置页单 item 结构。设备环境噪声大（SystemUI 16-51% CPU、swap 5G、858 任务）会放大体感。
- ⚠️ **多指触摸 bug 未解（头号任务）** [?]（继承）——现象：一手按踏板另一手点空白，行程填充漂到 100%、反复点空白反复抽搐（原话：`.handoffs/20260828132840-handoff.md` §2）；插桩就位等复现日志；三候选根因待裁决（意外事件重排/坐标混入他指/pairip relayout）。
- ⚠️ pager `startDragImmediately` settle 回抓在 Initial pass 无条件认领 DOWN，指针层不可拦截 [V]（本会话确认机制）——滑块抓取瞬间的 settle 中断闪动为已知残余。
- ⚠️ 滑块 v3 独占期间内部 isDragging 失效，拖拽态快弹簧回落慢弹簧（视觉跟随轻微滞后）[?]——用户未抱怨，暂不动。
- ⚠️ 0x414（fullAbsEnableSpeed）疑似死参数 [?]；0x3D4 读法未解 [?]；TC 开关位每帧重算（已绕过）[?]；TC 削减窗口图单次实测 [?]；游戏内 ABS 设置对物理无效 [?]；.rodata 常数不可直接改 [?]；计时赛 IRDSUIMobileControls 晚 ~2s [?]；LSPosed 下 Remote Preferences 受限→setComponent 显式广播 [?]；BillingHook NPatch 模式永远失败 [?]；flyme 后台白名单/miuix TopAppBar spring/广播 JSON 不含 position/OffsetTable.AUDIO_SOURCE_SET_VOLUME 实为 TweenVolume.set_volume [?]；GitHub 公式/Mermaid 渲染坑（先读持久记忆）[?]；IL2CPP ARM64 扫描三坑 [?]；offsets_sheet 0x1A62E10 勘误未订正 [?]。

## 6. 下一步（有序）
1. **等用户回传触摸 bug 复现日志**（头号任务，等待中）——force-stop 后装插桩版、确认日志开关开启、复现"一手踏板+一手点空白"、导出日志回传；分析三候选根因。
2. **冷启动满帧结构性修复（用户点名长期目标）**——搭建 androidx.baselineprofile：加 plugin + 生成器 module，用连接的 MEIZU 20 跑 profile 生成（覆盖冷启动→三页切换→滑块拖动路径）；评估配置页单 item 拆分（分 section 多 item）降低首组合成本。动手前先重跑 A/B 基线确认无手势回归。
3. ABS 二期（低优先级）：kP/κ 解析、弯中自动收紧、overlay ABS 视觉指示、0x410 低速退出档。
4. AI 车白名单长期观察（继承）。

## 7. 留给用户的开放问题
- 冷启动卡顿：你能接受的验收标准是什么？"每次冷启动首分钟不卡"还是"达到 KernelSU 完全体"？前者 baseline profile 大概率够，后者可能还需拆配置页单 item 结构（改动大，需单独评估）。
- 触摸 bug 复现日志回传后：跳变瞬间 action 序列是什么？（决定根因 a/b/c 走向）
- 用户测试触摸时游戏原生踏板按钮是隐藏还是显示？（影响"游戏原生输入接管"假设权重）
- v3 手势手感（滑块横向独占、竖向放行）日常使用后是否有新反馈？