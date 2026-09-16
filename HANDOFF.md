# HANDOFF — 读全文再开始干活

生成时间: 2026-09-17T01:34:21+08:00 · Git HEAD: `0ede3d1`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `0ede3d1`（2026-09-17 01:34）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `0ede3d1`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/NPATCH_CACHE_CRASH_NOTES.md`（**用户报"开屏闪退"先读它**）+ `app/src/main/kotlin/tools/alamobile/mod/util/LogExporter.kt`

## 1. 当前目标
**已完成**：排行榜数字列等宽改用 OpenType `tnum`（替代失效的 `FontFamily.Monospace`）+ 去掉积分值"分"后缀。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作已提交推送**：`b2dd08b`（fix: 榜单 tnum）、`0ede3d1`（docs: CLAUDE.md+README 同步）。两次 push 均成功。
- [V] **门槛**：`:app:lint` exit=0（58 warnings/4 hints，与开工基线持平；baseline 另过滤 3 errors+14 warnings）；`:app:assembleRelease -x lintVital*` exit=0；日志红线自查 clean（改动文件 0 处直用 `Log.x`）。
- [V] **已装机**：`adb install -r .../app-release.apk` → `Success`，设备 OnePlus `OPD2413`（ColorOS 16.0.9.400 / A16，serial `e98a35cb`）。版本号**未改动**（1.0.4 Alpha 1 / 104100）。
- [V] **用户实机验收通过**（`/handoff OK 了`）——等宽生效、观感可接受。
- [V] 工作区 clean（除本 HANDOFF.md 与归档文件）。
- [V] **`FontFamily` import 已从 LeaderboardScreen 移除**；`TabularDigits` 为文件级 `private val`。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint        → exit=0, BUILD SUCCESSFUL（58 warnings, 4 hints）
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
                             → exit=0, BUILD SUCCESSFUL
$ adb install -r .../app-release.apk → Success
```

## 3. 决策与理由
- **等宽改用 `tnum` 而非 `FontFamily.Monospace`** [V]——后者是 `GenericFontFamily("monospace")`，Compose 走 `Typeface.create("monospace", NORMAL)` = **运行时按族名查询**，由 ROM 决定命中文件。ColorOS 16 实测重定向到系统 sans（`cmd font dump` 报 DroidSansMono，但榜行数字相邻字形 advance 实测 `1→15px / 5→23px / 4→24px`，逐项吻合 `SysFont-Regular`；Flyme 12 同样失效）。`tnum` 是字体自带数字变体，对"ROM 换成哪个 sans"免疫。否决：内置字体文件进 APK（APK +200~500KB，字形风格与用户名割裂）。
- **作用域刻意收在榜单行内，不挂主题 `textStyles`** [V]——挂主题后版本号/档位/偏移量等无关数字一并变等宽，用户明确否决（"别的界面的数字也变成等宽了，太难看"）。等宽是**榜单列对齐**的排版需求，不是全局字体风格。已把这条写进 CLAUDE.md 防回归。
- **前三名 emoji 不再做 `rank <= 3` 分支** [V]——`tnum` 是数字字形替换特性，emoji 走另一套字形表，套上零影响；省一个会腐烂的条件。
- **删积分"分"后缀** [V]——用户要求，且与纯数字的圈速列统一。

## 4. 失败的尝试 — 不要再试
- **[X] 全局挂 `tnum` 到主题 `textStyles`** [V]——技术有效但产品错误：全模块数字变等宽，用户明确否决。等宽只对"纵向比较同一列数字"有价值。
- **[X] 用 `FontFamily.Monospace` 做等宽** [V]——被 ROM 族名重定向吃掉，ColorOS/Flyme 双双失效（见 §3 定量证据）。
- **[X] 指望 miuix `Text` 转发 `fontFeatureSettings`** [V]——`Text-Nvy7gAk` 只有 fontFamily/fontSize/fontWeight 等白名单形参，无该参数。必须走 `style = ...merge(...)`。
- **[X] 靠 `hasFontAttributes()` 早退解释失效** [V]——它只决定是否做 fallback SpanStyle；`applySpanStyle` 施加该特性在其**之后**，判据 `!= null && != ""`，与早退无关。已排除。
- **[X] 用 `app_process`/`dalvikvm` 跑 on-device Typeface 探针** [V]——`Assertion failed: src == nullptr && gDefaultTypeface == nullptr`（Linux 路径下 native typeface init 失败）；`dalvikvm` 另缺 `Log.println_native`。别在设备上跑独立 JVM 探针。
- **[X] 用 `tail` 截断 gradle 输出验门槛** [V]（继承）——会吞真实退出码；改 `> /tmp/x.txt 2>&1; echo exit=$?`。
- **[X] 预设 adb 传输方式** [V]（继承）——见 §5。
- 继承死路 [X]（详 `.handoffs/20260917013355-handoff.md` §4 及更早归档）：编辑层尺寸=控件尺寸+clipChildren 修四角 / 变暗层插 index 0 / `withEndAction` 做淡出收尾 / 提示文字画在编辑框 onDraw / `setShadowLayer` 加阴影 / `result::class.simpleName` 记日志 / 锁屏态 monkey 拉起 / 复用 `Logger.gameMediaDir` / 踏板"单向补送"·"MOVE 间隔判据" / `coroutineScope{}` 内多源竞速 / `***text***` 粗斜体 / MarkdownText 表格 / Remote Preferences `remove()` 清 token / `scheduleDraw` 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节。

