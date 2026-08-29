# HANDOFF — 读全文再开始干活

生成时间: 2026-08-29T21:04:55+08:00 · Git HEAD: `962fcbd5e5ef`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `962fcbd5e5ef` (2026-08-29)
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `962fcbd5e5ef`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `CLAUDE.md` + `hide_pedals_hook.c` 的"恢复账本"注释块（stash/restore 设计动机，本会话新增）+ `PedalOverlayView.kt` 的 `updateValuesFromPointer` 双源校验注释块 + `TC_LEVEL_DESIGN.md` §10 v1.5 + `ABS_LEVEL_DESIGN.md` §4 + `.handoffs/20260829205358-handoff.md` §4（死路总账）

## 1. 当前目标
两条并行线：①多指踏板污染修复（`845d970`）已实装并自测通过，**仍等复现用户装新 APK 回传日志**裁决污染层；②"隐藏原生油门/刹车"开关不生效 bug 已定位（单向阀缺陷）并修复（`ab29905`），Takotsubo 实机测试通过。主线 ① 的回传是核心等待；② 剩一个"重启场景 remote 配置旧值"的待裁决尾巴（见 §7）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **本会话修复（`ab29905`，`hide_pedals_hook.c` +109/−15）**：根因=`hide_if_active` 的 `SetActive(false)` 是单向阀，游戏不会自行恢复，关开关只停轮询、踏板永久隐藏直到重启。修复=恢复账本（stash 登记被隐藏 GO）+ 关闭后 tick 按帧节拍重新遍历、指针在册才 `SetActive(true)`；恢复全程遍历匹配，stash 死指针永不解引用；连续 40 pass(~20s) 无进展清账放弃。
- [V] **Takotsubo 实机测试通过**（用户会话中确认"测试通过"；设备 381QYFCN22B9A，先 force-stop 后 `adb install -r` Success）
- [V] **构建+lint**：`./gradlew :app:assembleRelease :app:lint` → BUILD SUCCESSFUL，**EXIT=0**，lint 0 errors（存量 warnings 基线量级）。注意 `assembleRelease` 不跑 lint。
- [V] 多指污染修复链（继承自上一会话，本会话未重新验证）：双源校验+源切换已实装并经 Takotsubo 自测；复现日志已裁决"坐标污染"，等新 APK 回传（红米 MIUI15 复现用户）。
- [V] `NativeBridge.hidePedalsApply()` 是零调用点死代码（grep 全量确认）——待清理，本次未动。
- 工作区: 干净，`main` 与 origin 同步（本会话三切片均已推送）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
./gradlew :app:assembleRelease :app:lint → BUILD SUCCESSFUL in 3s，EXIT=0（80 tasks，native 重编）
adb shell am force-stop + adb install -r → Success
git push → df6e183..962fcbd main（工作+文档两切片，成功）
```

## 3. 决策与理由
- **stash+遍历匹配恢复，而非存指针直接调** [V]——遍历拿到的对象必然存活；stash 里可能已随场景卸载的 GO 指针永不解引用（解引用死指针调虚方法=崩溃）。否决：对账本裸指针直接 SetActive(true)。
- **恢复走 tick（Unity 脚本线程），不在 `set_enabled` 直接做** [V]——`setHidePedalsEnabled` 跑在广播 receiver 线程，`il2cpp_runtime_invoke` 必须走 Unity 脚本线程语境；代价 ≤0.5s 延迟，可接受。
- **指针在册才恢复，而非"遍历到同名就恢复"** [V]——否则会把游戏本来就 inactive 的同名物体误点亮（stash 是"是我隐藏的"判据）。
- **40 pass 放弃机制** [V]——源对象随场景卸载后账本永不匹配即死循环白遍历；清账即可，新场景重建的原生踏板本来就是 active。
- **README 不动** [V]——修复不改变功能面描述；本次未发布不应混入 v1.0.2 release notes 段。
- 继承：双源校验+源切换设计、三档 20/100px 阈值、v1.0.2 发布流程（见上一份归档 `.handoffs/20260829205358-handoff.md` §3）。

## 4. 失败的尝试 — 不要再试
> 全部前向搬运，永不丢弃。完整历史见 `.handoffs/` 目录。

### 本会话新增
- （无新增死路；本次修复首一版漏改 `hide_buttons_recursive` 递归调用点传参、编译期被抓——提示：改 C 函数签名时 grep 全部调用点。）

### 继承死路（全部仍有效）
- [V] **断言"show taps 圆点不跟随"** → 用户实测纠正+官方文档证实圆点实时跟随 MOVE。对系统组件行为作断言前先查证。
- [V] **GameTurbo 设置层 A/B** → 复现用户试遍全部游戏相关设置无效。设置面被排除或深于设置面。
- [V] **MotionEvent.isResampled** → 编译 Unresolved reference（flagged API 未进 API surface）。不要再引入。
- [V] **`getRawX/Y(pointerIndex)` 直调** → API 29+，minSdk 26 lint 报错。统一走 `rawXAt`/`rawYAt` 等价式 helper。
- [?] **OnTouchListener 探 decorView 层事件** → ViewGroup listener 只在无 child 消费时触发（框架推理未实测）。窗口级对照需 hook Activity.dispatchTouchEvent。
- [V] 边框缝隙：逐像素半透明下所有"精确互补"方案全缝，over 链数学上无解，必须层内不透明。
- [V] 档位值 0.90 贴 native clamp 上限（低档 ≥0.9 被静默截断差异化消失）；把未实机口头数值标"定版"。
- [V] 押 p0 主旋钮被推翻；usesABS 恢复漏置位；只写 absEnable 不够（唯一门控 per-wheel usesABS）；只写 TCLSlip=eps 挡死起步；每帧写 ctor 默认≠真值。
- [V] 画笔模式技术成功被用户否决（"只认控制点"）；"换算法任意形状"撞信息论边界。
- [V] 触摸诊断：用 commit 时间推 APK 内容；"导出零数据=模块旧"单因推断；adb force-stop 用户在用设备。
- [V] 手势/测量：滑块 v1/v2 帧数 1/6、"设备性能"归因、GestureAxisBlocker 整向量消费、perfetto shorthand、测量期间手触、miuix 查源码先联网。
- [V] proxy_shift_up 打日志洪水（已修）；IL2CPP 调用不能 dlopen/直接调 RVA；后台 pthread 调 Unity API 崩溃。

## 5. 已知坑
- ⚠️ **多指污染待回传裁决** [V]（继承）——修复已给复现用户，日志回传判定见上一份归档 §6 的三分支（RAW_MISMATCH 出现/干净/零出现）。
- ⚠️ **隐藏踏板"重启后仍隐藏"可能= remote 配置旧值（Bug B，未验证未修）** [?]——若用户反馈关开关+重启游戏仍隐藏，读游戏进程日志（grep `readFromTargetProcess`/`remote preferences`）裁决 remote 陈旧 JSON 路径；备选修法=write() 加 `saved_at` 时间戳做 last-writer-wins。本次无证据指向它，未动。
- ⚠️ **ConfigReceiver 与 15s init 有竞态窗口** [?]（新发现，未验证）——游戏启动 10s 时广播写 false、15s 时 `initHidePedals(旧 settings 值)` 会覆盖回来。窗口窄（仅启动后 15s 内改配置），暂不修。
- ⚠️ **日志推送分片丢失** [?]（继承，用户裁决搁置）——LogReceiver 分片拼装无完整性校验。
- ⚠️ **flyme 冻结后台致 REQUEST_LOGS 延迟** [?]（继承，仅 Takotsubo 自机）——加固方向：游戏 onPause 主动推送。
- ⚠️ **配置页"任何时候满帧"未达成** [?]（继承）——冷启动 18-19% janky；动手前重跑 A/B 基线。
- ⚠️ 曲线编辑器 QP 求解高频路径 / pager settle 回抓 / 滑块 v3 快弹簧 [?]（继承，未恶化不动）。
- ⚠️ 0x414 死参数 / 0x3D4 读法 / 游戏内 ABS 设置对物理无效 / 计时赛 IRDSUIMobileControls 晚 2s / offsets_sheet 0x1A62E10 勘误未订正 [?]（继承）；GitHub 公式/Mermaid 渲染坑、IL2CPP ARM64 扫描三坑（先读持久记忆）。

## 6. 下一步（有序）
1. **等复现用户回传多指日志**（核心等待，继承）——回传后按三分支裁决（见 `.handoffs/20260829205358-handoff.md` §6）。
2. 若用户/社区反馈"关开关+重启游戏后踏板仍隐藏"→ 走 Bug B 路径：要日志裁决 remote 陈旧配置（备选修法见 §5 第二条）。实测未复现则搁置。
3. 追 ABS 档位 0.80/0.60/0.40 实机手感（继承，三轮未答）。
4. （可选清理）删 `NativeBridge.hidePedalsApply()` 死代码及其 JNI 声明占位（grep 确认零调用）。

## 7. 留给用户的开放问题
- 本次"测试通过"覆盖了哪些场景？运行时开→关切换已确认；**关开关后重启游戏**是否也验证过？（裁决 Bug B 是否真实存在）
- 继承：复现用户 HyperOS 具体版本/长按类全局功能/触控辅助 APP？双踏板漂满间歇还是持续？"无其他小米用户反馈"样本量？分片丢失修复优先级、冷启动验收标准、ABS 高档手感是否可感。