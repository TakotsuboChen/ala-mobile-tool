# 玩家车身份识别（carId / 车型映射）

> 状态：**活跃** · 最后编纂：2026-10-26
> 一句话结论：运行时唯一可靠的车型标识是 `carModifier.carUniqueID`（= `CarDefinition.carId`），
> 路径 `IRDSCarControllInput+0x190 → carModifier`；**游戏内不存在真实型号名**（W17/RB22 等），
> 型号只能由模块侧维护映射表。

---

## 1. 结论（发现）

### 1.1 运行时读取路径 [V]

```
IRDSCarControllInput (this)
  +0x190  →  carModifier*
              +0x20  carUniqueID   (= CarDefinition.carId)
              +0x24  carType       (0=GroundEffectEra 1=DoubleDRSEra)
              +0x68  carNumber     (恒等于 carId)
```

- 三个字段**恒等关系成立**：`carUniqueID == carNumber == carId` [V]
- 读取点在 `proxy_car_controller` 的玩家车白名单分支内，**每物理帧（50Hz）刷新** [V]
- 只读，无副作用，实测无崩溃 [V]

### 1.2 carId 区间与年代 [V]

| 年代 | carType | carId 区间 | 车数 |
|---|---|---|---|
| 2023 地效车 | 0 | 0–19 | 20（10 车队 × 2） |
| 2026 主动空力 | 1 | 20–41 | 22（11 车队 × 2） |

同一车队两台车**连号**（1 号车偶数、2 号车奇数）。

### 1.3 ⚠️ 游戏内没有真实型号名 [V]

这是本次调查最重要的结论，纠正了一个常见误解：

**作者只改了车队名和动力单元名，型号名根本不存在。**

全 APK（72 个 assets 逐字节扫描）精确搜索结果：

| 真实型号串 | 命中 |
|---|---|
| `RB22` `MCL40` `AMR26` `FW48` `VF-26` `MAC-26` `SF-26` | **零命中** |
| `W17` `A526` | 命中，但**全是二进制噪声** |

`W17` 的字节上下文实证（`sharedassets1.resource` @0x386c17）：
```
....7[7...{..1....D.M%..j.S.j..,i.I.Jc%.5)M..I2.W17..a.g..iF...!..K..6...
```
前后全是随机字节 —— 3~4 字符的短 token 在几十 MB 文件里必然随机出现。

`A526` 在 `global-metadata.dat` 里是哈希串中的十六进制巧合：
```
1051151F0183A8B0B6F6693C43F7D9E1815F85CAAAB18E00A5269A2.AAF4528994DD7C464F43C131F6CD44DF41ACC18462C9
```

⇒ **任何"读真实型号"的方案都做不到**。型号只能由模块侧维护 `carId → 型号` 映射表
（见 `app/src/main/kotlin/tools/alamobile/mod/util/CarCatalog.kt`）。

### 1.4 资源侧完整映射表 [V]

#### 2026 主动空力（`activeaerocars.bundle`）

| carId | 资产名 | displayName | teamId | **真实车队** | **真实型号** | 游戏内车队名（虚构） | 动力单元（虚构） | 国籍 |
|---|---|---|---|---|---|---|---|---|
| 20/21 | `mercedes` | `mer_1_26`/`mer_2_26` | 10 | **梅赛德斯 AMG** | **W17** | AFG Performance | AFG P26 E-Performance | de |
| 22/23 | `ferrari` | `fer_1_26`/`fer_2_26` | 11 | **法拉利** | **SF-26** | Squadra Corse Carrari | Carrari Tipo 266H | it |
| 24/25 | `redbull` | `redb_26_1`/`redb_26_2` | 12 | **红牛** | **RB22** | SeaGull Racing | SGPT_H_02 V6 | at |
| 26/27 | `astonMartin` | `ast_26_1`/`ast_26_2` | 13 | **阿斯顿·马丁** | **AMR26** | Hawthorne Grand Prix | AFG P26 E-Performance | gb |
| 28/29 | `williams` | `wil_26_1`/`wil_26_2` | 14 | **威廉姆斯** | **FW48** | Maillans Motorsport | AFG P26 E-Performance | gb |
| 30/31 | `mclaren` | `mcl_26_1`/`mcl_26_2` | 15 | **迈凯伦** | **MCL40** | McBritain | AFG P26 E-Performance | gb |
| 32/33 | `alpine` | `alp_26_1`/`alp_26_2` | 16 | **阿尔派** | **A526** | Bleu Horizon Racing | AFG P26 E-Performance | fr |
| 34/35 | `vcarb` | `alt_26_1`/`alt_26_2` | 17 | **赛牛（红牛二队）** | **VCARB 03** | Scuderia Nova Dynamics | SGPT_H_02 V6 | it |
| 36/37 | `audi` | `aud_26_1`/`aud_26_2` | 18 | **奥迪** | **R26** | Quantis Motorsport | Vektor e-s1 | de |
| 38/39 | `haas` | `has_1_26`/`has_2_26` | 19 | **哈斯** | **VF-26** | IASS | Carrari Tipo 266H | us |
| 40/41 | `cadillac` | `cad_26_1`/`cad_26_2` | 20 | **凯迪拉克** | **MAC-26** | Vanguard Performance | Carrari Tipo 266H | us |

