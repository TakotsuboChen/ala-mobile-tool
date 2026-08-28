# ABS 档位调节设计(v2)

> 状态:**已实现并实机定版**(2026-08-28;v1 因用户实测需求修正被推翻后重写,同日完成实装、修复与用户标定)
> v1 错误:把"低速 32% 限压"当设计锚点、押 p0 做主旋钮。用户实测证明低速不是痛点,真问题是**制动力基数过强 + ABS 全段过度保护**。
> 姊妹篇:TC_LEVEL_DESIGN.md(TC 档位 v1.4 已落地)——复用其管线骨架与方法论。

## 1. 需求基线(用户实测,2026-08-28)

| # | 实测 | 结论 |
|---|---|---|
| ① | 关 ABS + 100% 重刹 + 最高下压力 = 直接触发锁死 | 制动扭矩基数(T_b)远超轮胎抓地极限 |
| ② | 默认 ABS 全段(不只低速)几乎不锁死 | ABS 把实际压力压在远离抓地极限处(过度保护) |
| ③ | 低速"32% 限压刹不住"**不是**痛点 | p0 不能做主旋钮 |

设计目标:①**制动压力旋钮**修"基数过强"(关 ABS 也不秒锁死);②**ABS 干预档**修"过度保护"(提供贴极限的中间地带)。两诉求对应两组独立参数。

## 2. 游戏 ABS 机制(v2 深挖定案,全部指令级 [V])

### 2.1 tempBrakeF 完整计算链(RoadForce 0x1A7B35C)

```
初始化   tempBrakeF(0x3EC) ← T_b(0x88);brakePressure(0x418) ← wheel.brake(0xF0)
跳过段   usesABS=false(玩家车)或 αv≤0 → 不覆写,tempBrakeF 保持 T_b → 满压 → 锁死(实测①的机制)
进段     T = F_base · Ω → str 0x3EC
         ⚠️ σ<0.15 时也是 F_base·Ω——玩家车运动中永远在 ABS 段内,压力恒被压离 T_b
σ>0.15   pulse 帧:T ×b → str 0x3EC(乘完直接写,无任何二次 clamp)
pulse    pulseBrakes(0x408) 每帧无条件翻转(25Hz 方波)
σ 语义   唯一比较 0x1A7B760 fcmp/b.le:σ 只切换 [满压 F_base·Ω ↔ 泄压 ×b] 相位,非进出段条件
制动扭矩 = tempBrakeF × brakePressure(0x418);踏板 100% → 0x418=1.0 直通无曲线
```

### 2.2 SetBrakeBiasValues(0x1762BF4)计算式破解(事件驱动:装车/UI 配平)

```
T_b(0x88) 前 = 75.0 × bias /100?? → 前 = multiplier×bias,后 = multiplier×(100−bias);multiplier=75.0f(ctor,无运行时写者)
b(0x3E0)  前 = clamp01((bias−60)/10)×0.3;后 = clamp01((60−bias)/10)×0.3
p0(0x3E4/3E8) 前 = bias×13;后 = (100−bias)×13
currentBrakeBiasFront ∈ [50,70](游戏 UI 域)
```

**bias=60(中点默认)→ 前后轮 b 全 = 0 → pulse 帧 T×0 完全泄压**,方波在 [F_base·Ω, 0] 振荡,平均 0.5×F_base·Ω。

### 2.3 定量图景(与用户实测精确吻合)

| 工况 | 实际压力(相对 T_b) |
|---|---|
| 关 ABS | 1.0 T_b(满基数)→ 秒锁死 |
| 开 ABS 打滑中(方波平均) | 0.32–0.45 T_b(100–250 km/h)→ 几乎不锁死 |

**2.2–3 倍差距 = 用户实测两极化的完整解释**。高速段压缩主力是 **b=0 方波**(非 F_base,p0 权重 (1−r)² 高速趋零,150 km/h 仅 0.23)——v1 的"低速限压"叙事证伪。

### 2.4 Ω 耦合定量(b 的副作用边界)

