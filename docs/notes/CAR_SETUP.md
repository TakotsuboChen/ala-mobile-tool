# 车辆调教（setup）读取

> 状态：**活跃** · 最后编纂：2026-10-26（当日二次编纂：修正读取源）
> 一句话结论：赛道内读调教**不需要任何 hook**，但有**两个存储位置**——
> `raceMode == 3` 时游戏写 `ChampionshipData+0x68`，**不写**
> `GlobalVariables+0x10`。读错源会静默拿到 `AlaCarSetup..ctor` 的默认常量。
> `AlaCarSetup` 存的是**原始连续浮点**（13 项全部如此），游戏 UI 只是截断显示；
> 量纲与 UI 一致无需换算，但**精度必须保留**。

---

## 0. ⚠️ 先读这条：读错源会静默失败（2026-10-26 实机定案）

**现象**：`LAPsetup` 一直有输出、值看起来"正常"（65/58/60/50…），但玩家实际
设的是 100/100。**没有任何报错，日志行完整，格式正确**。

**根因**：`ApplyCurrentSetupPreRun` 内部按 `simpleRace` 参数分叉：

```asm
0x18b0898: ldr  x0, [x0, #0xb8]      ; GlobalVariables 的 static_fields
0x18b089c: tbz  w19, #0, #0x18b08a8  ; simpleRace 的 bit0 == 0 时【跳转】
0x18b08a0: str  x20, [x0, #0x10]!    ; simpleRace=true  → GlobalVariables+0x10
0x18b08a8: ldr  x0, [x0]             ; → championshipData
0x18b08b0: str  x20, [x0, #0x68]!    ; simpleRace=false → ChampionshipData+0x68
```

`tbz w19,#0,target` = **bit0 为 0 时跳转**。所以 `simpleRace=false` 走
`ChampionshipData+0x68`，**`GlobalVariables+0x10` 完全不被写**——它保持
`AlaCarSetup..ctor` 的默认常量。

**谁传 false**：`ChampionshipManager.GetToTrackFromSession`(0x173070c
`mov w1, wzr`) 与 `ApplySetupAndGoToTrack`(0x173182c) **硬传 false**；
只有 `prerunHandler.LoadTrackNow` 传 `simpleRace = (raceMode != 3)`
（`0x1a22e60 cmp w8,#3; cset w1, ne`）。

⇒ **实机计时赛 `raceMode == 3`**（2026-10-26 14:38 实机日志
`LAPsetup-diag: raceMode=3`），因此走的是 `ChampionshipData+0x68`。

**怎么发现的（三方对拍，这个方法要记住）**：

1. `AlaCarSetup..ctor`(0x1873AFC) 从 `.rodata` 载入默认常量——
   逐字节读出来是 `65,58,60,50 / 50,50,50,-3 / -1.5,-0.05,0.15,40 / 40,110`，
   与读到的 14 个值**完全相同** ⇒ 读到的是一个从未被填充的默认构造对象。
2. `carModifier.currentBrakeBiasFront`(0x3D4) 是 `HandleCarSetup` 从
   setup.brakeBias 直写的实际生效值（`0x1762d14 ldr s0,[x20,#0x20];
   str s0,[x19,#0x3d4]`）⇒ 实测 `50.000` ≠ 读到的 `60.000`。
3. 补打 `raceMode` ⇒ `3`，反查分叉逻辑即定位。

**教训**：上一版文档写"计时赛走前者（实机已验）"是**错结论**。那次验证只
确认了"日志行存在"，没比对数值。**"有输出" ≠ "读对了"**。

---

## 1. 结论（发现）

### 1.1 读取路径 [V]

**两个存储位置，先读 `ChampionshipData+0x68`，为空再回落 `GlobalVariables+0x10`**：

```
① ChampionshipData + 0x68            (raceMode==3 / 冠军赛路径，先读)
   └─ AlaCarSetup*
② GlobalVariables + 0x10             (回落：非冠军赛路径)
   └─ AlaCarSetup*
```

⚠️ **不要按 `raceMode` 硬分支**——该字段的取值语义在不同上下文不一致
（`ComputeFuelToLoad` 里 `raceMode==2` 是计时赛判定，而实际跑圈时是 3）。
用"哪份非空"作判据更稳，也天然覆盖冠军赛。

