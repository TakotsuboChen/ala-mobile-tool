# HANDOFF — 读全文再开始干活

生成时间: 2026-09-14T23:40:00+08:00 · Git HEAD: `7388ba6`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `7388ba6`（2026-09-14 23:39）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `7388ba6`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `app/src/main/kotlin/tools/alamobile/mod/util/LogExporter.kt`（media 直读导出，本会话重写）

## 1. 当前目标
**已完成**：日志导出改为 AFA 跨包直读 `/sdcard/Android/media/<游戏包>/`，并删除「确保游戏在运行中」确认弹窗 + `awaitFreshLogs` 门控 + 整条游戏→模块日志推送链。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **改动已提交推送**：`bee9554`（工作切片：media 迁移 + 删弹窗/门控/推送链）、`7388ba6`（CLAUDE.md/README 同步）。
- [V] **构建/lint**：`:app:lint --rerun-tasks` **EXIT=0**（58 warnings 全为既有基线；比上次少 4，因删了 LogReceiver）；`:app:assembleRelease -x lintVital*`（环境崩溃规避）成功；`:app:compileDebugKotlin` 成功。
- [V] **实机端到端验证（用户操作，2026-09-14 23:25）**：用户「调模块配置→杀模块→进游戏→杀游戏→打开模块导出」。模块 pid 8544 于 23:25:40 导出（`exported 190435 bytes`），内容含游戏 pid 989 于 23:25:30 的最新日志（`pedalMode=SINGLE` 变更 + overlay 重建），**游戏已被杀（signal 9，23:25:36）、导出仍成功**——正是旧门控会拒绝的场景。导出文件 `build/logs/ala_tool_log_20260914_232540.txt`（已 pull 本地）。
- [V] **media 目录落盘实证**：游戏重启后 `/sdcard/Android/media/com.Takotsubo.AlamobileFormula/` 出现 `ala_tool.log`/`ala_tool_native.log`/`ala_tool_crash_native.log`，owner uid 10271（= 游戏进程，非模块），证实游戏进程写自己 media 目录成功。
- [V] 工作区 clean，与 origin/main 同步（`7388ba6`）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint --rerun-tasks   → EXIT=0
BUILD SUCCESSFUL in 1m 58s
Lint found 58 warnings, 4 hints (and 3 errors and 15 warnings filtered by baseline)
```

## 3. 决策与理由
- **日志通道从 `Android/data/<pkg>/files/` 迁到 `Android/media/<pkg>/`** [V]——media 不在 scoped storage 受限区，模块 App 持 AFA 可跨包直读；游戏进程写自己包 media = 同 uid 零权限。否决：维持 data 目录 + 广播推送——正是旧方案，闪退时导不出（本次要修的痛点）。
- **整条删除弹窗 + `awaitFreshLogs` 门控 + LogReceiver 推送链** [V]——门控在游戏闪退/被杀时恰好阻止导出崩溃现场，与"导出日志用于排查"的目的相悖；media 直读与游戏是否运行无关。否决：只删弹窗保留门控——门控仍在闪退场景拒绝导出。
- **导出返回 null 语义改为"未找到日志文件"** [V]——旧 `null` 会 Toast「请先启动游戏！」，现改为「导出失败，未找到日志文件」（不再暗示需要游戏）。
- **`Logger.gameMediaDir` 新增为 public** [V]——供 LogExporter 复用同一路径计算，避免两处硬编码 `/sdcard/Android/media/...` 漂移。

## 4. 失败的尝试 — 不要再试
- **[X] 认为游戏进程启动~15s 崩溃是模块改动引入** [V]——实为我在**锁屏（Asleep）状态下 monkey 冷启动**游戏所致；crash 记录 `located=0`/`pc=+0x0`（崩点不在模块内）。用户正常操作那轮（pid 989）**零崩溃**，signal 9 是用户手动杀。不要把它当回归。
- **[X] 用锁屏态 monkey 拉起游戏做验证** [V]——屏幕 Asleep 时冷启动 Unity 会 SIGSEGV（pid 18250/18717 两次），污染日志。验证前先 `KEYCODE_WAKEUP` 亮屏。
- 继承死路 [X]（详 `.handoffs/20260914233905-handoff.md` §4 及更早归档）：踏板"版本号早退豁免"/"单向补送"/"MOVE 间隔判据" / `coroutineScope{}` 内多源竞速 / `result::class.simpleName` 记日志 / 声称装机未执行 `adb install` / `***text***` 粗斜体 / MarkdownText 表格 / alpine 跑 glibc ELF / psql `-v` 传大 JSON / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / `screencap` 裁图目测尺寸 / `git rebase -i`。

## 5. 已知坑
- ⚠️ **旧 `Android/data/<pkg>/files/` 下的日志为历史残留** [?]——迁移后不清理，新日志只写 media。用户报"日志没更新"先确认看的是 media 目录。
- ⚠️ **日志迁移未在官版（com.Vince）单测** [?]——仅共存版实机验证；LogExporter 对两包名都遍历，理论对称。
- ⚠️ **同进程双路径** [?]——Logger 游戏进程优先 media，`mkdirs` 失败才回落 externalFilesDir；未见失败案例，但回落时导出会读不到（LogExporter 只读 media）。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无 weight 的可滚动正文 + 按钮同列，平板可能重演窄条。修法见 CLAUDE.md 弹窗排版铁律。
- ⚠️ **`clearAuth` 的 `no service bound, daemon stores NOT cleared` 是设计内 digest** [V](继承)——NPatch 清数据后首启必现一次，不是故障指纹。
- ⚠️ **对话纪律** [V](继承)——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 **别在用户锁屏时操作设备**。

## 6. 下一步（有序）
1. （可选）官版（com.Vince）跑一遍导出验证两包名对称。
2. （可选）清理旧 `Android/data/<pkg>/files/` 残留日志文件（或在迁移说明里告知用户）。
3. （可选）同法加固 `EulaDialog`/`UpdateDialog` 正文 weight（见 §5）。
4. （可选）验收权限门控页 / 真实群验证 bot 投递 / ABSdiag·TCdiag 降频。

## 7. 留给用户的开放问题
- 本次日志导出改造是否要正式发版（涉及版本号，需用户定）？还是先转 release APK 私发验证？
- 旧 `Android/data/<pkg>/files/` 残留日志要不要主动清理（用户设备上会一直躺着）？
- 那两条锁屏态 SIGSEGV（`located=0`，libil2cpp 空指针）是否需要单独追（可能与模块无关，属 Unity 冷启动）？