b 三处读取:①β=clamp01(b/0.3)→Ω=γ+β(1−γ)(摩擦圆让渡);②后轮 κ=1−β 只进 kP 项(kP≡0 无效);③pulse 帧 T×b。
**b≥0.3 → β 饱和=1 → Ω=1,β 通道关死,释放深度成为零副作用杠杆**。b 从 0→0.3 中间段,弯中纵向让渡线性减弱(方向与主效果同向,非冲突)。游戏 UI 极限只能到 0.3(bias=70 单侧),模块可直写 0.5–0.9。

## 3. 注入点选型 v2(杠杆重评)

| 字段 | 作用域 | 修①基数过强 | 修②过度保护 | 判定 |
|---|---|---|---|---|
| **b(0x3E0,轮级)** | 全段(σ>0.15) | — | **主杠杆 [V]**:抬 b 直接抬方波平均 (1+b)/2;b≥0.3 零耦合 | **干预强度档参数** |
| **T_b(0x88,轮级)** | 全段基数 | **主杠杆 [V]**:等比缩放,关 ABS 基数与 ABS 天花板同降,保持前后配平 | 间接 | **制动压力档参数** |
| p0(0x3E4/3E8) | 仅低速 | 否 | 低速辅助 | 降级,首版不调 |
| kP(0x40C) | 非pulse帧 | 正值=更弱干预 | 实验性 | 二期 |
| 0x410/0x414 | 时机 | — | — | 二期(用户已证低速非痛点) |

写入者:0x3E0/0x88 均唯一写者 SetBrakeBiasValues(事件驱动,正常圈驾不重写)→ 模块每帧兜底绝对值写。0x88 是 public 序列化字段,等比乘法安全。

## 4. 档位模型 v2(双旋钮,照 TC 结构;已按用户规格落地)

- **模式**:`默认 / 自定义`(同 TC)。**每次从默认切到自定义时弹警示**(标题"⚠️建议调整最大制动压力"居中 + 正文左对齐 + "我已了解"按钮——减弱干预后重刹易锁死,提醒配合下调最大制动压力;⚠️ 须带 U+FE0F 变体选择符,裸 U+26A0 会渲染成黑白文本字形)。
- **旋钮 1 · 干预强度**(自定义卡片内,b 绝对值覆写;用户规格档名"关闭 ABS、低、中、高、最高(默认)"——2026-08-28 文案统一,与 TC 档位词汇表合并):

  | 档 | b 值(**2026-08-28 用户实机标定**) | 方波平均 | 语义 |
  |---|---|---|---|
  | 关闭 ABS | usesABS=false(复用现有通道) | 无 ABS | 完全锁死自由 |
  | 低 | 0.80 | 0.90×F_base | 贴极限,重刹频繁可感锁死 |
  | 中 | 0.60 | 0.80 | 间歇锁死 |
  | 高 | 0.50 | 0.75 | 略松于原厂,轻微锁死 |
  | 最高(默认) | **不写**(恢复捕获基线) | 0.50 | 原厂:提前接手,"刹车还有余量就被收走" |

  标定史:首版候选 0.50/0.30/0.15(方波平均 0.75/0.65/0.58)→ 用户实测"低档干预仍非常强、只有一丁点锁死,中等到最高几乎完全不锁死,差异化不足" → 第二版 0.90/0.60/0.30 → **用户终版标定 0.80/0.60/0.50 定案**。教训:有效手感区间远窄于理论窗口(0–0.9),原厂 b=0 是极端而非档位;档距 0.15 不构成可感差异,**0.10–0.30 级档距起步**。

  描述文案:"修改干预制动偏置"(2026-08-28 文案定版,原"修改干预方波平均")。b<1 恒有泄压相位(native clamp 上限 0.9,不允许持续锁死——"完全锁死"留给关闭档,行业同款)。
