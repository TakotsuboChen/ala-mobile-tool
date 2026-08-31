# HANDOFF — 读全文再开始干活

生成时间: 2026-08-31T12:45:00+08:00 · Git HEAD: `9227460`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `9227460` (2026-08-31)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `9227460`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/LAP_HOOK_NOTES.md`（含 §4a 九会话模式矩阵——本轮核心产出）+ `docs/TRACK_IDENTIFICATION.md`（16 赛道表）

## 1. 当前目标
**用户定案（2026-08-31）：当前只做计时赛有效圈速，其他模式判定以后要继续挖但暂缓**——门禁已 9/9 全模式实测正确，功能处于"稳定收尾"态。数据源全就绪，剩余方向：计时赛圈速 UI 化（可选）与模式挖掘（远期）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **lap_hook 功能闭环**（`0596207`）：hook `LLV.Awake`(0x199DE28) + `HandleSectorsTimes`(0x1A0A1C4)；圈完成挂 order==2 即时判定；validLap 采信游戏原生位；会话最快有效圈本地维护。
- [V] **模式门禁 v2 实测 9/9 全对，代码零改动**：champManager→isTimeAttack 硬判 + champ==NULL=挂起。本轮全枚举采样（生涯澳 FP1/2/3/Q1/正赛 → 比赛周上海正赛 → 计时赛巴林 → 快速比赛伊莫拉 → GF 西班牙）全部门禁行为正确——计时赛记录 ✓，其余 8 场挂起 ✓。
- [V] **模式信号全枚举矩阵落定**（`bd85c17`，docs/LAP_HOOK_NOTES §4a）：`LAPsession[awake]` 诊断行（LLV.Awake 场景加载即打，进赛道无需驾驶）11 信号；关键发现：生涯正赛 sessBits=4 vs 比赛周正赛 sessBits=1（两条置位路径）、cdSess 节次枚举 0-3=FP1-Q1/6=正赛、GF=`GlobalVariables.isGrandFestival` 专用位、fullQuali=周末级属性、raceModes 全场景=0 确认无区分力。
- [V] **持久文档补共存版双包名**（`9227460`）：CLAUDE.md Overview 官版 `com.Vince.AlamobileFormula` + 共存版 `com.Takotsubo.AlamobileFormula` + 两包日志拉取路径。README 201 行原本已覆盖，未动。
- [V] **构建**：`./gradlew :app:assembleDebug :app:assembleRelease` → BUILD SUCCESSFUL，EXIT=0。实机已装 release（共存版设备），9 会话采样就是它跑出来的。
- 工作区: 干净，`main` 与 origin 同步（`bd85c17` 工作 / `9227460` 持久文档 / 本次 handoff 提交）。
- 继承 [?]：多指污染修复等红米用户日志回传；Bug B 未复现未修。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleDebug :app:assembleRelease → BUILD SUCCESSFUL，EXIT=0
盲判测试：9 条 LAPsession[awake] 盲判 6.5/9，错的 2 条反哺矩阵（正赛指纹分裂发现）
git push → bd85c17（工作）+ 9227460（持久文档）main，成功
```

## 3. 决策与理由
- **其他模式判定降为远期，现阶段只做计时赛** [V]（用户定案 2026-08-31）——v2 门禁已 9/9 全对，继续挖无行为收益；矩阵空格（Q2/Q3/多人）只影响未来 UI 的模式标签完整度。
- **LAPsession 诊断行保留不删** [V]——它是 UI 化的模式标签信号源；与 rfHits/hitAge（可删）区分处置。
- **诊断行挂 LLV.Awake 而非圈完成事件** [V]——模式信号进赛道时已定死；用户采样成本从"跑完整圈"压到"进赛道即退"。双门控（awake 必打 + sector 可选对照互补）。
- 继承：圈完成挂 order==2、validLap 采信原生位、champ==NULL=挂起、赛道身份=buildIndex、文档三分工、拦截器零浮点——见 `.handoffs/20260831124000-handoff.md` §3。
- 继承：实机测试默认 release 构建后**立即 adb install 装机**（一条链跑完，adb 找不到设备才问）——记忆 device-test-release-build，2026-08-31 事故加固。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录 + docs/LAP_HOOK_NOTES.md §6。

### 本轮新增
- [X] **只构建不装机就交采样清单** → 用户按清单白跑一轮（10 次进赛道全空转，设备上是旧版）。装机是取证链路第一环，`assembleRelease` 后必须立即 `adb install -r`。
- [X] **凭静态指纹盲判模式** → 9 场判对 6.5 场：生涯正赛 sessBits=4 ≠ 比赛周正赛 sessBits=1，"正赛有唯一指纹"假设被实测证伪。判定模式必须查 §4a 矩阵，勿按字段名字面义推断。