⚠️ **「真实车队」「真实型号」两列是外部知识，不是游戏数据**——游戏里根本不存在这些字符串
（见 §1.3 的证据）。它们的来源是「游戏车队资产名 → 现实世界车队 → 对应车型」的人工映射，
措辞由用户 2026-10-26 逐条给定，**不得擅自改写**。

⚠️ **carId 34/35 的资产名是 `vcarb`，不是贴图文件里的 `alt26`** ——
prefab 路径实证 `ActiveAeroEra/CarsPrefabs/vcarb26_1.prefab`，
而贴图叫 `alt26_nor.png`。两者不一致，以 prefab/资产名为准。

#### 2023 地效（`grounderacars_assets_all_*.bundle`）

| carId | 资产名 | displayName | teamId | **真实车队** | **真实型号** | 游戏内车队名（虚构） | 动力单元（虚构） | 国籍 |
|---|---|---|---|---|---|---|---|---|
| 0/1 | `mercedes` | `mer_1_23`/`mer_2_23` | 0 | **梅赛德斯 AMG** | **W14** | AFG Team | AFG Hybrid M18 | de |
| 2/3 | `ferrari` | `fer_1_23`/`fer_2_23` | 1 | **法拉利** | **SF-23** | Carrari | Carrari H18 | it |
| 4/5 | `redbull` | `red_1_23`/`red_2_23` | 2 | **红牛** | **RB19** | Sea Gull | Wave Hybrid Spec4 | at |
| 6/7 | `astonMartin` | `ast_1_23`/`ast_2_23` | 3 | **阿斯顿·马丁** | **AMR23** | Sport Race | AFG Hybrid M18 | gb |
| 8/9 | `williams` | `wil_1_23`/`wil_2_23` | 4 | **威廉姆斯** | **FW45** | Maillans | AFG Hybrid M18 | gb |
| 10/11 | `mcLaren` | `mcl_1_23`/`mcl_2_23` | 5 | **迈凯伦** | **MCL60** | McBritain | AFG Hybrid M18 | gb |
| 12/13 | `alpine` | `alp_1_23`/`alp_2_23` | 6 | **阿尔派** | **A523** | Focault | Focault Y34 | fr |
| 14/15 | `alphaTauri` | `alt_1_23`/`alt_2_23` | 7 | **阿尔法托利（红牛二队）** | **AT04** | Gabbiano | Wave Hybrid Spec4 | it |
| 16/17 | `haas` | `has_1_23`/`has_2_23` | 8 | **哈斯** | **VF-23** | IASS | Carrari H18 | us |
| 18/19 | `alfaromeo` | `alf_1_23`/`alf_2_23` | 9 | **阿尔法·罗密欧** | **C43** | Mauser | Carrari H18 | ch |

⚠️ 2023 代资产名大小写混用（`mcLaren`/`mcLaren2`/`alfaRomeo2`），**不是笔误**，按原样保留。

### 1.5 实机验证记录 [V]

| 时间 | carId | carType | carNumber | 资源侧对应 |
|---|---|---|---|---|
| 2026-10-26 00:13:40 | 22 | 1 | 22 | 2026 法拉利 1 号 · SF-26 |
| 2026-10-26 00:23:59 | 23 | 1 | 23 | 2026 法拉利 2 号 · SF-26 |
| 2026-10-26 00:25:16 | 37 | 1 | 37 | 2026 奥迪 2 号 · R26 |
| 2026-10-26 00:26:16 | 18 | 0 | 18 | 2023 阿尔法·罗密欧 1 号 · C43 |
| 2026-10-26 00:41:21 | 28 | 1 | 28 | 2026 威廉姆斯 1 号 · FW48 |

5 次读数覆盖两代、三支车队、1 号车与 2 号车，`carUniqueID == carNumber == carId` 全部成立。

---

## 2. 依据（研究）

### 2.1 资源侧解析方法

用 UnityPy 解析两个 Addressable bundle 里的 `CarDefinition` / `TeamDefinition`
ScriptableObject：

```python
# 每个 CarDefinition: carId / displayName / m_Name(资产名) / teamReference
# 每个 TeamDefinition: teamId / teamName / engineName / nationality
# 沿 teamReference 的 pathID 关联两张表
```

