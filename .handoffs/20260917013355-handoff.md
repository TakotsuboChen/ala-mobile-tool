# HANDOFF — 读全文再开始干活

生成时间: 2026-09-16T01:22:32+08:00 · Git HEAD: `e1e1d2f`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `e1e1d2f`（2026-09-16 01:22）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `e1e1d2f`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/NPATCH_CACHE_CRASH_NOTES.md`（**用户报"开屏闪退"先读它**）+ `app/src/main/kotlin/tools/alamobile/mod/util/LogExporter.kt`

## 1. 当前目标
**已完成**：① NPatch 缓存损坏故障（三次复现）定案 + 修正用户话术 + issue #147 更新/评论；② 日志导出并入 NPatch 框架日志。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作已提交推送**：`da1ef3a`（LogExporter）、`ba6dcd5`（NPATCH_CACHE_CRASH_NOTES）、`e1e1d2f`（CLAUDE.md 索引同步）。三次 push 均成功。
- [V] **门槛**：`:app:lint` exit=0（58 warnings/4 hints，与开工基线持平）；`:app:assembleRelease -x lintVital*` exit=0；日志红线自查 clean（改动文件无直用 `Log.x`）。
- [V] **已装机**：`adb install -r app/build/outputs/apk/release/app-release.apk` → `Success`，设备 OnePlus `OPD2413`（ColorOS 16.0.9.400 / A16，serial `e98a35cb`）。本仓版本号**未改动**（1.0.4 Alpha 1 / 104100）。
- [V] **本机造数据实测 LogExporter 三条路径**：排除过滤（含 `] Loaded patch config` 的文件被跳过）✅、top-N 按 mtime 降序取 3 ✅、空集跳过+不崩 ✅。**假文件已全部删除**（`npatch/` 目录整个移除，用户原有 5 个文件完好）。
- [V] **对外操作已发**：#147 正文更新（4 处事实精修，证据代码块 45 行逐字节比对无改动）+ 评论 `id 5683329250`；回读线上内容 dif 确认落库。
- [V] 工作区 clean。
- [V] 新增条记忆 `adb-device-is-always-mine-users-are-remote`，并给 `wireless-adb-mdns-rediscover` 加了前置条件。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint                → exit=0, BUILD SUCCESSFUL（58 warnings, 4 hints）
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
                                     → exit=0, BUILD SUCCESSFUL
$ adb install -r .../app-release.apk → Success
```

## 3. 决策与理由
- **NPatch 故障根因锁定 + 用户处置改为"清游戏缓存"** [V]——坏文件在**游戏**的 `dataDir/cache/code_cache/`，不在 `top.nkbe.npatch`；清 NPatch 数据无效且**负收益**（删模块 native 缓存，`Load modules` 45ms→230ms）。否决："清 NPatch 数据重注入"（旧话术，已被两个用户各自执行错）。
- **判定指纹三条**：`cacheApkPath` 恒定 + 连续同栈崩溃 + 全程零 `Extracting` [V]——判据是**指纹不是机型**（ColorOS 16 / OriginOS 6 两套 ROM 机制相同）。
- **LogExporter 排除含 `] Loaded patch config` 的 npatch 日志** [V]——该行由 `LSPApplication` 在**模块装载阶段**打印，说明本次启动模块真跑过，不可能是崩溃现场；实测过滤生效。
- **给上游的日志问题是"绕过 XLog"而非"级别太低"** [V]——`XposedLogPrinter.log()` 的 switch 对 DEBUG/INFO 都正常写出，无按级别丢弃分支；真因是 `OriginApkHelper` 直接 `import android.util.Log`。否决："改 Log.d 为 Log.i"（照做仍不落盘，会浪费维护者时间）。
- **本机不是真实 NPatch 环境** [V]——两台设备的 media 目录都无 `npatch/`，故端到端验证只能靠造数据（单元级）。

