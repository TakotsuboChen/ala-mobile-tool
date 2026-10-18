# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T21:46:00+08:00 · Git HEAD: `c6b7ea0`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `c6b7ea0`（已 push）· 服务端 `main` @ `f48e658`（本会话未动）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `c6b7ea0`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `LeaderboardScreen.kt:607-620`（本会话修复点）

## 1. 当前目标
无进行中的功能切片。本会话两件事：① 修榜单用户名省略号不生效（`maxLines` 漏 `overflow`）；
② 纠正"OPD2413 = OPPO 平板"的机型误称并落盘。

用户 2026-10-18 对围场功能定案「功能基本正常，暂时这样，下个会话再改」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **榜单用户名省略号已修**：`LeaderboardScreen.kt` `BoardRow` 的 `Text` 补
  `overflow = TextOverflow.Ellipsis`（+7 行，含 import 与注释）。根因 = 只写 `maxLines = 1`
  时 `overflow` 默认 `TextOverflow.Clip`，硬裁切在容器右缘。
- [V] **APK 内实证**：`mapping.txt` 反查 `LeaderboardScreenKt -> wz0`、`BoardRow -> c`；
  `baksmali` 反汇编后 `wz0.smali` 的 `c()` 在用户名 Text 调用点有 `v20=0x2`（Ellipsis）
  + `v22=0x1`（maxLines）。源码 21:32:51 → APK 21:36:09。
- [V] **lint + release 通过**：`LINT_EXIT=0` / `BUILD SUCCESSFUL in 1m 31s`；
  `assembleRelease` `BUILD SUCCESSFUL in 1m 27s`（11,359,723 B）。
- [V] **已装机并由用户验收**：魅族 20（`192.168.50.142:5555`），
  `lastUpdateTime=2026-10-18 21:42:11`，`versionCode=104140` / `1.0.4 Alpha 4`。用户回「ok 了」。
- [V] **全仓 `maxLines` 配对检查**：6 处全部有 `overflow`。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`，服务端 `1.0.1`。未打 tag、未发 Release。
- 工作区: 仅 HANDOFF.md 与 `.handoffs/` 归档（本 handoff 提交内容）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1m 31s（66 warnings / 4 hints；2 errors 与 14 warnings 为 baseline 既有项）
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 27s，APK 21:36:09（11,359,723 B）
$ javap -p -c LeaderboardScreenKt.class | grep TextOverflow
  → TextOverflow$Companion.getEllipsis-gIe3tQ8()，位于 BoardRow 方法体内
$ adb -s 192.168.50.142:5555 install -r <apk>   → Success
$ adb ... shell dumpsys package tools.alamobile.mod | grep lastUpdateTime
  → 2026-10-18 21:42:11
```

## 3. 决策与理由
- **补 `overflow` 而非调布局宽度** [V]——文字被切在容器边缘且无省略标记，
  是 `overflow` 缺失的典型症状，不是 `weight`/`width` 不够。
- **顺手做全仓 `maxLines` 配对扫描** [V]——同一模式复制了 4 处、3 对 1 错，
  说明这是可复发的复制粘贴漂移，扫一遍成本低于下次再报。
- **机型对照单独落盘为记忆文件** [V]——此前只在别的记忆里"顺带提一句"，
  检索不命中，导致同一个错误反复犯（见 §4 第一条）。

## 4. 失败的尝试 — 不要再试
- [X] **把 `OPD2413` 说成 "OPPO 平板"** [V]——2026-10-18 用户当场纠正：
  「什么 OPPO 平板，我说了多少次一加平板 2 Pro」。**它是 OnePlus（一加）平板 2 Pro**，
  与 OPPO 同属欧加集团、系统都叫 ColorOS，但机型名是用户的身份指称，写错即事实错误。
  已落盘 memory `opd2413-is-oneplus-pad-2-pro`（含两台设备对照表）。
- [X] **用 `unzip -p <apk> classes.dex | strings | grep TextOverflow` 验证** [V]——
  R8 把 Compose value class 压成 int 常量，符号被抹掉 ⇒ **恒零命中**，会把已修报成没修。
  正确链路见 CLAUDE.md「R8 会把 Compose value class 参数内联」条。
- [X] **Edit 报错后直接重试同一字符串** [V]——上一次 Edit 返回 "internal error" 时内容
  **已经落盘**，盲目重试必然失配。**先 Read 目标区间确认当前内容再决定是否重试。**
- [X] **zsh 里 `unzip -p "$APK" classes*.dex` 的 glob 不展开** [V]——报 `no matches found`，
  且失败时 `grep -c` 仍返回 0（假阴性）。先 `unzip -l` 取实际 dex 名。
