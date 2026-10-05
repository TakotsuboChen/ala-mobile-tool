# HANDOFF — 读全文再开始干活

生成时间: 2026-10-05T22:09:02+08:00 · Git HEAD: `948efb7`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `948efb7`（本地领先 origin/main 2 个提交，**未 push**——见 §2）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `948efb7`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 待重探的 [?]: §5 全部继承项 + 本轮新增的官版/跨品牌项
- 先读: `native/src/kerb_haptic.c` 常量区 + `HapticMixer.kt` 的 `Envelope`/`kerbThrottleAllows` 注释（改路肩振感前必读，内含全部已证伪方案）

## 1. 当前目标
**本轮目标已完成：新增「路肩振感反馈」功能 + 配置页 UI 调整**。信号源、输出引擎、跨振感隔离、默认关闭 + 开启确认弹窗、图标更换全部落地并装机。**现无进行中目标**——等用户指派下一项。

## 2. 已验证状态 — 工作实际停在哪
- [V] **两个切片已本地提交**：`0f71f57`（路肩振感 + UI）→ `948efb7`（CLAUDE.md 同步）→ 本 HANDOFF.md 提交。**`main` 领先 origin/main 2 个提交，尚未 push**。
- [V] **门槛全绿**：`rm -rf app/build && ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` → **EXIT=0**，BUILD SUCCESSFUL，Lint 64 warnings 全在 baseline（0 error）。
- [V] **已装机**：`adb install -r` Success，设备 MEIZU_20（`192.168.50.142:5555`）；装机前后 **1.0.4 Alpha 1 / 104100**（**版本号未动**）。
- [V] **图标像素级验证**：`VibrationIntensityIcon`（变换烘焙进坐标）与 `KerbIcon`（两条子路径合并）均 `rsvg-convert` + `compare -metric AE` = **0**；Kotlin `d` 串 vs 参考串逐字符 **MATCH**；Kerb `z` 数 4 / 子路径 4 与原图一致。
- [V] **实机日志验证（用户自测，共存版）**：桥接后真实离场「最后一颗颗粒 → 抓地力」= **18/18 ≤26ms**（min 8 / p50 14）；形态只剩 `grip-flat` / `kerb-only` 两种（`grep -c grip-floor` = 0，垫底形态彻底消失）。
- [?] **用户手感反馈**：用户报「比之前好，但还能感觉到」——**日志判不了的体感结论以用户为准**。残留的是"周期性颗粒串固有的一拍"，已向用户说明无法在不违反"路肩优先"的前提下消除。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ rm -rf app/build && ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → Lint found 64 warnings, 4 hints (3 errors, 14 warnings filtered by baseline)
  → BUILD SUCCESSFUL in 21s          EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk
  → Performing Streamed Install / Success
```

## 3. 决策与理由
- **路肩信号源 = `IRDSWheel.materialIndex(0x2E8) == -5`** [V]——反汇编 `IRDSCarVisuals.TireModelVisuals`(0x1A68B4C) 实证游戏自己就这么判（`cmn w8,#5` → `kerbSound=true`），`kerbSoundUpdate` 读它播路肩音 ⇒ 与路肩音逐帧同步，模块不分析赛道区域。否决：`get_SurfaceType()`（只读封装，全 `.so` 零 `bl` 指向 = 死代码）。
- **颗粒率 = 用户给定线性映射 `v_mps × 1.25`**（0.8 m/s → 1 Hz，无上限）[V]——用户明确要求"频率我来定"。否决：复现游戏音高曲线（实机证明 `kerbMaxPitch` 恒 = 1.0，是常数、零速度响应）。
- **路肩期间抓地力彻底静音（路肩优先），静默段填 0** [V]——用户规格第 2 条起就要求。**否决 `kerbTapWave` 垫底方案**（曾为"无缝"在脉冲间垫抓地力载波）：用户实测立刻报「被淹没/松松散散」——垫底就是"路肩期间抓地力在响"，且载波填掉静默使 LRA 不停机、撞击退化成连绵振动。
- **交接靠"响应速度 + 桥接波形"，不靠垫底** [V]——(1) `tick` 的 `grainChanged` 分支立即下发（不受 `HARD_MIN_MS` 节流）；(2) native `KERB_OFF_HOLD_FRAMES` = 1 帧（滞回窗口 = 抓地力被压制的时长，宁小勿大）；(3) 桥接波形 `[最后一颗颗粒×3段][抓地力载波×93段]` + `repeat=n`（前 n 段只播一次，之后无限循环）——消掉静默尾巴且马达不停机。实测 ≤26ms。
- **删除路肩独立强度滑条，强度固定满幅** [V]——本机 primitive 路径下 HAL 把 `PRIMITIVE_CLICK` 当 4ms 桩、忽略 `scale`，滑条空转（用户实测「调节强度并没有什么用」）。否决：保留滑条但 <100% 改走 waveform（低强度段手感变软 + 99↔100% 有台阶）。
- **路肩默认关闭 + 开启时弹确认** [V]——用户定案：路肩会覆盖抓地力振感、可能影响抓地力判断。
- **`VibrationIntensityIcon` 用"烘焙坐标"而非 `viewportOriginX/Y`** [V]——该 `d` 以**相对** `m` 起笔，`group(translation)` 与 `PathParser` 相对起点的交互无文档保证；烘焙成绝对 `M` 后坐标域规整为 `0..W`，歧义消失（`AE=0` 验证）。