脚本：`.tools/join_car_team_table.py`（一次性工具，保留供升版复用）。

### 2.2 `cM`(0x190) 字段确认

`get_CM()` 反汇编（RVA `0x1A659B0`）：

```asm
0x1a659f4:  ldr  x21, [x19, #0x190]    ; ← 裸字段读取，无懒加载
0x1a65a04:  mov  x0, x21
```

⇒ 0x190 是真实字段，不存在"懒加载导致为空"的可能。

### 2.3 为什么不能用 `carUniqueID` 做上传主键之外的推断

`carModifier` 的 `carUniqueID` 全 `.so` 只有一处引用（`dump.cs:17727` 的字段声明），
没有 getter 方法，说明它只在装车时写入一次、之后只读。

---

## 3. 实现（固化）

| 位置 | 内容 |
|---|---|
| `native/src/pedal_hook.c` | 探针：`proxy_car_controller` 白名单分支内读 `this+0x190` 三字段，缓存到 `g_car_id_probe` 等 volatile 变量 |
| `native/src/pedal_hook.h` | getter：`pedal_get_car_unique_id()` / `pedal_get_car_era()` / `pedal_get_car_number()` |
| `native/src/ala_core.c` | JNI `pollCarIdentity(jintArray)` → `[0]=carId [1]=carType [2]=carNumber`，返回 carId |
| `app/.../NativeBridge.kt` | `external fun pollCarIdentity(out: IntArray): Int` |
| `app/.../util/CarCatalog.kt` | 42 台车映射表（carId → 资产名/车队/型号），单一事实源 |
| `native/src/lap_hook.c` | 圈边界日志 `LAPcar: carId=%d era=%d`（**仅日志，未接上传**） |

### 日志特征串（排查用）

| 串 | 含义 |
|---|---|
| `CARIDprobe cm=0x... carUniqueID=N carType=N carNumber=N` | 换车/进赛道时打（`cM` 指针变化触发） |
| `CARIDprobe cM lost` | 离开赛道或换车中，`cM` 变 NULL |
| `LAPcar: carId=N era=N (lap ...)` | **有效圈完成时**打，与 `LAPbest`/`LAPdone` 同批 |

### ⚠️ 日志去重设计

- `CARIDprobe` 用 **`cM` 指针**判重，不是 `carId` —— 同一辆车重进赛道 `carId` 不变，
  用 `carId` 判会静默掉最需要观察的那次
- `LAPcar` 每圈一条，频率与圈速绑定，不受去重影响

---

## 4. 已否决的方案

### [X] 在 `this` 前 0x400 字节扫描候选指针（特征探测）

**现象**：直接 SIGSEGV 闪退。崩溃日志：
```
signal=SIGSEGV(11) fault_addr=0xa3d70a3f00c4bc
pc=0x6d0a990cf4  =>  +0x0
```

**原因**：`IRDSCarControllInput` 里有大量非指针字段（float/bool/int），
把它们当指针解引用必然崩。

**教训**：**只读已知偏移的具名字段**，不要做"扫描找候选"式的探测。
加范围校验（`id in 0..63 && era in 0..2`）也没用——读之前就已经崩了。

### [X] 用 `carId` 变化作为日志触发判据

**现象**：同一辆车重进赛道时 `CARIDprobe` 不再打印。

**原因**：`carId` 是"值"，重进赛道不变；应改用 `cM`（实例身份），每次进赛道必变。

**教训**：观察"事件是否发生"，判据要选**会随事件变化的量**。

### [X] 认为 `cM`(0x190) 在赛道内恒 NULL

**现象**：前两轮跑圈 `CARIDprobe` 零命中，误判为字段为空。

**真实原因**：日志滚动截断（`truncate_log_file` **保留后半、丢弃前半**），
那条日志被后续 1.7MB 的 `unlock_steer_angle` 刷掉了。

**教训**：`grep` 零命中 ≠ 不存在。先确认日志**覆盖范围**（时间跨度、文件大小 vs 截断上限），
再下"没有发生"的结论。

---

## 5. 待验证

- [?] **2023 地效代只验了 1 条读数**（carId 18）。其余 9 支车队未实机核对，
  依赖资源侧解析（已很确定，但未逐条实机验证）。
- [?] **DLC 新车**：`CarDLCManifest` 机制允许后续加车，carId 可能超出 0–41。
  未知 carId 时 `CarCatalog.byCarId()` 返回 null，上传侧应省略而非报假值。
- [?] **上传字段契约未定**：`carId` + `era` 已能取到，但服务端字段名、
  是否按车型统计等均未决策。**当前代码不参与上传**（`LAPcar` 仅日志）。
