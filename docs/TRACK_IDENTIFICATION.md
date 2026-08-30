# 赛道识别表（16 条 GP 赛道）

> 生成时间: 2026-08-31 · 适用游戏版本: Ala Mobile 8.0.4 (versionCode 200146)
> 用途: `lap_hook` 的 `LAPscene` 日志行把 buildIndex/gpIndex/场景名打进日志后，
> 由此表反查赛道身份；后续 UI（计时赛圈速面板）按此表显示赛道名。

## 证据链与可信度

| 层级 | 来源 | 可信度 |
|---|---|---|
| L1 权威 | APK `assets/bin/Data/data.unity3d` → BuildSettings.scenes（29 个场景，buildIndex 顺序） | [V] Unity 打包器生成，buildIndex 权威 |
| L2 佐证 | global-metadata.dat stringliteral（`Monza`/`Barcellona`/`Shangai`/`a1Ring`/`MelbourneRifatta`/`Dubai` 等场景名字面量在代码中被引用） | [V] |
| L3 交叉 | 实机 LAPscene 探测（Monza 物证） | [V] (2026-08-31, pid=28640) |

**实机交叉验证**（4/16 条已跑通，公式 `gpIndex = buildIndex − 2` 全部吻合）：

| 实测日期 | 场景名 | buildIndex | gpIndex | 会话类型 |
|---|---|---|---|---|
| 2026-08-31 | `Monza` | 14 | 12 | 计时赛 |
| 2026-08-31 | `Shangai` | 3 | 1 | 快速模式正赛（被门禁挂起，LAPscene 仍打出） |
| 2026-08-31 | `Monaco` | 7 | 5 | 计时赛 |
| 2026-08-31 | `MelbourneRifatta` | 2 | 0 | 计时赛 |

示例（Monza）：`LAPscene: scene='Monza' buildIndex=14 gpIndex=12`，
`14 − 2 = 12`（GP 场景基址=2，见 `CommonUtilities.GetGPIndex` 反汇编
`return activeScene − *(static_obj+0x20)`）——三层证据完全一致。

## 16 条 GP 赛道（buildIndex 2–17 连续）

| buildIndex | gpIndex | 场景名（LAPscene 输出） | GP 菜单名 |
|---|---|---|---|
| 2 | 0 | `MelbourneRifatta` | Australian GP（澳大利亚） |
| 3 | 1 | `Shangai` | Chinese GP（中国） |
| 4 | 2 | `Sakhir` | Bahrain GP（巴林） |
| 5 | 3 | `Imola` | Motorvalley GP（意大利 Motor Valley / 艾米利亚-罗马涅） |
| 6 | 4 | `Barcellona` | Spanish GP（西班牙） |
| 7 | 5 | `Monaco` | Monaco GP（摩纳哥） |
| 8 | 6 | `Montreal` | Canadian GP（加拿大） |
| 9 | 7 | `a1Ring` | Austrian GP（奥地利，红牛环旧称 A1-Ring） |
| 10 | 8 | `Silverstone` | British GP（英国） |
| 11 | 9 | `Hockenheim` | German GP（德国） |
| 12 | 10 | `Hungaroring` | Hungarian GP（匈牙利） |
| 13 | 11 | `Spa` | Belgian GP（比利时） |
| 14 | 12 | `Monza` | Italian GP（意大利） |
| 15 | 13 | `Suzuka` | Japanese GP（日本） |
| 16 | 14 | `Interlagos` | Brazilian GP（巴西） |
| 17 | 15 | `Dubai` | Emirates GP（阿联酋 / 迪拜） |
| 18 | — | `preRunExperimental` | （非赛道，实验场景，GP 区间到此终止） |

## 完整场景表（BuildSettings 全录，含非赛道场景）

| buildIndex | 场景文件 | 用途 |
|---|---|---|
| 0 | `Assets/Scenes/splashINtro.unity` | 开场 splash |
| 1 | `Assets/Scenes/Garage_Baked.unity` | 车库/主菜单 |
| 2–17 | `Assets/Scenes/GpScenes/*.unity` | **16 条 GP 赛道** |
| 18 | `Assets/preRunExperimental.unity` | 实验场景 |
| 19 | `Assets/Scenes/QualiResults.unity` | 排位赛结果 |
| 20 | `Assets/Scenes/RaceResults.unity` | 比赛结果 |
| 21 | `Assets/Scenes/loadingScene.unity` | 加载页 |
| 22 | `Assets/Scenes/ChampionshipFinale.unity` | 锦标赛收官 |
| 23 | `Assets/Scenes/TimeAttackResults.unity` | **计时赛结果页** |
| 24 | `Assets/Studio Livery Creator/Scenes/Vehicle.unity` | 涂装编辑器 |
| 25 | `Assets/Scenes/MultiplayerLobby.unity` | 多人大厅 |
| 26 | `Assets/Scenes/helmetEditor.unity` | 头盔编辑器 |
| 27 | `Assets/Scenes/SeasonIntro.unity` | 赛季开场 |
| 28 | `Assets/Scenes/suitEditor.unity` | 车服编辑器 |

## 注意事项

- **场景名拼写是游戏原始拼写，勿"纠正"**：`Shangai`（单 a）、`Barcellona`（意大利语）、
  `a1Ring`（小写 a 开头）、`MelbourneRifatta`（意大利语 rifatta=重建）。字符串精确匹配
  （如日志过滤/后续 UI 文案映射）必须逐字使用本表的场景名列。
- `trackToRace`（`IRDSLevelLoadVariables` 0xB8）在移动端取值恒为 `MobileScene`
  （场景名，非赛道名）——赛道身份**不可**从 `trackToRace` 取，必须走
  `LAPscene` 探测链（`SceneManagerHelper.get_ActiveSceneName` /
  `get_ActiveSceneBuildIndex` / `CommonUtilities.GetGPIndex`，见 `native/src/lap_hook.c`）。
- **探测重置时机**：LLV 单例虽 DontDestroyOnLoad，但每次场景加载 `Awake` 都重触发
  （实机观测快速模式一次加载流程触发 4 次），`lap_hook` 在 Awake 时重置探测状态与
  会话最佳圈——换赛道后不会残留上一赛道的 `gpIndex`/最佳圈。
- 游戏升级后 buildIndex 可能变化（场景增删），本表受 `VersionGate` 门控，仅对
  8.0.4 (200146) 有效。升级验证时重跑 UnityPy BuildSettings 解析即可。
- 单机 GP 与多人房间共用这套场景（`MultiplayerLobbyManager.myTrack` 0x4C 同样
  走 buildIndex 语义）。
- **会话门禁**：`lap_hook` 只在计时赛会话记录圈数据（`ChampionshipManager.isTimeAttack`）；
  非计时赛（快速模式正赛 / 比赛周练习·排位·正赛）中 `LAPscene`/`LAPtrack` 行照常输出
  （赛道识别与会话无关），圈速行（`LAPlap`/`LAPbest`/`LAPdone`/`LAPinv`）全部静默。
  会话类型信号矩阵见 `LAP_HOOK_NOTES.md` §模式门禁。