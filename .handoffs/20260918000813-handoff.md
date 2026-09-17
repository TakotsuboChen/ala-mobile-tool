# HANDOFF — 读全文再开始干活

生成时间: 2026-09-17T21:13:18+08:00 · Git HEAD: `c520d10`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `c520d10`（2026-09-17 21:13）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `c520d10`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: CLAUDE.md「编辑模式（长按工具图标出入）的几条非显然约定」条目（本会话已更新为 ①-⑩）+ `app/src/main/kotlin/tools/alamobile/mod/overlay/OverlayManager.kt`

## 1. 当前目标
**已完成**：编辑模式三连修（退出后踏板残留可拖拽 / 进场变暗闪黑 / 编辑中切踏板模式退出后黑屏常驻）+ 长按重置改语义（长按空白处 2s 恢复当前显示单/双踏板，替代长按控件 3s）+ 编辑模式游戏全屏禁触。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作已提交推送**：`55f1bae`（fix: 编辑模式三修）、`c520d10`（docs: CLAUDE.md+README 同步）。两次 push 均成功。
- [V] **门槛**：`:app:lint` exit=0；`:app:assembleRelease -x lintVital*` exit=0。日志红线未新增违规（改动全在 overlay，日志走 Logger）。
- [V] **已装机**：`adb install -r .../app-release.apk` → `Success`，设备 OnePlus `OPD2413`（serial `e98a35cb`）。版本号**未改动**（1.0.4 Alpha 1 / 104100）。
- [V] **用户实机验收通过**（`/handoff ok`）——三修 + 长按空白 2s + 全屏禁触均过。
- [V] 工作区 clean（除本 HANDOFF.md 与归档文件）。
- [V] Bug1 根因有日志实锤链：dim 层孤儿化 = removeGamingOverlays 清了 dimView/hintView 引用但没摘 view → rebuild 时 ensureXxx 新建第二份层，旧层 alpha=1 永久盖屏（退出后无任何淡出日志佐证）。

### 测试/build 输出（本次交接 run 的真实输出，含退出码）
```
$ ./gradlew :app:lint        → exit=0, BUILD SUCCESSFUL
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
                             → exit=0, BUILD SUCCESSFUL
$ adb install -r .../app-release.apk → Success
```

## 3. 决策与理由
- **退场收尾守卫语义状态而非动画终值** [V]——`animateFade` 的 `alpha == 0f` 判据删掉（动画末帧走 Choreographer，与同时到期的 postDelayed 差 0~1 帧，alpha≈0.03 恒不为 0 → 永不置 GONE → 编辑层 alpha≈0 常驻吃触摸 = Bug1"退出后仍可拖拽"）。`!editMode` 守卫已覆盖"退出中途重进"。
- **dim/hint 层"不摘除 + 不清引用"成对** [V]——rebuild 路径清引用会让 ensureXxx 新建第二份层（孤儿 view 盖屏 = Bug3 黑屏）；防抖（编辑中改参数不重播渐暗）靠复用旧层（alpha 已是 1，1→1 空动画）。编辑层三 tag 仍摘除并清引用（真死 view）。
- **dim/hint 新建层初值恒 `alpha = 0f`** [V]——旧 `if (editMode) 1f else 0f` 在进场路径恒命中 1f 分支（toggleEditMode 先置位再走到这）→ 1→1 空动画 = Bug2 进场闪黑。
- **编辑模式触摸三路分流** [V]——本层矩形→拖拽；空白→消费（全屏禁触实现点）；兄弟矩形/工具按钮→return false 穿透（工具按钮穿透 = 退出编辑模式唯一出口，被吃掉用户锁死）。空白判定 `isBlankArea` 是**聚合式**（遍历全部编辑层矩形 + 工具按钮）——触摸只派给 z 序最高层，单层自判会把兄弟矩形当空白吃掉。
- **长按重置语义改为"长按空白处 2s 恢复当前显示踏板"** [V]——用户要求；重置范围 = 踏板两套（油门+刹车编辑层都 reset），换挡控件不在范围（与文案一致）；走 `resetToDefault` → 既有 `updateTarget → onChanged` 落盘链路。文案引用 `LONG_PRESS_RESET_MS` 常量联动，不再硬编码。
- **长按取消阈值 = 3×touchSlop 欧氏距离** [V]——touchSlop（≈8dp）是"拖拽开始"语义，手指静止生理抖动逐帧累积必超 1×slop → "经常触发不了长按"（用户实测，猜测正确）。3×slop（≈24dp）放行全部抖动、保留取消能力。控件内拖拽升级仍用 1×slop，未动。