**零 hook、零实例指针链**。`GlobalVariables` 用 `il2cpp_field_static_get_value`
取值；`ChampionshipData` 同样经静态字段取指针后按 `+0x68` 读实例字段。
与项目里既有的 `isGrandFestival` / `championshipData` 读取是同一套机制（见
`lap_hook.c` 的 `resolve_mode_signals`）。

**常驻一致性校验**：`lap_snapshot_car_setup` 每圈对拍
`读到的 brakeBias` vs `carModifier.currentBrakeBiasFront`，**只在不等时
打 `LAPsetup: MISMATCH`**。正常零输出。这条是防止将来升版重蹈覆辙的唯一
自动护栏——见 §0。

### 1.2 字段偏移与量纲 [V]

| UI 显示项 | 字段 | 偏移 | 量纲 | 实测样例 |
|---|---|---|---:|---|
| 下压力（前/后） | `frontWingAero` / `rearWingAero` | 0x18 / 0x1C | 0–100 连续 | `55.397 / 37.331` |
| 底盘高度（前/后） | `frontRideHeight` / `rearRideHeight` | 0x44 / 0x48 | 连续 | `36.854 / 37.616` |
| **刹车平衡（前）** | `brakeBias` | 0x20 | 0–100 连续 | `54.702`（后 = 45.298） |
| 悬架刚度（前/后） | `frontSuspensionStiffness` / `rearSuspensionStiffness` | 0x24 / 0x28 | 0–100 连续 | `64.193 / 25.165` |
| 防倾杆（前/后） | `frontSuspensionRollbar` / `rearSuspensionRollbar` | 0x2C / 0x30 | 0–100 连续 | `31.566 / 31.457` |
| 外倾角（前/后） | `frontCamber` / `rearCamber` | 0x34 / 0x38 | **度**（可为负） | `-2.862 / -1.565` |
| 前束角（前/后） | `frontToe` / `rearToe` | 0x3C / 0x40 | **度**（可为负） | `-0.033 / 0.088` |
| （未探针）载油量 | `fuelLoaded` | 0x4C | — | — |
| （未探针）简单模式选择 | `selectedSimples` | 0x10 | int[] | — |

⚠️ **13 项全部是连续浮点，没有一个是「整数档位」** [V]（2026-10-26 实机定案）——
实机一次全项非整数读数（`aero=55.397/37.331 ride=36.854/37.616 brake=54.702
stiff=64.193/25.165 rollbar=31.566/31.457 camber=-2.862/-1.565
toe=-0.033/0.088`，Monza 1:15.443，用户确认与游戏内设定一致）。

⚠️ **"UI 显示整数"是显示层截断，不是存储精度**：`UpdateSlidersStrings`
（`0x18AD0C8`）读滑条值后 `fcvtzs w9, s0`（**向零截断**，非四舍五入）再转字符串
⇒ 调「58」实际存 `58.709`，UI 显示 `58`。

⇒ **展示层按项分档 + 固定小数位**（2026-10-27 用户定案，见 §5 末「展示格式」）：
其余项 2 位 / 外倾角 3 位 / 前束角 4 位，**不去尾零**。实现见 `DriverScreen.fmt()`。
⚠️ 早先写的「原样呈现原始 float、去尾随零」**已作废**：既会丢掉前束角的区分度
（3 位下 `0.0884` 与 `0.088` 都是 `0.088`），去尾零又会让同列位数参差。
后续「应用调教」功能要回写原始值，显示截断不影响存储精度。

⚠️ **量纲不需要换算**（下压力存 `55.397` 不是 `0.554`，外倾角存 `-2.862` 度
不是弧度）——这一条仍然成立，只是"等于 UI 显示值"的说法不准确。

### 1.3 ⚠️ 刹车平衡是「单字段」，不是前后两个 [V]

`brakeBias` **只有一个字段**，存的是**前**轴百分比；后轴 = `100 − brakeBias`。
UI 上显示成 `60 / 40` 是**派生的前后分配**，不是两个独立字段。

