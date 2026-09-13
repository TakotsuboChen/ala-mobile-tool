# HANDOFF — 读全文再开始干活

生成时间: 2026-09-13T13:11:59+08:00 · Git HEAD: `502019f`（模块仓；本文件为后续 handoff 提交，其 parent）
paddock 仓 HEAD: `973329d`（本会话未动）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `502019f`（2026-09-13 13:11，**尚未 push**）
- 漂移检查: `git rev-parse HEAD~1`（模块仓）是否仍 = `502019f`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA；不一致以 git 实际输出为准
- 待重探的 [?]: 见下方标记
- 先读: `docs/PADDOCK_PLAN.md`（契约源）

## 1. 当前目标
**已完成**：修复围场指南弹窗「蓝色按钮只剩窄窄一条」的平板 bug。已提交 + 装机（**用户已口头确认「解决了」**，未做屏幕测量复核）。**现无进行中目标。**

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因已定位并与 miuix 源码对上**（`references/miuix/.../layout/DialogContentLayout.kt:318`）：
  `DialogContentLayout` 给卡片加 `heightIn(max = if (isLargeScreen) windowHeight * (2f/3f) else Unspecified)`。平板（3392x2400 @420dpi即 1292x914dp，宽≥840dp）判为 largeScreen → 2/3 屏 = **610dp 硬上限**。
  内容实际需求：标题 17sp(~33dp) + 12dp + 正文 MaxHeight(0.55×914=**503dp**) + 12dp + 按钮 40dp + insideMargin 24×2 ≈ **648dp** > 610dp。
  `Column` 超限时把所有"无 weight 子项"**按比例压缩**（`Modifier.weight` 文档：无 weight 的子项先被测量、再按比例分配剩余空间；反过来说约束收紧时无 weight 子项一起被压）→ 按钮的 `defaultMinSize(minHeight=40dp)` 被压破。