### 继承死路（lap_hook 三版演化 + 指示灯信号链 + FPSIMD，全部实机实证）
- [X] **圈完成判定等 order 2→0 回绕** → 判定延迟 30s+，第 5 圈"消失"。必须挂 order==2 事件本身。
- [X] **sectorOrder 按 1-based 判 ==3** → 永不命中（0-based）。
- [X] **把 HandleSectorsTimes 当过线单次事件** → 实为圈段过线事件簇（连发数帧 13–27ms）。
- [X] **gate v1 "champ==NULL → 放行"** → 快速正赛恒 NULL，正赛误记（Shanghai）。v2 改 NULL=挂起。
- [X] **trackToRace 当赛道名** / **bestLapTimeInfo 当历史最佳** / **raceModes/isQuali/sessBits 当会话类型判据** → 废签/分段表/无区分力，详见 docs/LAP_HOOK_NOTES §6。
- [X] **切片提交混入 git rename** → `git mv` 后要立即单独提交或 `git restore --staged` 分离。
- [X] **拦截器回调浮点操作** → s0 污染毁档位，严禁（docs/MODULE_ABS_NOTES §2c）。**WITH_FPSIMD 补救也不可信**。
- [X] ABS v1-v5 全否决（v6 饱和重映射定案）；指示灯信号链 v1-v5 全否决（拦截器定案）；proxy_shift_up 日志洪水（18810 条/21min）；IL2CPP 不能 dlopen/直调 RVA；后台 pthread 调 Unity API 崩溃——见 `.handoffs/20260831124000-handoff.md` §4。

## 5. 已知坑
- ⚠️ **拦截器回调零浮点是长期红线** [V]——复发即档位失效。
- ⚠️ **圈完成判定挂 order==2 是红线** [V]——改回回绕判定重现"消失圈"。
- ⚠️ **lap_hook 日志只许在 `order_changed || valid_changed` 边界内** [V]——事件簇路径加日志会洪水。
- ⚠️ **多指污染待回传裁决** [?]（继承）。
- ⚠️ **隐藏踏板 Bug B / ConfigReceiver 竞态 / 日志分片丢失** [?]（继承，搁置中）。
- ⚠️ **指示灯拦截地址与 lap_hook 全部 RVA 硬编码** [V]——8.0.4 专用；升级游戏必须重跑 Il2CppDumper + BuildSettings 解析（docs/LAP_HOOK_NOTES §7）。
- ⚠️ **矩阵空格** [?]——Q2/Q3（cdSess 4/5 预期值未采样）、多人房间（gv bit0）、`[sector]` 对照行未采过。挖其他模式时先补这些。
- ⚠️ **16 赛道表 12/16 条未实机跑过** [?]（继承）。
- ⚠️ **门禁 champ==NULL 多语义** [?]——NULL 实测=快速正赛/GF/计时赛新档；其他走 NULL 的会话未穷举，报异常先查 champManager 挂载。
- ⚠️ 曲线编辑器 QP/pager 回抓/滑块快弹簧/冷启动 janky [?]（继承，未恶化不动）。

## 6. 下一步（有序）
1. **计时赛圈速 UI 化**（当前 log-only，用户未拍板；数据源已就绪）——读模块本地 best（比游戏 absoluteFastestLap 早一个读点），模式标签可用 LAPsession 静态集 + §4a 矩阵。
2. （可选清理）删 `NativeBridge.hidePedalsApply()` 死代码 + 移除 abs_diag_log 的 rfHits/hitAge 诊断行。lap_hook LAPsession 行保留。
3. **若用户报 ESC 场景制动异常** → 按 `.handoffs/20260830004912-handoff.md` §4 做通道隔离。
4. （继承）等红米用户回传多指日志，回传后按三分支裁决。
5. （远期）其他模式判定继续挖掘——先补 §4a 空格（Q2/Q3/多人/`[sector]` 行），再挖 champManager 多语义。

## 7. 留给用户的开放问题
- 计时赛圈速 UI 化形态（overlay 显示当前圈/最快圈）？暂缓中，等用户发起。
- 工具按钮记忆位置无 UI 重置入口（继承）。
- TC/ABS 指示灯亮度/闪烁频率调节项？（继承，规格固定中）
- 继承：复现用户 HyperOS 版本；分片丢失修复优先级、冷启动验收标准。