对比：其余六项（下压力/底盘高度/悬架刚度/防倾杆/外倾角/前束角）
都是**前后各一个独立字段**。这个不对称是结构性的 —— 刹车平衡是
「分配比例」（前后之和恒 = 100），其他是独立可调参数。

⇒ **日志里 `brake=60.000` 的信息量与 `aero=65/58` 等价**，
后轴值是派生量而非缺失。不要误判为「探针漏读了一个值」。

### 1.4 写入时机 = 进赛道前，赛道内稳定不变 [V]

`GlobalVariables.currentCarSetup` 的唯一写入点是
`SetupManager.ApplyCurrentSetupPreRun(bool simpleRace)`（RVA `0x18B05E8`），
其三个调用方**全是「进赛道前」路径**：

| 调用方 | RVA |
|---|---|
| `ChampionshipManager.GetToTrackFromSession` | `0x17304EC` |
| `ChampionshipManager.ApplySetupAndGoToTrack` | `0x1731820` |
| `prerunHandler.LoadTrackNow` | `0x1722DD8` |

⇒ 赛道内该对象**稳定不变**，圈边界读一次即可，无需每帧采样。

⚠️ **存在两条写入路径**（`ApplyCurrentSetupPreRun` 内 `tbz w19, #0` 分叉）：
- `simpleRace = true`：`GlobalVariables.static_fields + 0x10`
- `simpleRace = false`：`ChampionshipData + 0x68`

**实机计时赛 `raceMode == 3` ⇒ 走 `ChampionshipData+0x68`**（2026-10-26 定案，
详见 §0）。本模块因此**先读 ChampionshipData、再回落 GlobalVariables**。
旧文档写的"计时赛走前者（实机已验）"已被证伪。

### 1.5 实机验证记录 [V]

2026-10-26 11:47:05，Monza，`carId=22`（2026 法拉利 1 号 · SF-26），
有效圈 `1:14.105`：

```
lap_hook: GlobalVariables.currentCarSetup resolved: 0x6c336835d8 (state=1)
LAPsetup: aero=65.000/58.000 ride=40.000/40.000 brake=60.000
          stiff=50.000/50.000 rollbar=50.000/50.000
          camber=-3.000/-1.500 toe=-0.050/0.150 (lap 1:14.105)
```

同批 `LAPcfg: pedal usage 3710/3710 frames (100%)` ⇒ 3710 帧 / 74.105 s
= **50.06 Hz**，符合「分母正确性自检指纹」（≈50Hz 说明玩家车白名单
有效、AI 车未被计入），该圈数据可信。

日志覆盖 11:46:26–11:47:19（53 秒 / 9291 行 / 1.43 MB，远低于 2 MB
截断上限）⇒「只有一条 LAPsetup」是真的只跑了一个有效圈，非截断丢证。

---

## 2. 依据（研究）

### 2.1 `dump.cs` 字段声明

`AlaCarSetup`（`dump.cs:35891`，TypeDefIndex 916）字段布局：

```
public int[] selectedSimples;        // 0x10
public float frontWingAero;          // 0x18
public float rearWingAero;           // 0x1C
public float brakeBias;              // 0x20
public float frontSuspensionStiffness; // 0x24
public float rearSuspensionStiffness;  // 0x28
public float frontSuspensionRollbar;   // 0x2C
public float rearSuspensionRollbar;    // 0x30
public float frontCamber;            // 0x34
public float rearCamber;             // 0x38
public float frontToe;               // 0x3C
public float rearToe;                // 0x40
public float frontRideHeight;        // 0x44
public float rearRideHeight;         // 0x48
public float fuelLoaded;             // 0x4C
```

### 2.2 双向对拍：`ApplyCurrentSetupPreRun` 反汇编

**偏移被两条独立证据锁死**（这是逆向里最强的偏移确认方式）：

`ApplyCurrentSetupPreRun`（`0x18B05E8`）从 `SetupManager` 的滑条读值
（`blr` 到 `Slider.get_value`）后，逐字段 `str` 进新建的 `AlaCarSetup`
实例。读端偏移（SetupManager 字段）→ 写端偏移（AlaCarSetup 字段）
的配对关系与 `dump.cs` 声明**完全一致**：

