# LAP_HOOK_NOTES — 计时赛有效圈速监听工程笔记

> 功能：`native/src/lap_hook.c`（log-only，无 UI）——计时赛会话内把每圈判定
> 与会话最快有效圈写入日志；16 条赛道自动识别。
> 生成：2026-08-31 · 证据标注同 HANDOFF 惯例：[V] 实机/dump 实证 · [?] 未裁决。

## 1. Hook 层次与信号链

```
hook ①  IRDSLevelLoadVariables::Awake (RVA 0x199DE28)
          └─ 捕获 LLV 单例（DontDestroyOnLoad 但每场景加载都重建，
             实机一次快速模式加载流程触发 4 次 Awake）→ 新会话重置
hook ②  odometerHandler::HandleSectorsTimes (RVA 0x1A0A1C4)
          ├─ 圈段事件簇（每圈 ~3 簇，簇内连发数帧）
          ├─ 圈完成判定（order==2 事件）
          ├─ 对照读链：this+0xF8 stGUI → +0x68 stadistics
          │             → absoluteFastestLap(0xC0)/fastestTimeAuthor(0xC4)
          ├─ this+0xE8 targetSpeed → +0xF0 navigateTWp → +0x240 bestLapTimeInfo
          └─ 模式门禁：this+0x4D8 champManager → +0x20 isTimeAttack
probe（无 hook）LAPscene：SceneManagerHelper.get_ActiveSceneName /
          get_ActiveSceneBuildIndex + CommonUtilities.GetGPIndex，
          经 il2cpp 官方导出（class_from_name + runtime_invoke）调用
```

- 安装：15s 延迟路径 `NativeBridge.initLap(...)`（独立 JNI，不动 init() 44 参数签名）
- 偏移来源：`OffsetTable.kt`（`IRDS_LEVEL_LOAD_VARIABLES_AWAKE` /
  `ODOMETER_HANDLER_HANDLE_SECTORS_TIMES`），RVA 对照见
  `il2cpp-dumps/v8.0.4/offsets_sheet.csv` "Lap timing" 节
- 红线：纯透传只读、不写游戏字段；事件簇路径允许日志（非每帧路径）

## 2. HandleSectorsTimes 语义裁决（实机三轮，全部 [V]）

| 轮次 | 事件 | 裁决 |
|---|---|---|
| v1 设计 | 按 dump 静态判读 | 假设：过线事件，~3 次/圈，order 1-based |
| 第一轮（165 行日志） | 同 order 连续多行、时间戳 13–27ms | 误判"每帧刷新"→ 实为**圈段过线事件簇**（HUD 刷新期连发） |
| 第一轮 | 判 `order==3` 永不命中 | **order 0-based（0/1/2）** |
| 第二轮（五圈） | 第 5 圈过圈行"消失" | `order==2` 事件本身即圈完成（携带完整圈时）；v1 等 2→0 回绕延迟到下一圈 S1（30s+）→ v2 改为收到即判 |
| 第三轮（双赛道+正赛） | 圈 2 S3 切弯无中间行 | valid 翻转与圈完成同帧时 `LAPlap` 行被吞——`LAPinv` 自含全部信息，非数据损失 |

**最终语义**：`sectorOrder` 0-based；`sectorTime`=完成段耗时；`totalLapTime`
仅 order==2 携带完整圈时（order 0/1 恒 0）；`validLap` 为**持续状态位**
（切弯即降 0 直到本圈结束）；`sectorQuality`=分段快慢色码（2=快/绿系，
0=慢/黄系，快慢参照为自己历史分段——实测佐证非权威，[?] 内部规则未深究）。

## 3. 日志行格式

| 行 | 触发 | 内容 |
|---|---|---|
| `LAPtrack` | LLV.Awake | LLV 捕获 + trackToRace 原文（恒 'MobileScene'，废签留观测） |
| `LAPscene` | 首次圈段事件（每场景一次） | `scene='<Unity场景名>' buildIndex=N gpIndex=M` |
| `LAPlap` | 圈段边界 / validLap 翻转 | 原始值 + absFastest 对照 + pCur 当前圈分段 |
| `LAPbest` | 过圈且有效且刷新会话最佳 | **当前最快有效圈速** |
| `LAPdone` | 过圈且有效未破纪录 | 圈时 + 会话最佳 |
| `LAPinv` | 过圈但无效 | 圈时（切弯圈也有完整时间） |
| `LAPgate` | 非计时赛会话首事件 | 挂起提示（每会话一次） |
| `LAPmode` | 挂起态的圈完成事件 | 六信号诊断行（终版 gate 取证材料） |