## 5. 已知坑
- ⚠️ **读图会触发网关层 token 爆炸（本会话实证）** [V]——`Read` 把图放在 `tool_result` 内，本机网关 NewAPI（`newapi.takotsubo.cloud`）在 Anthropic→OpenAI 降级时把整个 base64 序列化成**纯文本**（`case "tool_result"` 分支 `Marshal` + `SetStringContent`；上游修复 PR QuantumNous/new-api#4873 **仍未合并**），BPE 切词 → 单图 ~12 万 token（transcript 铁证：增量 121641 ÷ b64 331092 = 2.72 字符/token）。**运维已在前置 OpenResty 加 LuaJIT 拦截器修复**（复测 465KB 全屏图 nested 路径 1080 vs 顶级 1023，已持平）。⚠️ 这是**网关侧补丁**，换网关即复现；读图仍建议先降采样到长边 ≤1568px。详见记忆 `read-image-token-explosion-gateway-bug`。
- ⚠️ **`Read` 图片不提示文件大小** [V]——先 `ls -l` 看字节数；本机有 PIL 12.3 / numpy 2.2 可降采样/裁剪。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——两集合不相交；**不预设有线/无线**，每次先 `adb devices -l`。见记忆 `adb-device-is-always-mine-users-are-remote`。
- ⚠️ **LogExporter 的 3 行诊断日志的去留未定** [?]（继承）——现保留；建议降为只留"命中 N 个"一行。
- ⚠️ **NPatch 段未在真实 NPatch 环境端到端验证** [?]（继承）——只做了造数据单元级验证。
- ⚠️ **`Logger.gameMediaDir` / `LogExporter.gameMediaDir` 是两份实现** [?]（继承）——路径字面量两处漂移，可选收拢。
- ⚠️ **旧 `Android/data/<pkg>/files/` 下日志为历史残留** [?]（继承）——不清理；用户报"日志没更新"先确认看的是 media 目录。
- ⚠️ **日志迁移未在官版（com.Vince）单测** [?]（继承）——仅共存版实机验证。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?]（继承）——无可滚动正文 `weight` 时平板可能重演窄条按钮。
- ⚠️ **编辑模式重构后仅共存版 + 双踏板模式实机走过** [?]（继承）——SINGLE 模式、换挡控件编辑层、官版包名未单独回归。
- ⚠️ **榜单 tnum 只在 ColorOS 16 平板（OnePlus OPD2413）验过** [?]——未在 Flyme / 官版包名 / 其他 ROM 回归；字体不带 `tnum` 时会静默退化为比例数字（不更差，但也就没修好）。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明**。

## 6. 下一步（有序）
1. （可选）其它 ROM 回归榜单 tnum（Flyme / 官版包名 `com.Vince`）。
2. （可选）决定 LogExporter 3 行诊断日志留几行，改完重跑 `:app:lint` + 装机（继承）。
3. （可选）回归 SINGLE 模式与换挡控件的编辑层（继承，尚未单测）。
4. （可选）官版（com.Vince）跑一遍日志导出，验证两包名对称（继承）。
5. （可选）加固 `EulaDialog`/`UpdateDialog` 正文 `weight`（继承）。
6. （可选）验收权限门控页 / 真实群验证 bot 投递 / ABSdiag·TCdiag 降频（继承）。

## 7. 留给用户的开放问题
- 榜单等宽在 Flyme / 其他 ROM 上要不要一并回归？（继承相关）
- 3 行诊断日志保留几行？（继承）
- 旧 `Android/data/<pkg>/files/` 残留日志要不要主动清理？（继承）
- 那两条锁屏态 SIGSEGV（`located=0`，疑与模块无关，属 Unity 冷启动）是否要单独追？（继承）