```asm
; 0x18b0654: ldr x0,[x21,#0x140]  ← SetupManager.AdvancedBrakeBias
; 0x18b0668: blr x9               ← Slider.get_value
0x18b0670: str s0, [x20, #0x20]   → AlaCarSetup.brakeBias

; 0x18b0674: ldr x0,[x21,#0x0f0]  ← AdvancedFrontStiffness
0x18b06a8: str s0, [x20, #0x24]   → frontSuspensionStiffness

; 0x18b0690: ldr x0,[x21,#0x100]  ← AdvancedFrontRollbar
0x18b06a8: str s0, [x20, #0x2c]   → frontSuspensionRollbar

; 0x18b06ac: ldr x0,[x21,#0x110]  ← AdvancedFrontCamber
0x18b06e0: str s0, [x20, #0x34]   → frontCamber

; 0x18b06c8: ldr x0,[x21,#0x120]  ← AdvancedFrontToe
0x18b06e0: str s0, [x20, #0x3c]   → frontToe

; 0x18b06e4: ldr x0,[x21,#0x130]  ← AdvancedFrontRide
0x18b0718: str s0, [x20, #0x44]   → frontRideHeight
```

### 2.3 静态字段写入点

```asm
; ApplyCurrentSetupPreRun 尾部
0x18b0898: ldr  x0, [x0, #0xb8]   ; GlobalVariables 的 static_fields
0x18b089c: tbz  w19, #0, #0x18b08a8  ; simpleRace? 分叉
0x18b08a0: str  x20, [x0, #0x10]!    ; GlobalVariables.currentCarSetup = setup
0x18b08a8: ldr  x0, [x0]             ; 否则 → championshipData
0x18b08b0: str  x20, [x0, #0x68]!    ; ChampionshipData.currentCarSetup = setup
```

`0xb8` 是 IL2CPP `Il2CppClass` 的 `static_fields` 偏移（本项目既有
代码已多处使用该常量），`+0x10` 与 `dump.cs` 的
`public static AlaCarSetup currentCarSetup; // 0x10` 吻合。

### 2.4 为什么 `currentCarSetup` 没有 getter

`grep -n "currentCarSetup" dump.cs` 只有**两处字段声明**
（`ChampionshipData` 0x68 与 `GlobalVariables` 0x10），零方法引用。
与 `carModifier.carUniqueID` 同型：装车/进赛道时写一次，之后只读，
编译器没有生成属性包装。⇒ 必须直接读字段，不能调 getter。

---

## 3. 实现（固化）

| 位置 | 内容 |
|---|---|
| `native/src/lap_hook.c` | `resolve_setup_field()` —— 一次性解析 `GlobalVariables.currentCarSetup` 的 FieldInfo（`g_setup_resolved` 状态机 0/1/2） |
| `native/src/lap_hook.c` | `lapsetup_diag()` —— 打一行 14 个 float 的 `LAPsetup` |
| `native/src/lap_hook.c` | `OFF_SETUP_*` 13 个偏移宏（定义在 `resolve_setup_field` 之前） |
| `native/src/lap_hook.c` | 圈完成处（`LAPcar` 之后）调用 `resolve_setup_field()` + `lapsetup_diag()` |

**当前只打日志，不参与上传** —— `lap_upload_t` 未改，上传契约未定。

### 日志特征串（排查用）

| 串 | 含义 |
|---|---|
| `lap_hook: GlobalVariables.currentCarSetup resolved: 0x... (state=1)` | FieldInfo 解析成功（每进程一次） |
| `... resolved: 0x0 (state=2)` | 解析失败，不会重试 |
| `LAPsetup: aero=... ride=... brake=... (lap ...)` | **有效圈完成时**打，与 `LAPcar`/`LAPbest` 同批 |
| `LAPsetup: currentCarSetup is NULL (lap ...)` | 字段找到但对象为空（该模式可能走冠军赛路径） |
| `LAPsetup: currentCarSetup FieldInfo unavailable (state=N, lap ...)` | FieldInfo 没找到 |

### 字段顺序（日志解析契约）