- [V] **像素实测证据**（修复前截图 `screencap`）：按钮蓝色区域 y=1936..1973 即 **38px ≈ 14.5dp**（Density 2.625）；正常应为 40dp=105px。卡片总高 1600px = 610dp，与 2/3 屏推算完全吻合。
- [V] **修复**（commit `2c14558`）：`PaddockGuideDialog.kt` 给正文 `MarkdownText` 加 `modifier = Modifier.weight(1f, fill = false)` —— 正文成为可让渡空间的一方（超限先收缩并滚动），按钮保持 40dp 最小高；`fill=false` 保证正文短时弹窗仍按内容收拢、不撑满 2/3 屏。
- [V] **装机后 UI 树验证**：`uiautomator dump` 读「我已了解」按钮 bounds = `[1208,1854][2185,1974]` → **高 120px ≈ 45.7dp**（≥ 40dp 最小高 + 圆角外扩），另一条层级 bounds `[1609,1888][1785,1940]`。异常窄条已消失。
- [V] 构建/lint（全新 shell）：`./gradlew :app:lint` → BUILD SUCCESSFUL（0 error，62 warnings 均在 baseline 外/既有）；`:app:assembleRelease -x lintVital*` → BUILD SUCCESSFUL；`:app:assembleDebug` → BUILD SUCCESSFUL。版本号未动（`1.0.4 Alpha 1` / `104100`）。
- [V] 装机：`adb install -r app-release.apk` → Success（设备 `e98a35cb`）。
- [V] 工作区 clean，**3 个本地提交尚未 push**（见 §6）。
- [?] **`MainScreen.kt` 弹窗挂载埋点** `"login gate dialog MOUNTED"` 仍在（诊断用，用户尚未决定留/删）。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:lint → BUILD SUCCESSFUL in 1m 7s（EXIT=0）
./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 24s
./gradlew :app:assembleDebug → BUILD SUCCESSFUL in 12s
adb install -r app-release.apk → Success
uiautomator → 「我已了解」bounds [1208,1854][2185,1974]（120px ≈ 45.7dp）
```

## 3. 决策与理由
- **用 `weight(1f, fill=false)` 而非降低 `maxHeightFraction`** [V]——降 fraction 是治标：治不了"任何嵌入弹窗的内容超 2/3 屏都会被压"的通用问题，且会让正文可视区在正常设备上变小。weight 从"谁让渡空间"这一层解决，对手机/平板同构。
- **不给按钮设 `weight`** [V]——按钮必须保最小高，正文才是可滚动的那一方；给按钮 weight 会让它在内容超限时继续被分走空间。
- **`fill = false` 是必需的** [V]——`weight(1f)` 默认 `fill=true` 会强制正文撑满可用高度，导致正文短时弹窗也被撑到 2/3 屏（观感倒退）。
- 继承：`action_metas()` 单一事实源 / 信箱通道优于修进程通道 / 登出写空 token / AFA 硬性前置 / 4xx 先存后判 / 零 Hook 判定红线 / 版本号红线——详见 `.handoffs/20260913123947-handoff.md` §3。

## 4. 失败的尝试 — 不要再试
- **靠 `screencap` 横向裁图目测按钮尺寸** → 首次裁剪区域（y 2050-2250）裁偏、看空 → 误判按钮"在视口外"。改用**按基色做像素掩码统计**（`(b-r>60)&(b-g>40)`）定位蓝色行/列范围，才拿到真实 38px。**做像素测量时先用颜色掩码定位元素，不要先裁图目测。**
- 继承 [X]：`coroutineScope{}` 内实现多源竞速 / 只加源数不改 scope 结构 / `result::class.simpleName` 记诊断日志 / 声称"已装机"而未实际执行 `adb install` / `***text***` 粗斜体 / `MarkdownText` 表格 / alpine 跑本地 glibc ELF / psql `-v` 传大 JSON / 旧快照覆盖 configs 表 / `LocalBringIntoViewSpec` / `evaluateLoginGate` 初始 true early return / Remote Preferences `remove()` 清 token / scheduleDraw 治 LTPO / 单变量弹窗挂载 / 磁盘缓存原图字节——详见 `.handoffs/20260913123947-handoff.md` §4。

## 5. 已知坑
- ⚠️ **同一类坑仍在 `EulaDialog` / `UpdateDialog` 潜伏** [?]——两者同样把无 weight 的可滚动正文（`EulaDialog` 用固定 `.height(360.dp)`、`UpdateDialog` 用 `MarkdownText`）与按钮同列放在 `OverlayDialog` content 里。手机屏（<840dp 宽）不触发 largeScreen，故未暴露；**平板上若正文撑满 360dp、按钮仍可能被压**。本次未改（超出用户指令范围），若用户报同类问题照本方修。
- ⚠️ **权限门视觉未实机验收** [?](继承)——需临时关 AFA 再开模块 App 看观感。
- ⚠️ **配置热更新仍走广播** [?](继承)——ColorOS 关关联启动 + 游戏不在前台时广播可能丢（信箱覆盖冷启动）。用户已说"暂时保持现状"。
- ⚠️ **信箱残留探针文件** [?](继承)——`/sdcard/Android/media/<游戏包>/` 下 `ala_probe_*` 残留，可手动删，无功能影响。
- ⚠️ **永久 4xx 先提示后静默丢弃** [?](继承,待用户确认)——`isRetryableStatus` 丢弃判据导致约 30s 的"善意的假话"。
- ⚠️ **bot 投递未端到端验证** [?](继承)——本地模拟仅验证到"回复正文正确"，真实群投递需在群里发指令确认。
- ⚠️ **围场指南 1.5× 行高下弹窗可视区变少** [?](继承)——现在正文带 weight 会优先让渡空间，可视区进一步变小；用户如觉得仍挤/读不完可调 `maxHeightFraction` 或行高。
- ⚠️ **OkHttp 败者协程不可中断** [?](继承)——竞速败者靠自身超时（8s/12s）消亡，当前只在启动门控调一次。
- ⚠️ **对话纪律** [V](继承)——1 mid-turn 消息逐条消化 2 旧快照不当现在时断言 3 装机前先验设备上 APK 版本 4 调查日志前先对齐"几次"计数 5 修 UI 时序问题先拉触摸时间线。

## 6. 下一步（有序）
1. **push 三个本地提交到 origin/main**（`2c14558` UI 修复 → `502019f` CLAUDE.md → 本次 handoff 提交）；未经用户确认不要 push。
2. （待用户决定）`MainScreen.kt` 的「login gate dialog MOUNTED」埋点保留还是删。
3. （可选）同法加固 `EulaDialog`/`UpdateDialog` 的正文 weight（见 §5）。
4. （可选）验收权限门控页观感 / 真实群验证 bot 投递 / 清理信箱探针残留 / ABSdiag·TCdiag 降频 / 闪退 crash 现场定案。

## 7. 留给用户的开放问题
- 登录门控埋点去留（见下一步 2）
- 是否要顺手给 EulaDialog / UpdateDialog 加同样的 weight 加固
- 围场指南弹窗可视区（0.55 屏 / 1.5× 行高，现叠加 weight 让渡）是否需再调
- 信箱通道是否给游戏侧加"配置热更新"路径（现走广播）？用户已说"暂时保持现状"
- 永久 4xx 处理：先提示后静默丢弃（现状）vs 无限重试
