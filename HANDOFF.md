# HANDOFF — 读全文再开始干活

生成时间: 2026-09-15T00:11:08+08:00 · Git HEAD: `7a11da3`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `7a11da3`（2026-09-15 00:11）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `7a11da3`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/LeaderboardScreen.kt`（本会话改动点）

## 1. 当前目标
**已完成**：排行榜（积分榜/赛道榜）的数字列改等宽字体，修右对齐「数字宽度参差、整列不齐」。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **改动已提交推送**：`35034c9`（工作切片：LeaderboardScreen 等宽数字）、`7a11da3`（CLAUDE.md 同步）。
- [V] **构建/lint**：`:app:compileDebugKotlin` 成功；`:app:assembleRelease -x lintVital*`（环境崩溃规避）成功；`:app:lint` **EXIT=0**（完整命令跑完取退出码，未截断）。
- [V] **已装机验证**：`adb install -r app/build/outputs/apk/release/app-release.apk` → `Success`；设备 MEIZU 20（Flyme），`dumpsys package` 显示 `versionName=1.0.4 Alpha 1`、`lastUpdateTime=2026-09-15 00:02:36`。本仓版本号**未改动**（用户只授权修样式）。
- [V] **用户实机确认效果 OK**（本会话末口头反馈"ok 了"）——等宽字形观感用户接受，无需备选方案。
- [V] 工作区 clean，与 origin/main 同步（`7a11da3`）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint   → LINT_EXIT=0
BUILD SUCCESSFUL in 2s
```

## 3. 决策与理由
- **数字列用 `FontFamily.Monospace`，而非调 textAlign/宽度** [V]——根因是系统 sans 默认**比例数字**（proportional figures，"1" 窄 "8" 宽），对齐只解决整体落点、解决不了字形本身宽窄不一。等宽字体（tabular figures 的字体级替代）令每个数字字形同宽，右对齐列即得"小数点对齐"观感（圈速 `1:02.345` 与 `1:22.345` 秒数位严格对齐）。否决：逐位用固定宽容器对齐——复杂且不通用。
- **名次列只对 `rank > 3` 套等宽** [V]——前三名是🥇🥈🥉 emoji，套等宽字体会回落成单色/异形字形（等宽字体通常无 emoji 字形）。
- **未升版** [V]——用户指令只授权修样式；版本号是用户决策域，构建/装机一律用仓库现版本（见全局红线）。

## 4. 失败的尝试 — 不要再试
- **[X] 认为游戏进程启动~15s 崩溃是模块改动引入** [V]（上一会话）——实为在**锁屏（Asleep）状态下 monkey 冷启动**游戏所致；crash 记录 `located=0`/`pc=+0x0`（崩点不在模块内）。不要当回归。
- **[X] 用锁屏态 monkey 拉起游戏做验证** [V]（上一会话）——屏幕 Asleep 时冷启动 Unity 会 SIGSEGV，污染日志。验证前先 `KEYCODE_WAKEUP` 亮屏。
- 继承死路 [X]（详 `.handoffs/20260915001108-handoff.md` §4 及更早归档）：认为游戏崩溃是模块引入 / 锁屏态 monkey / 用 code 里已有的 `Logger.gameMediaDir` 复用（实为各算各的，见 §5）/ 踏板"版本号早退豁免"/"单向补送"/"MOVE 间隔判据" / `coroutineScope{}` 内多源竞速 / `result::class.simpleName` 记日志 / 声称装机未执行 `adb install` / `***text***` 粗斜体 / MarkdownText 表格 / alpine 跑 glibc ELF / psql `-v` 传大 JSON / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节。

## 5. 已知坑
- ⚠️ **`Logger.gameMediaDir` / `LogExporter.gameMediaDir` 是两份实现** [V](本会话核实 )——上一会话 HANDOFF 称"新增 public 供复用"未落地：`LogExporter.kt:56-57` 自己 private 重算 `/sdcard/Android/media/$pkg`。语义有差异（Logger 版需游戏包名上下文，Exporter 要遍历两候选包名），但路径字面量仍两处漂移。可选收拢。
- ⚠️ **旧 `Android/data/<pkg>/files/` 下的日志为历史残留** [?](继承)——迁移后不清理，新日志只写 media。用户报"日志没更新"先确认看的是 media 目录。
- ⚠️ **日志迁移未在官版（com.Vince）单测** [?](继承)——仅共存版实机验证；LogExporter 对两包名都遍历，理论对称。
- ⚠️ **同进程双路径** [?](继承)——Logger 游戏进程优先 media，`mkdirs` 失败才回落 externalFilesDir；回落时导出会读不到（LogExporter 只读 media）。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无 weight 的可滚动正文 + 按钮同列，平板可能重演窄条。修法见 CLAUDE.md 弹窗排版铁律。
- ⚠️ **`clearAuth` 的 `no service bound, daemon stores NOT cleared` 是设计内 digest** [V](继承)——NPatch 清数据后首启必现一次，不是故障指纹。
- ⚠️ **对话纪律** [V](继承)——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 **别在用户锁屏时操作设备**。

## 6. 下一步（有序）
1. （可选）官版（com.Vince）跑一遍日志导出验证两包名对称。
2. （可选）收拢 LogExporter/Logger 的 gameMediaDir 两处实现（见 §5）。
3. （可选）加固 `EulaDialog`/`UpdateDialog` 正文 weight（见 §5）。
4. （可选）验收权限门控页 / 真实群验证 bot 投递 / ABSdiag·TCdiag 降频。

## 7. 留给用户的开放问题
- 排行榜等宽字体 + 日志导出 media 直读两处改动是否正式发版（涉及版本号，需用户定）？还是先转 release APK 私发验证？
- 旧 `Android/data/<pkg>/files/` 残留日志要不要主动清理（用户设备上会一直躺着）？
- 那两条锁屏态 SIGSEGV（`located=0`，libil2cpp 空指针）是否需要单独追（可能与模块无关，属 Unity 冷启动）？