- **旋钮 2 · 最大制动压力**(ABS 下方**独立项**,不在自定义卡片内;T_b 等比缩放,F1 官方游戏同名术语):

  | 值 | T_b 缩放 | 语义 |
  |---|---|---|
  | 100%(默认) | 不写 | 原厂基数 |
  | 50-100% **无级** | ×设定值 | 调低后关 ABS 锁死倾向同步下降;**等比缩放非截断——踏板响应曲线/纵轴完全不受影响**(absPressure 独立于 absMode 生效,默认/关闭档下也修"关 ABS 秒锁死")。下限演化:首版 75% 太温和无法观察(§6.3)→ 0-100%(0% = T_b 清零,观察信号极强)→ **50-100%**(2026-08-28 用户规格;更低时高速段 F_base→0 制动几乎消失,无实验价值且易误导为"模块坏了";Java 侧 migrateAbs/ConfigReceiver clamp 同步收窄,native clamp 不动——tbScale<0 有独立禁用语义) |

  描述文案:"调整游戏制动摩擦扭矩上限"。下限 50%(用户规格)。
- **enableAbs 派生化**(照 TC):`关闭 = 自定义 + 干预强度=关闭 ABS`;旧 `enable_abs` bool 迁移 false→CUSTOM+OFF,true→DEFAULT(红线:老用户"ABS 关闭"不得悄悄变"默认");制动压力独立于迁移(默认 100)。

## 5. 端到端改动清单(骨架同 v1,参数形态更新)

数据流:UI(模式+干预档+制动压力)→ ModConfig(三路下发)→ ConfigReceiver 派生 → JNI `setAbsParams(mix, bOverride, tbScale)` → pedal_hook.c 基线捕获 + wheels 数组每帧覆写。

1. `config/ModConfig.kt` — 新键 `abs_mode`/`abs_strength`/`abs_pressure`;`AbsMode`/`AbsStrength`(携带 b 值)/`AbsPressure`(携带 tbScale)枚举;`absEffectiveParams()`;`migrateAbs()`;**7 个 Settings 构造点全同步**(read/fromJson/defaultSettingsPublic/ViewModel×3/PedalOverlayView.kt:42 部分构造必须带默认值)。
2. `ui/viewmodel/ConfigViewModel.kt` — uiState/init/toSettings 三处 + setter。
3. `ui/screen/configure/ConfigurePagerMiuix.kt` — ABS 区:模式下拉 + 干预强度滑条 + 制动压力滑条,分隔线成组照搬 TC 区;`absStrengthName()`/`absPressureName()`。
4. `config/ConfigReceiver.kt` — ABS 派生块(照 TC 117-126)。
5. `NativeBridge.kt` — `external fun setAbsParams(mix, bOverride, tbScale)`(独立 setter,不动 init 44 参签名)。
6. `native/src/ala_core.c` — JNI → `pedal_set_abs_params`。
7. `native/src/pedal_hook.h/.c` — config 字段;基线 `g_abs_base_b[]/g_abs_base_tb[]/g_abs_base_uses[]`(per-wheel,−1=未捕获);覆写挂 proxy_fixed_update 白名单分支,**覆写块在关闭块之前**(基线防污染);wheels 遍历照 usesABS 写法:b 绝对值写(档位值),T_b=基线×tbScale 等比写;切回默认恢复基线;**usesABS 残留恢复通道**(关闭路径写 false 后游戏永不自己写回——Awake 唯一写者;enable_abs 回 true 时一次性恢复基线,否则切任何档都停在关闭状态,实机实测发现的 bug);换车检测(wheels 指针变化)重置基线重捕;install 兜底 mix=1.0/bOverride=−1/tbScale=1.0;setter clamp(b∈[0,0.9]、tb∈[0,1.0] 0=清零合法、mix≤0→OFF)。
8. **新增 `abs_diag_log`**(tc_diag_log 类比,白名单内限频):pulseBrakes(0x408)/tempBrakeF(0x3EC)/b/T_b/brakePressure(0x418) 写前值。**注意:0x3D4(currentBrakeBiasFront)读法不对(float/int 误读出 2049 垃圾值),已从日志移除——bias 真值从 T_b 反推(4500/75=60)**。
9. `offsets/OffsetTable.kt` — 字段偏移继续 native 硬编码(TC 0x34/0x38 先例);**勘误:0x1A62E10 实为 setThrottleInput,setBrakeInput 真身 0x1a62df4**(script.json;本会话发现,offsets_sheet 待订正)。