## 4. 失败的尝试 — 不要再试
- **[X] `animateFade` 收尾加 `view.alpha == 0f` 判据** [V]——动画末帧与 postDelayed 竞序，alpha 恒差最后一点，判据永不成立。守卫语义状态（`!editMode`）而非视觉终值。
- **[X] dim/hint 层初值写 `alpha = if (editMode) 1f else 0f`** [V]——进场路径 editMode 已先置位，条件恒真 → 新层初值 1 + 1→1 空动画 = 闪黑。"防重播"不靠初值靠复用。
- **[X] `removeGamingOverlays` 清 dimView/hintView 引用** [V]——view 还挂在树上，清引用 = 孤儿 view + 丢失唯一引用，退出后黑屏常驻（日志实锤：rebuildFromConfigChange ×8 连发后退出无淡出日志）。"清引用"必须只对真正被摘的 view 做。
- 继承死路 [X]（详 `.handoffs/20260917213000-handoff.md` §4 及更早归档）：编辑层尺寸=控件尺寸+clipChildren 修四角 / 变暗层插 index 0 / `withEndAction` 做淡出收尾 / 提示文字画在编辑框 onDraw / `setShadowLayer` 加阴影 / `result::class.simpleName` 记日志 / 锁屏态 monkey 拉起 / 复用 `Logger.gameMediaDir` / 踏板"单向补送"·"MOVE 间隔判据" / `coroutineScope{}` 内多源竞速 / `***text***` 粗斜体 / MarkdownText 表格 / Remote Preferences `remove()` 清 token / `scheduleDraw` 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节 / 全局挂 `tnum` 到主题 textStyles / `FontFamily.Monospace` 做等宽 / miuix `Text` 转发 `fontFeatureSettings` / `hasFontAttributes()` 早退解释失效 / on-device Typeface 探针 / `tail` 截断 gradle 输出验门槛。

## 5. 已知坑
- ⚠️ **读图会触发网关层 token 爆炸** [V]（继承）——网关侧 OpenResty LuaJIT 拦截器已修（换网关即复现）；读图先降采样到长边 ≤1568px。详见记忆 `read-image-token-explosion-gateway-bug`。
- ⚠️ **adb 设备必是本机、用户口中的"用户"必是远端** [V]（继承）——不预设有线/无线，每次先 `adb devices -l`。
- ⚠️ **LogExporter 的 3 行诊断日志的去留未定** [?]（继承）——现保留；建议降为只留"命中 N 个"一行。
- ⚠️ **NPatch 段未在真实 NPatch 环境端到端验证** [?]（继承）——只做了造数据单元级验证。
- ⚠️ **`Logger.gameMediaDir` / `LogExporter.gameMediaDir` 是两份实现** [?]（继承）——路径字面量两处漂移，可选收拢。
- ⚠️ **旧 `Android/data/<pkg>/files/` 下日志为历史残留** [?]（继承）——不清理；用户报"日志没更新"先确认看的是 media 目录。
- ⚠️ **日志迁移未在官版（com.Vince）单测** [?]（继承）——仅共存版实机验证。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?]（继承）——无可滚动正文 `weight` 时平板可能重演窄条按钮。
- ⚠️ **编辑模式本会话改动仅共存版 + SINGLE 模式实机验证** [?]——DUAL 双编辑层交互（聚合空白判定跨层让路）、换挡控件编辑层、官版包名未单独回归；3×slop 取消阈值也只在 OPD2413 单设备验过。
- ⚠️ **榜单 tnum 只在 ColorOS 16 平板验过** [?]（继承）——未在 Flyme / 官版包名 / 其他 ROM 回归。
- ⚠️ **对话纪律** [V]（继承）——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明**。

## 6. 下一步（有序）
1. （可选）DUAL 模式回归编辑模式（聚合空白判定、双编辑层穿透、跨层拖拽互不干扰）。
2. （可选）换挡控件编辑层回归（空白长按不重置换挡——设计如此，验证用户是否接受）。
3. （可选）官版（com.Vince）回归编辑模式 + 日志导出两包名对称（继承）。
4. （可选）决定 LogExporter 3 行诊断日志留几行，改完重跑 `:app:lint` + 装机（继承）。
5. （可选）其它 ROM 回归榜单 tnum（Flyme 等，继承）。
6. （可选）加固 `EulaDialog`/`UpdateDialog` 正文 `weight`（继承）。

## 7. 留给用户的开放问题
- 3×slop 取消阈值在别的设备/ROM 上手感如何？要不要做成配置项？（继承相关：长按手感类参数）
- 3 行诊断日志保留几行？（继承）
- 旧 `Android/data/<pkg>/files/` 残留日志要不要主动清理？（继承）
- 那两条锁屏态 SIGSEGV（`located=0`，疑与模块无关，属 Unity 冷启动）是否要单独追？（继承）