## 4. 模式门禁（终版）

**需求定案（用户，2026-08-31）**：只记计时赛，其他会话一律不记。
**主信号**：`odometerHandler.champManager (0x4D8)` → `ChampionshipManager.isTimeAttack (0x20)`。

| 会话类型 | champManager | isTimeAttack | sessBits | 结果 |
|---|---|---|---|---|
| 计时赛 | 非 NULL | **1** | 未采样 | 记录 ✓ [V] |
| 比赛周第三节自由练习 | 非 NULL | **0** | 6 (fp=1,timed=1) | 挂起 ✓ [V] |
| 快速模式正赛 | **NULL** | 未知 | 1 (race=1) | 挂起（NULL 分支）✓ [V] |
| 排位 | [?] | [?] | [?] | 按 ta=0/NULL 挂起（未实测） |

- v1 死路：`champ==NULL → 放行`——快速正赛 champManager 恒 NULL，正赛圈被误记
  （Shanghai 1:38.354 事件）。v2 改"NULL = 模式未知 = 挂起"，三路径全堵。
- 排除信号：`raceModes`（正赛/练习均 0，无区分力）；`isQuali`（练习赛也为 1，
  语义是"非正赛圈速 UI 状态"而非排位）；`sessBits` 降级旁证（`timedSession`
  在排位/练习为 1，与"计时赛"字面义相反，勿当主判据）。
- 切回计时赛时自动清最佳圈 + 重探赛道（`g_mode_gated` 复位路径），模式间数据零串写。

## 5. 赛道识别

16 赛道权威表：`TRACK_IDENTIFICATION.md`（APK BuildSettings 场景表 L1 权威 +
metadata 字面量 L2 + 实机 LAPscene L3 交叉，4/16 已实测全部吻合）。
要点：场景名即赛道名（`Monza`/`Shangai`/`Barcellona`/`a1Ring`/`MelbourneRifatta`…，
**拼写是游戏原始拼写勿纠正**）；`gpIndex = buildIndex − 2`。

## 6. 已知坑与红线

- ⚠️ **圈完成判定挂在 order==2 事件上，勿改回"等 order 2→0 回绕"**——回绕在下一圈
  S1 过线才发生，会重现"第 5 圈消失"。
- ⚠️ **`bestLapTimeInfo` (0x240) 是当前圈实时分段**，999.0=段未开始哨兵，
  **不是历史最佳**（pCur 命名即为此）；历史最佳在 `IRDSStatistics.bestLapTimesInfoByCar`
  字典（未读，Dictionary 布局成本高，当前无需求）。
- ⚠️ `absoluteFastestLap` 更新滞后于过圈（游戏 SubmitForFastestTime 时机），
  模块本地 best 比游戏字段早一个读点——未来 UI 应读模块自己的 best。
- ⚠️ `author`（fastestTimeAuthor）是**车号**非圈序号（实测 4/20）。
- ⚠️ 每帧路径红线：若未来把日志加进非边界分支会洪水——日志只许在
  `order_changed || valid_changed` 边界内。
- [?] `sectorQuality` 精确取值表（0/2 实测出现，1 未观测）。
- [?] 单圈 S3 切弯时 valid 翻转与圈完成同帧、无中间行——可接受，未深究。

## 7. 验证清单（下次游戏升级时）

1. 重跑 Il2CppDumper → `offsets_sheet.csv` 对照本表 RVA（8.0.0→8.0.4 增量
   `+0xC2DC`/`+0xD598` 无全局规律，须逐方法核对）。
2. 重解 `data.unity3d` BuildSettings 场景表（UnityPy）核对 16 场景 buildIndex。
3. 实机跑计时赛一圈：`LAPscene`/`LAPbest` 正常 + `track` 为场景真名。
4. 实机跑一场快速正赛：只出现 `LAPgate`/`LAPmode`，无圈速行。