## 4. 失败的尝试 — 不要再试
- **[X] `kerbTapWave`：脉冲之间垫抓地力载波** [V]——用户实测「被抓地力振感淹没，没有屏蔽掉，松松散散」。垫底 = 路肩期间抓地力在响（违反硬规格）+ 填掉静默致 LRA 不停机。**静默段必须填 0**。
- **[X] 用 `sleep N` 去"等用户玩一局"再抓 log** [V]——用户明确否定：用户在那段时间**已经玩了**，抓到的本就是他们的游玩数据，事后又叫他们"再玩一次确认手感"= 同一份数据要两遍。**需要一次游玩会话 → 显式请求 + 等用户口头确认，不用定时器；一次会话只服务一次验证；主观手感要在请求游玩时一并说清。**
- **[X] 自行 `force-stop` + `am start` 重启游戏** [V]——用户强烈纠正（「不是说好了已经记忆了吗」）。**改→构建→install 可以自己做；重启归用户，必须先问。** goal/Stop-hook 的"继续"提示**不是**接管用户硬件的授权。
- **[X] 用日志读数当"手感结论"** [V]——日志能证"间隔 ≤26ms"，证不了"可感不可感"。只有用户的耳朵能作数。
- [X] 继承死路（详 `.handoffs/20261005220855-handoff.md` §4 及更早）：通道②`|Fy|/(μ·Fn)` / 通道②`α/maxAngle` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / `viewBox` 非零原点照搬 / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `result::class.simpleName` 记日志 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` / 用"探针窗口到达间隔"证明掉帧 / 用构造快照 `position` 做触摸基准 / 在 tick 里做停滞检测。

## 5. 已知坑
- ⚠️ **路肩「可感间隔」压不干净是物理下限** [V]——颗粒是周期性串（15 m/s → 53ms 周期），最后一颗落点随机；桥接已把 kerb-off→抓地力压到 ~16ms，但"最后一颗→kerb-off"那段属颗粒节奏本身。**唯一能彻底消掉的路是"极低幅抓地力垫底"，而它违反"路肩优先"，已被用户否决。**
- ⚠️ **primitive 路径天生没有幅度维度** [V]——`PRIMITIVE_CLICK` 的 `scale` 被 HAL 忽略（本机 4ms 桩）；要保"哒哒哒"手感就不能离开 primitive ⇒ 强度旋钮与手感二选一。
- ⚠️ **`createWaveform` 的 `repeat` 是"回绕起点段索引"** [V]——`0` = 从头无限循环，`n` = 前 n 段只播一次、之后从第 n 段循环。桥接波形依赖此语义。
- ⚠️ **无限循环波形必须显式 `cancel()`** [V]——primitive 播完即止，顶不掉它；切到路肩时必须 cancel（且放在节流之前）。
- ⚠️ **"生产者按帧推送的状态量"停摆后永久冻结** [V]——判据放读侧，时钟用 `CLOCK_MONOTONIC` 墙钟。路肩与抓地力均有 300ms 看门狗；`TcAbsIndicatorView` 未排查。
- ⚠️ **中文词全量替换前必须 grep 标识符复用** [?]——词若恰是资源 ID / JSON key 的一部分，全量替换破坏兼容性。
- ⚠️ **本设备实测走 Envelope 振感路径** [?]——`path=env` 说明共存版带 VIBRATE；**官版（`com.Vince`）无此权限走 `Fallback`，未实机验证**；**其他品牌（OPPO/Vivo/一加/Realme/iQOO/华为/小米/荣耀/红米）全部未验证** [?]。
- ⚠️ **存量用户配置不会被新默认值覆盖** [?]——`optString(KEY, default)` 只在键不存在时生效；本次路肩默认从开改关，**老用户仍是开**（旧 JSON 有 `kerb_haptic_enabled:true`），`kerb_haptic_intensity` 死键被忽略（无害）。
- ⚠️ **继承未验收项** [?]：金标播报实机触发 / 官版整体 / 自动 DRS 的 `playercar`(0x9C) 回落分支 / 自锁超车"抬起被吞"直接证据 / 手柄路径 / 崩溃自捕新增能力。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明（含重启游戏）** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明"做什么/为什么/多久/你该做什么"**。

## 6. 下一步（有序）
1. **（用户发话才做）push 本地 2 个提交**——`main` 领先 origin/main，`git status` 显示 `[ahead 2]`；push 属对外操作，须用户明确要求。
2. 让用户**自行重启游戏**验证：路肩开关默认关、抓地力含振感时开启弹确认框（左灰「取消」/ 右蓝「确认」）、新图标观感、路肩上下沿无分隔线。
3. 回归官版（`com.Vince`）：验证 `Fallback` 振感路径出振、不抛 `SecurityException`。
4. 跨品牌适配实机验证（至少一台非魅族机）——primitive 支持度与手感差异。
5. （可选）`TcAbsIndicatorView` 是否有同类"状态量冻结"语义（见 §5）。
6. （可选）正式发版时**先问用户版本号**，再做 module.prop 对齐 + tag（继承）。

## 7. 留给用户的开放问题
- 残留的"最后一颗颗粒 → 抓地力"一拍间隔，是否接受为物理下限（唯一替代方案是垫底，已否决）？
- 抓地力「最大振动强度」默认 50% 的手感如何？
- 路肩振感在**其他品牌**上的手感（本机为魅族 primitive 路径，其他机可能走 waveform 兜底、手感不同）？
- 官版（无 VIBRATE 权限）的 `Fallback` 路肩振感可接受吗（该路径完全未验证）？