```
aero      = frontWingAero / rearWingAero          下压力
ride      = frontRideHeight / rearRideHeight      底盘高度
brake     = brakeBias                             刹车平衡（前；后 = 100 − 前）
stiff     = frontSuspensionStiffness / rear...    悬架刚度
rollbar   = frontSuspensionRollbar / rear...      防倾杆
camber    = frontCamber / rearCamber              外倾角（度）
toe       = frontToe / rearToe                    前束角（度）
```

---

## 4. 已否决的方案

### [X] 通过 `SetupManager` 实例链读取

**想法**：`SetupManager.acs`（`+0x168`）也持有 `AlaCarSetup`。

**否决**：`SetupManager` 是**车库 UI 的 MonoBehaviour**，赛道场景中
不一定存在；即便存在也要先拿到实例指针（需要 hook 或场景遍历）。
静态字段 `GlobalVariables.currentCarSetup` 零 hook 零遍历即可直达，
没有任何理由走实例链。

### [X] Hook `ApplyCurrentSetupPreRun` 拦截写入

**否决**：写入发生在**进赛道前**，hook 只能拿到「写入瞬间」的值，
而模块真正需要的是「跑这一圈时用的是哪套调教」。静态字段在赛道内
稳定不变，圈边界读一次语义更准、且零 hook 风险。
（对比：`carId` 用 `proxy_car_controller` 探针是因为它没有静态持有者。）

### [X] 假设字段存归一化值（0–1）需要换算

**否决**：实机读数直接是 `65.000` / `-3.000` / `-0.050`，量纲自洽，
无缩放迹象。若真存归一化值，外倾角会是 `-0.052` 这类小量。
⇒ **不做任何换算**。

---

## 5. 待验证

- [?] **冠军赛路径**：`ApplyCurrentSetupPreRun` 的 `simpleRace` 分叉会把
  setup 写到 `ChampionshipData + 0x68` 而非 `GlobalVariables + 0x10`。
  计时赛已验走后者；冠军赛尚未实测。若实机出现
  `LAPsetup: currentCarSetup is NULL`，需补 `ChampionshipData+0x68` 兜底。
  依赖：跑一场冠军赛 + 导出日志。
- [?] **`selectedSimples`（0x10，int[]）语义**：UI 的「简单模式」调教
  选择索引（前翼/后翼/底盘高度各一档）。未探针，未解析。
  依赖：对照 UI 简单模式界面逐档实测。
- [?] **负前束/正前束的 UI 表述**：实测 `toe=-0.050/0.150`，前为负、
  后为正。UI 上是否显示为 `-0.05°` / `+0.15°` 或带「内八/外八」字样，
  未核对。
- [?] **冠军赛路径的上传表现**：同第一条。若冠军赛走 `ChampionshipData+0x68`，
  当前实现会整组 `has_setup = 0`（弹窗显示「缺失」），而不是崩溃或报错。
  依赖：跑一场冠军赛看圈速详情弹窗。

---

## 5a. 上传链路（2026-10-26 已接通）

调教与车型**已接入围场上传**，不再是"只打日志"。

**数据流**：`lap_snapshot_car_setup()`（`lap_hook.c`，圈边界调用）读一次 →
写 `g_upload_car_id/car_era/has_setup/g_upload_setup[13]` → `g_upload_seq++`
发布 → Java 1Hz `pollLapUpload(IntArray(11), FloatArray(13))` 取走 →
`PaddockClient.uploadLap(..., car, setup)` 上报。

**跨语言契约点（改一处必须三处同改）**：
1. `lap_hook.c` 的 `US_*` 枚举（写入顺序）
2. `ala_core.c` 的 `sbuf[13]`（JNI 搬运顺序）
3. `PaddockClient.LapSetup.fromNative`（读取顺序）

**缺失语义**：
- 车型用 `car_id < 0`（探针未采到，getter 返回 -1）
- 调教用**独立标志** `has_setup`，不用数值哨兵——浮点值域连续，任何哨兵都可能撞上合法调教值
- 服务端 `score::CarSetupConfig::normalized` 再兜一层：车型越界（不在 0..41 / era 不在 0..1）→ 两列都 NULL；调教缺任一字段 → 整组 NULL

