# HANDOFF — 读全文再开始干活

生成时间: 2026-09-15T02:23:56+08:00 · Git HEAD: `5e9e9ca`（本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `5e9e9ca`（2026-09-15 02:23）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `5e9e9ca`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `app/src/main/kotlin/tools/alamobile/mod/overlay/OverlayManager.kt`（`syncEditMode` / `applyOverlayVisibility`）与 `OverlayEditView.kt`；改编辑模式前**先读 CLAUDE.md 的「编辑模式非显然约定」七条**

## 1. 当前目标
**已完成**：编辑模式（长按工具图标出入）的视觉与交互重构——整屏 0.3s 渐暗 75%、四角完整圆、屏幕居中三行提示、双踏板「油门/刹车」标识、退出还原、屏蔽单击。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **改动已提交推送**：`8664707`（工作切片：5 文件）、`5e9e9ca`（CLAUDE.md + README）。三次 push 均成功。
- [V] **构建/lint**：`:app:compileDebugKotlin` BUILD SUCCESSFUL；`:app:assembleRelease -x lintVital*`（环境崩溃规避）BUILD SUCCESSFUL；`:app:lint` BUILD SUCCESSFUL，58 warnings / 4 hints（改动文件无新增告警）。
- [V] **已装机验证**：`adb install -r app/build/outputs/apk/release/app-release.apk` → `Success`，设备 MEIZU 20（381QYFCN22B9A）。本仓版本号**未改动**（用户只授权改交互）。
- [V] **用户实机确认**（本会话末"ok，完美"）。
- [V] 工作区 clean。
- 本会话实测**删除**了 `syncEditMode` 的两条排查日志与 `describeChildren()`（用户确认效果后收尾）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint                → BUILD SUCCESSFUL in 1m 35s（58 warnings, 4 hints）
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
                                     → BUILD SUCCESSFUL in 1m 45s
