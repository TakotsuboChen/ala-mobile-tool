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
| `LAPgate` | 非计时赛会话首个圈段事件 | 挂起提示（每会话一次） |
| `LAPsession[awake]` | LLV.Awake（场景加载瞬间） | 模式信号全集（静态集，进赛道即打，无需驾驶） |
| `LAPsession[sector]` | 会话首个圈段事件 | 同上 + odometer 链真实值（champ/isQuali），对照补全 |

## 4. 模式门禁（终版）

**需求定案（用户，2026-08-31）**：只记计时赛，其他会话一律不记。
**主信号**：`odometerHandler.champManager (0x4D8)` → `ChampionshipManager.isTimeAttack (0x20)`。

| 会话类型 | champManager | isTimeAttack | 结果 |
|---|---|---|---|
| 计时赛 | 非 NULL | **1** | 记录 ✓ [V] |
| 比赛周第三节自由练习 | 非 NULL | **0** | 挂起 ✓ [V] |
| 快速模式正赛 | **NULL** | 未知 | 挂起（NULL 分支）✓ [V] |

- v1 死路：`champ==NULL → 放行`——快速正赛 champManager 恒 NULL，正赛圈被误记
  （Shanghai 1:38.354 事件）。v2 改"NULL = 模式未知 = 挂起"，三路径全堵。
- 切回计时赛时自动清最佳圈 + 重探赛道（`g_mode_gated` 复位路径），模式间数据零串写。

### 4a. 模式信号全枚举矩阵（2026-08-31 实机 9 会话采样，全 [V]）

采样方式：`LAPsession[awake]` 诊断行（hook LLV.Awake，场景加载瞬间单发；
每模式**进赛道即退，无需驾驶**）。采样行程：生涯澳大利亚
FP1/FP2/FP3/Q1（无成绩未进 Q2/Q3）/正赛 → 比赛周上海正赛 → 计时赛巴林 →
快速比赛伊莫拉 → GRAND FESTIVAL 西班牙。

位定义：`sessBits` = IRDSStatistics 静态三布尔
（bit0=isRaceSession / bit1=isFreePracticeSession / bit2=timedSession）；
`gvBits` = GlobalVariables 静态三位
（bit0=isMultiplayerMatch / bit1=isRace / bit2=isGrandFestival）；
cd* = `GlobalVariables.championshipData` 实例字段。

| # | 会话 | sessBits | gvBits | cdSess | cdRound | cdTrack | fullQuali |
|---|---|---|---|---|---|---|---|
| 1 | 生涯·澳大利亚 FP1 | 0 | 0 | 0 | 0 | 0 | 1 |
| 2 | 生涯·澳大利亚 FP2 | 6 | 0 | 1 | 0 | 0 | 1 |
| 3 | 生涯·澳大利亚 FP3 | 6 | 0 | 2 | 0 | 0 | 1 |
| 4 | 生涯·澳大利亚 Q1 | 6 | 0 | 3 | 0 | 0 | 1 |
| 5 | 生涯·澳大利亚**正赛** | **4** | 2 | 6 | 0 | 0 | 1 |
| 6 | 比赛周·上海**正赛** | **1** | 2 | 6 | 0 | 1 | 0 |
| 7 | 快速比赛（伊莫拉） | 1 | 0 | 0* | 0 | 0 | 0 |
| 8 | 计时赛（巴林） | 0 | 2 | 0* | 0 | 0 | 0 |
| 9 | GRAND FESTIVAL（西班牙） | 1 | **2**(bit2=GF) | 0* | 0 | 0 | 0 |

\* 全零 champData 实例——快速类模式挂一个空 ChampionshipData，非 NULL。

**实测裁决**：

- **节次枚举（cdSess）**：0/1/2/3 = FP1/FP2/FP3/Q1，6 = 正赛（两个正赛样本
  独立证实）。Q2/Q3 预期 4/5（未采样，[?]）。
- **正赛指纹分裂**（重要）：生涯正赛走 `timedSession` 置位路径（sessBits=4），
  比赛周正赛走 `isRaceSession` 路径（sessBits=1）——**两条代码路径，勿把
  任一单独指纹当"正赛"判据**。
- **fullQuali 是周末级属性**：生涯澳大利亚站=1（全程排位制 Q1/Q2/Q3），
  比赛周上海=0（短排位）。非生涯全局设置。
- **GF 专用位**：`GlobalVariables.isGrandFestival`（gv bit2）一击命中，
  GF 判定首选信号。
- **计时赛负指纹**：sessBits=0（IRDS 三位全零）+ gvBits=2（isRace 亮）+
  champData 新建档全零。计时赛不占 IRDS 会话位——主判据仍只有
  champManager.isTimeAttack。
- **raceModes 全场景=0**（含正赛），确认无区分力，维持排除。
- 门禁回归：9 会话在 v2 门禁（ta 硬判 + NULL=挂起）下行为全部正确——
  计时赛记录 ✓，其余 8 场（练习×3、排位、生涯正赛、比赛周正赛、快速比赛、
  GF）全挂起 ✓，零误记。

**未采样空格**：Q2/Q3（cdSess 4/5 待证）、多人房间（gv bit0 挂起实测缺）、
排位第二节起 fp 位是否回落 [?]。`LAPsession[sector]` 对照行（带
champ/isQuali 真实值）尚未采过——下次进赛道过一次 S1 线即补全。

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
4. 实机跑一场快速正赛：只出现 `LAPgate`/`LAPsession`，无圈速行。