**存储形态（用户 2026-10-26 定案）**：
- **车型只存整数** `car_id` + `car_era`，展示名由模块侧 `CarCatalog` 反查。
  改车队译名只发模块、历史圈自动跟着变，服务端永不认识车型字。
- **调教存原始连续浮点**（13 项全部如此），服务端零换算。
  ⚠️ 早先写的「存的就是 UI 显示值本身」不准确：UI 显示是 `fcvtzs` 截断的结果，
  存储精度远高于显示（调「58」实存 `58.709`）。见 §1.2。
- **`setup_brake_bias` 只存前值**，后 = 100 − 前（UI 的 `60/40` 是派生量）。

**展示格式（用户 2026-10-27 定案）**：**按项分档 + 固定小数位、不去尾零**。

| 项 | 位数 | 理由 |
|---|---|---|
| 下压力 / 底盘高度 / 刹车平衡 / 悬架刚度 / 防倾杆 | **2** | 有整数位，2 位小数已足够区分相邻档 |
| 外倾角 | **3** | 量级 `-2.8624`，有整数位，3 位够 |
| 前束角 | **4** | 量级 `0.0884` 无整数位，3 位下 `0.0884` 与 `0.088` 都显示 `0.088` |

实现 = `DriverScreen.fmt(v, digits)`：`"%.${digits}f".format(v)`。

⚠️ **两项角度位数刻意不同**（外倾角 3 / 前束角 4）——**不要为了"角度统一"
把它们并成同一个位数**：外倾角有整数位、前束角没有，同样的精度要求下后者要多一位。
⚠️ **不加去尾零**：那会让 `54.70` 变 `54.7`、`55.40` 变 `55.4`，同一列位数参差
（用户 2026-10-26 报「刹车平衡变 1 位了」）。
⚠️ **不要改回「原样呈现原始 float（`%.4f` + `trimEnd('0')`）」**——那是
2026-10-26 的旧定案，2026-10-27 已被上表取代。
⚠️ **不要按「整数项取整」**——13 项全是连续浮点（§1.2），取整会丢精度。

**真库测试**：`paddock-api/src/car_setup_sql_tests.rs`（5 项，`#[ignore]`，
需 `DATABASE_URL`）——覆盖 15 个新列、全字段往返、半套归一化、车型越界、
旧客户端零改动兼容。这些**编译期全都发现不了**（sqlx 用运行时 `query` 而非宏）。

---

## 6. 载油量（fuelLoaded）：为什么它在计时赛不构成问题

> **本议题的完整原理分析在 [`TECHNICAL_ANALYSIS.md` §4.5](../TECHNICAL_ANALYSIS.md#45-载油量计时赛里不构成圈速变量)**。
> 这里只留结论与判据指针，不重复证据。

### 6.1 结论 [V]

**计时赛里玩家无法调节油量，且油量不参与物理计算。**
两个独立理由，任一条都足以关闭该议题。

| 理由 | 一句话 |
|---|---|
| 油量是赛道固有属性 | `carModifier.Start` 调 `ComputeFuelToLoad`，`raceMode==2`（游戏自身计时赛判定）走「按赛道长度算」分支，读 `trackSelStatus[trackIndex]` × 系数 |
| 油量不进物理 | `IRDSDrivetrain.fuelQty` 全 dump 零方法引用；`IRDSDrivetrain` 无任何质量字段 |

⇒ **探针不要读 `fuelLoaded`**，没有信息增量。玩家可选且影响性能的是
车型（`carId`），那才需要记录——**可选的才记录，固定的不记录**。

### 6.2 已否决的提议 [X]

**[X] 「把 `fuelLoaded` 纳入探针，用于榜单公平性」**

2026-10-26 提出后经查证否决，理由见上表（详见 `TECHNICAL_ANALYSIS.md` §4.5）。
**不要重新提议。**

⚠️ 只有当出现「计时赛可调油量」的新版本，或某条赛道的 `trackSelStatus`
值异常导致油量跨玩家不同时，才需要重新评估 —— 届时先跑
`ComputeFuelToLoad` 的日志再谈。