$ adb install -r app/build/outputs/apk/release/app-release.apk → Success
```

## 3. 决策与理由
- **编辑层铺满整屏，被编辑控件矩形改为内部 `RectF`** [V]——根因：旧编辑层布局尺寸 = 控件尺寸，四角圆点圆心落在 view 自身布局边界上，圆面越界被**自身**裁剪路径切成 1/4（与父容器 `clipChildren` 无关，所以上轮设 `clipChildren=false` 只修好了其中两个角）。铺满整屏后所有绘制落在自身边界内，任何层级裁剪都无从下手。否决：逐层 `clipChildren=false`——父链每层都要放行，实测不对。
- **触摸用范围闸而非全接管** [V]——铺满整屏若一律接住事件，工具按钮/其他控件/游戏画面全失灵。`ACTION_DOWN` 出控件矩形（含角命中圈）即 `return false` 放行。
- **可见性收敛到 `applyOverlayVisibility()`**（`VISIBLE = editMode \|\| overlaysVisible`）[V]——根因：编辑模式**不修改** `overlaysVisible`，而旧代码在三条路径各自按它判定，产生两个反向 bug（退出后控件还留着 / 编辑中改参数控件消失）。单一函数根治。
- **退出编辑模式不重建控件** [V]——旧实现进出**两个方向**都 `removeGamingOverlays()+addGamingOverlays()`：退出时淡出动画作用在"刚出生就 GONE"的新层上 = 瞬间恢复无渐亮（进场的淡入恰好能播）。退出只翻转 `editMode` 让现有图层播 1→0。
- **变暗层 z 序 = 紧贴游戏内容之后，不是 index 0** [V]——Unity 画面走 SurfaceView，被合成器提到**窗口内所有普通 View 之上**；index 0 的层实际在 SurfaceView 下面，等于没盖（用户实测"屏幕没变暗"）。
- **编辑模式屏蔽工具图标单击** [V]——单击是展开/折叠（会重建控件 + 退出编辑），与编辑状态打架。
- **「油门/刹车」标识画在 `saveLayer` 之外** [V]——踏板用 `canvas.saveLayer(...)` 的层 alpha 承担透明度设置，`restoreToCount` 之前绘制的任何内容都跟随它；移到之后才能恒不透明。
- **长按恢复默认 0.5s → 3s，提示秒数由同一常量推导** [V]——用户指定。
- **未升版** [V]——用户指令只授权改交互，版本号属用户决策域（全局红线）。

## 4. 失败的尝试 — 不要再试
- **[X] 让编辑层尺寸=控件尺寸 + 父容器 `clipChildren=false` 修四角 1/4** [V]——裁剪不止一层，父链每层都要放行；改单点只会让**其中几个**角变完整（实测：左上完整、右上切半、左下切半、右下仍 1/4）。正解见 §3 第一条。
- **[X] 变暗层 `addView(dim, 0)`（插到最底层）** [V]——SurfaceView 被合成器提到窗口 View 之上，盖不住。见 §3。
- **[X] 用 `withEndAction` 做淡出收尾** [V]——用户实测退出瞬间恢复、收尾未执行；改 `Handler.postDelayed` 显式收尾。
- **[X] 把提示文字画在编辑框 `onDraw` 里（依赖父容器不裁剪画到框外）** [V]——脆弱且与角的遮挡问题纠缠，改为独立全屏层（`EditHintView`）。
- **[X] `Paint.setShadowLayer` 给提示文字加可读性阴影** [V]（继承）——只在软件渲染画布生效，硬件加速画布静默忽略；改用描边双绘。
- **[X] `result::class.simpleName` 记日志** [V]（继承）——release R8 混淆成 `qm2` 类乱码，用稳定 tag 字面量。
- 继承死路 [X]（详 `.handoffs/20260915022356-handoff.md` §4 及更早归档）：认为游戏进程启动~15s 崩溃是模块引入 / 锁屏态 monkey 拉起游戏 / 复用 `Logger.gameMediaDir` / 踏板"版本号早退豁免"·"单向补送"·"MOVE 间隔判据" / `coroutineScope{}` 内多源竞速 / 声称装机未执行 `adb install` / `***text***` 粗斜体 / MarkdownText 表格 / alpine 跑 glibc ELF / psql `-v` 传大 JSON / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / `scheduleDraw` 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节。

## 5. 已知坑
- ⚠️ **`Logger.gameMediaDir` / `LogExporter.gameMediaDir` 是两份实现** [?](继承)——路径字面量两处漂移，可选收拢（`LogExporter.kt:56-57` 自己 private 重算）。
- ⚠️ **旧 `Android/data/<pkg>/files/` 下日志为历史残留** [?](继承)——迁移后不清理；用户报"日志没更新"先确认看的是 media 目录。
- ⚠️ **日志迁移未在官版（com.Vince）单测** [?](继承)——仅共存版实机验证。
- ⚠️ **同类弹窗坑仍在 `EulaDialog`/`UpdateDialog` 潜伏** [?](继承)——无可滚动正文 `weight` 时平板可能重演窄条按钮（修法见 CLAUDE.md 弹窗排版铁律）。
- ⚠️ **`clearAuth` 的 `no service bound, daemon stores NOT cleared` 是设计内 digest** [V](继承)。
- ⚠️ **编辑模式重构后仅共存版 + 双踏板模式实机走过** [?]——SINGLE 模式、换挡控件编辑层、官版包名未单独回归。
- ⚠️ **对话纪律** [V](继承)——1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感 7 **别在用户锁屏时操作设备**。

## 6. 下一步（有序）
1. （可选）回归 SINGLE 模式与换挡控件的编辑层（本会话未单测）。
2. （可选）官版（com.Vince）跑一遍日志导出，验证两包名对称。
3. （可选）加固 `EulaDialog`/`UpdateDialog` 正文 `weight`。
4. （可选）验收权限门控页 / 真实群验证 bot 投递 / ABSdiag·TCdiag 降频。

## 7. 留给用户的开放问题
- 旧 `Android/data/<pkg>/files/` 残留日志要不要主动清理？
- 那两条锁屏态 SIGSEGV（`located=0`，libil2cpp 空指针）是否要单独追（疑与模块无关，属 Unity 冷启动）？