- [X] 继承死路（详 `.handoffs/20261018212807-handoff.md` §4 及更早）：VPS 上 `apt-get update`
  构建镜像 / `FROM` 复用 alpine 镜像 + COPY glibc 产物 / 轮询等待慢构建 / 真库测试并行跑 /
  `--rerun-tasks` 重编 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 / 抓地力加地板 /
  `HapticGenerator` / `pm grant VIBRATE` / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 /
  变暗层插 index 0 / 踏板「单向补送」/ `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 /
  给旧 APK 留 `(NPatch 845)` 回滚副本 / 用 `+6` 偏移硬推 NPatch 版本号。

## 5. 已知坑
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]——只写 `maxLines = 1` 默认 `Clip` 硬裁切。
  见 memory `compose-maxlines-overflow-orthogonal`。
- ⚠️ **R8 内联 value class 使 `strings` 搜符号假阴性** [V]——见 CLAUDE.md 构建验证段。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 比较恒真** [V]（继承）——
  空状态必须短路返回 `false`。见 memory `lazylist-pagination-sentinel-trap`。
- ⚠️ **分页是否生效的判据是「请求间隔」不是「请求次数」** [V]（继承）——见同上 memory。
- ⚠️ **围场 UI 日志在模块进程 `filesDir`，需 root** [V]（继承）——见 memory `module-log-two-process-split`。
- ⚠️ **`strings` 查不到中文 ≠ 改动没进包** [V]（继承）——用 `grep -a` 字节搜。
- ⚠️ **NPatch `VERSION_CODE` = `git commitCount`** [V]（继承）——见 memory `npatch-versioncode-is-commitcount`。
- ⚠️ **zsh 里 `UID` 是只读内置变量** [V]（继承）——换 `DID`/`USER_ID`。
- ⚠️ **设备上有两台** [V]——`e98a35cb` = **一加平板 2 Pro**（OPD2413），
  `192.168.50.142:5555` = 魅族 20。多设备时 adb 必须 `-s` 指定。
- ⚠️ **VPS docker 需 sudo** [V]（继承）。
- ⚠️ **服务端容器无请求级日志** [V]（继承）——验证行为靠数据内容比对。
- ⚠️ **`loadingMore` 绝不能放进 `LaunchedEffect` 的 key** [V]（继承）——会永久卡"加载中…"。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——新增设置项前先问。
- ⚠️ **物理上限解除 ≠ 视觉/UI 跟着变** [V]（继承）——见 memory `physics-limit-vs-visual-readout`。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **IL2CPP 浮点字段必须用 `find_field_access_f.py`** [V]（继承）。
- ⚠️ **日志 2MB 滚动截断会吃掉安装期证据** [V]（继承）。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——任何 adb 动作都算。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [V]（继承）——看日志环境块的构建指纹。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——判据 = 日志含 `Pushed game logs via chunked broadcast`。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [?]（继承）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [?]（继承）——当前约 41 项。
- ⚠️ **用户日志在 Windows 盘** [?]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **等用户对围场其余功能的反馈**：① 信息卡右侧箭头点击；② 车手主页四维筛选；
   ③ 榜单点行进主页；④ 长昵称在**车手主页**与**主页卡片**的省略表现。
   异常先拉模块进程日志（`/data/data/tools.alamobile.mod/files/ala_tool.log`，需 root）。
2. **（可选）`DriverScreen` 补分页诊断日志**：目前只有 `LeaderboardScreen` 有
   `load more: offset=...`，车手主页靠请求间隔推断。
3. **（可选）846 包分发**：`安装包/Ala Mobile Pro 8.0.6 Fix 2.apk` 已入库待发。
   发之前确认用户是否需要（不主动提版本号/发版）。
4. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块 `1.0.4 Alpha 4`、
   服务端 `1.0.1` 均未动。
5. **（可选）服务端镜像清理**：VPS 上 40+ 历史镜像占空间；compose 当前指向 `1.0.1-paging`，
   而仓库 `deploy/docker-compose.vps.yml:8` 写的是 `paddock-api:1.0.1`（**两者不一致，待对齐**）。

## 7. 留给用户的开放问题
- `deploy/docker-compose.vps.yml` 的镜像 tag（`1.0.1`）与 VPS 实跑 tag（`1.0.1-paging`）
  不一致，要不要改成实际值？
- 车手主页仍有「计时赛积分 / 有成绩赛道 / 零辅助最快圈」三列，是否也要调整？
- 分页"到底"目前靠 `entries.size >= total` 静默判断，是否需要显式"没有更多了"提示？