## 4. 失败的尝试 — 不要再试
- **[X] 清 NPatch 管理器数据修闪退** [V]——例 2 实证无效（坏文件在游戏 dataDir）；且删模块 native 缓存致下次启动全量重解压。
- **[X] 靠"关作用域也崩"排除模块** [V]——只能证"作用域不是变量"，**"模块零日志"是更强的直接证据**（游戏进程日志段为空 = 模块代码未执行过）。报告用后者。
- **[X] `Log.d` 级别过滤导致 cache-hit 不落盘** [V]——本会话一度写入 issue 又更正；真因是绕过 XLog。
- **[X] 用 `tail` 截断 gradle 输出验门槛** [V]——会吞真实退出码；改 `> /tmp/x.txt 2>&1; echo exit=$?`。本会话已按此重跑。
- **[X] 预设 adb 传输方式** [V]——见第 5 节，已入记忆。
- 继承死路 [X]（详 `.handoffs/20260916012232-handoff.md` §4 及更早归档）：编辑层尺寸=控件尺寸+clipChildren 修四角 / 变暗层插 index 0 / `withEndAction` 做淡出收尾 / 提示文字画在编辑框 onDraw / `setShadowLayer` 加阴影 / `result::class.simpleName` 记日志 / 锁屏态 monkey 拉起 / 复用 `Logger.gameMediaDir` / 踏板"单向补送"·"MOVE 间隔判据" / `coroutineScope{}` 内多源竞速 / `***text***` 粗斜体 / MarkdownText 表格 / Remote Preferences `remove()` 清 token / `scheduleDraw` 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节。

## 5. 已知坑
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]——两集合不相交，永不互相顶替；且**不预设有线/无线**，每次先 `adb devices -l`（本会话预设无线、按 mdns 反复试 `192.168.50.123:38997` 全被拒，实际是有线）。见记忆 `adb-device-is-always-mine-users-are-remote`。
- ⚠️ **LogExporter 的 3 行诊断日志的去留未定** [?]——现保留（段命中数 + 每文件字符数）；建议降为只留"命中 N 个"一行。这是为本次排查临时加的，属噪声候选。
- ⚠️ **NPatch 段未在真实 NPatch 环境端到端验证** [?]——本机无该环境，只做了造数据单元级验证。
- ⚠️ **`Logger.gameMediaDir` / `LogExporter.gameMediaDir` 是两份实现** [?](继承)——路径字面量两处漂移，可选收拢（`LogExporter.kt` 自己 private 重算）。
- ⚠️ **旧 `Android/data/<pkg>/files/` 下日志为历史残留** [?](继承)——迁移后不清理；用户报"日志没更新"先确认看的是 media 目录。
- ⚠️ **日志迁移未在官版（com.Vince）单测** [?](继承)——仅共存版实机验证。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无可滚动正文 `weight` 时平板可能重演窄条按钮（修法见 CLAUDE.md 弹窗排版铁律）。
- ⚠️ **`clearAuth` 的 `no service bound, daemon stores NOT cleared` 是设计内 digest** [V](继承)。
- ⚠️ **编辑模式重构后仅共存版 + 双踏板模式实机走过** [?](继承)——SINGLE 模式、换挡控件编辑层、官版包名未单独回归。
- ⚠️ **对话纪律** [V](继承)——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 **别在用户锁屏时操作设备** 8 **动用户设备状态前先说明**（本会话写假文件未事先告知）。

## 6. 下一步（有序）
1. （可选）决定 3 行诊断日志留几行，改完重跑 `:app:lint` + 装机。
2. （可选）回归 SINGLE 模式与换挡控件的编辑层（继承，尚未单测）。
3. （可选）官版（com.Vince）跑一遍日志导出，验证两包名对称（继承）。
4. （可选）加固 `EulaDialog`/`UpdateDialog` 正文 `weight`（继承）。
5. （可选）验收权限门控页 / 真实群验证 bot 投递 / ABSdiag·TCdiag 降频（继承）。

## 7. 留给用户的开放问题
- 3 行诊断日志保留几行？
- 旧 `Android/data/<pkg>/files/` 残留日志要不要主动清理？（继承）
- 那两条锁屏态 SIGSEGV（`located=0`，疑与模块无关，属 Unity 冷启动）是否要单独追？（继承）