## 6. 验证记录(2026-08-28 实测定案)

1. **运行时真值定案 [V]**:ABSdiag 实测 `baseline[0..3] captured b=0.000/-0.000 tb=4500/3000 uses=1`——与 SetBrakeBiasValues 计算式(bias=60、multiplier=75)逐位吻合;**b=0(原厂全泄压)实锤**。TCdiag 同场:TC 基线 slip=0.400/minspd=11.0(跨功能一致性佐证)。
2. **usesABS 残留 bug 发现与修复 [V]**:用户实测"切关闭后任何档位(含总开关回默认)都停在关闭状态"——根因:关闭路径写 usesABS=false 后游戏永不自己写回(Awake 唯一写者),恢复方向缺失。修复:usesABS 纳入基线捕获/恢复 + 关闭路径置 taking_over + 覆写块前移防基线污染。修复后日志实证 `usesABS baseline restored (1)`,用户确认生效。
3. **制动压力字段写入生效 [V]**:ABSdiag 实证 tbCfg=0.75 → tb=3375.0(4500×0.75)写入且稳定保持。用户"无法判断是否生效"的原因:75% 下限太温和(低速段 p0 主导稀释 T_b 权重 + 方波泄压掩盖)→ 范围改 **0-100% 无级**观察(0% 高速段 F_base→0,信号极强),用户观察后定版。
4. **档位标定 [V]**:见 §4 标定史(0.50/0.30/0.15 → 0.90/0.60/0.30 → **0.80/0.60/0.50 定案**)。
5. `./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL,lint 0 errors(42 warnings = 基线)。

## 7. 行业先例(v2 要点)

- **F1 官方游戏 Brake Pressure setup 项**(F1 24:80–100%,F1 22:50–100%)——制动压力旋钮的直接先例,同名同义,F1 mod 题材对齐。
- Bosch M5:干地高号 map = "最激进 dry-slick 标定";downforce-dependent slip 是正式功能(高速放更晚/低速收紧,二期进阶方向)。
- 赛用 ABS vs 原厂:200→0 km/h 刹距短 17–20%(HP Academy 实测)。
- 贴极限哲学适配触屏阶跃输入("踩死让系统找峰值");移动端无先例(首发),底线=最低档也不放任持续锁死。
- 锁死代价文案:前轮锁=转向丧失(TireRack);平斑不可恢复(Michelin);游戏内一次大锁死≈10% 胎寿命(iRacing 社区)。

## 8. 开放问题

1. ~~b/T_b/bias 运行时真值~~ [V] 已定案(§6.1);玩家配平 ≠60 时基线 b≠0 的档位相对关系 — 首版接受(档位为绝对值)。
2. 0x414 死参数判定 [?] — 指令级证据链倾向死参数(αv 只进 kP 项,kP≡0),未实证;二期 kP 解析时一并验。
3. kP/κ、0x410 时机维 — 二期。
4. 弯中自动收紧(M5 lateral deceleration slip 思路:直线贴极限/弯中回保守)— 二期,触屏玩家最大风险场景的对冲。
5. overlay ABS 介入视觉指示 — 独立增强。
6. p0 低速辅助档 — 若用户日后反馈低速问题再启用。
7. **0x3D4(currentBrakeBiasFront)读法未解** [?] — float 读出 denormal≈0、int 读出 2049,两试皆非;非功能字段,diag 已移除;未来若需读配平真值需重新确认字段类型/偏移。

## 9. 红线(实现时必守)

1. **白名单 `is_target_player_car`**(RoadForce 全车必经,误写 AI 车瘫场)。
2. **基线在首次覆写前捕获**(b/T_b 均可能被玩家配平改过),切回默认恢复;不写 ctor/推导默认。
3. **每帧绝对值写**(b=档位值;T_b=基线×缩放),禁止现值×系数复利。
4. 透传路径禁日志;abs_diag 白名单内限频。
5. 迁移语义:旧 enable_abs=false 必须落"关闭"档。
6. 改完跑 `:app:lint`。