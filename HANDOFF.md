# HANDOFF — 读全文再开始干活

生成时间: 2026-10-25T21:50:00+08:00 · Git HEAD: `544ded5`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `544ded5`（已 push）· 服务端未动（`8a2da78`，10-20）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `544ded5`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/configure/ConfigurePagerMiuix.kt`
  的 `ConfigureNativeFeaturesScreen`（两个警示弹窗都在这里）

## 1. 当前目标
无进行中的功能切片。本会话完成：**「解除最大转向角度限制」从关切到开时弹驾驶
特性警示**（文案用户逐字给定，已装机、用户验收「没问题」）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **功能已落地**：`ConfigureNativeFeaturesScreen` 里 `showSteerUnlockWarnDialog`
  state + 拦截式 `onCheckedChange`（`if (on && !uiState.enableUnlockSteerAngle)`）
  + 页面末尾常驻组合树的 `OverlayDialog`。
- [V] **语义 = 立即开启 + 仅告知**（用户 2026-10-25 三问定案）：开关先落库生效，
  弹窗只有单个蓝色「我已了解」，**没有「取消」**——取消会与"已生效"自相矛盾。
- [V] **正文渲染 = MarkdownText**（用户选），含一处 `**粗体**`
  「并非是因为修改了物理特性导致的」。四段之间留空行（解析器把连续非空行合并成一段）。
- [V] **阅读门控 = 要**（用户选）：`derivedStateOf { maxValue == 0 || value >= maxValue }`，
  按钮 `请先阅读说明` → `我已了解`，与 `PaddockGuideDialog` 同模式。
- [V] **构建**：`assembleRelease -x lintVital*` → **exit=0**，BUILD SUCCESSFUL (1m46s)。
- [V] **lint**：`:app:lint` → **exit=0**，67 warnings / 4 hints / 0 errors（baseline 过滤 2e+14w）。
- [V] **产物三链**：APK 时间戳 `21:43:58` 晚于源码 `21:39:52`；
  `aapt2 dump badging` → `104230` / `1.0.4 Beta 3`（**未改版本号**）；
  dex `grep -a` 命中 5 条特征串（标题 / `请先阅读说明` / 粗体句 / 正文段落 /
  `高速弯"轨道车"`）各 1 处；标题字节 `e2 9a a0 ef b8 8f` = U+26A0 + U+FE0F ✅。
- [V] **装机**：`adb -s 192.168.50.142:5555 install -r` → Success；
  `lastUpdateTime=2026-10-25 21:44:42`（版本号没变，时间戳才是装机凭证）。
- [V] **用户验收**：「没问题」（`/handoff 没问题`）。
- [V] **工作区干净**：`git status --short --branch -uall` → `## main...origin/main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
exit=0
BUILD SUCCESSFUL in 1m 46s
50 actionable tasks: 12 executed, 38 up-to-date

$ ./gradlew :app:lint
exit=0
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline lint-baseline.xml)
BUILD SUCCESSFUL in 2m 16s
```

## 3. 决策与理由
- **「立即开启 + 仅告知」而非「确认后才开启」** [V]——用户在 AskUserQuestion 三选
  里明确选了前者。与 ABS 警示同模式（单按钮），否决路肩振感那种「取消/确认」双按钮。
- **MarkdownText 而非裸 Text 分段** [V]——用户选的；粗体那句是整条提示的落点。
- **要阅读门控** [V]——用户选的，与围场指南同模式。
- **开关回调用拦截式 lambda 而非方法引用** [V]——需要同时做「判断方向」和「落库」，
  `actions::setEnableUnlockSteerAngle` 无法插入弹窗触发。
- **`!uiState.enableUnlockSteerAngle` 守卫** [V]——已开状态下重复回调不重复弹。
- **文案常量放顶层 `private const val`** [V]——放 composable 内会每次重组重建字符串。

## 4. 失败的尝试 — 不要再试
- [X] **`xxd` 查十六进制** [V]——本机没装（`command not found`）。用 `od -An -tx1` 替代，
  验证 U+FE0F 时走 `grep -a -o $'⚠️…' | od -An -tx1`。
- [X] **把"没看到证据"说成"无法确认"** [V]（继承）——SSH 主机/端口/密钥逐字在记忆
  `paddock-vps-ssh-port-4142` 里。见 memory `check-memory-before-asking`。
- [X] **GNU `objdump` 反汇编 aarch64** [V]（继承）——报 `architecture UNKNOWN`，
  无 NDK `llvm-objdump`、无 capstone。只能 `readelf -sW` + `strings`。
- [X] **`unzip -p app.apk lib/...so | nm -D -`** [V]（继承）——nm 要文件不要管道。
- [X] **`unzip -p app.apk classes*.dex`** [V]（继承）——zsh glob 在 unzip 内不展开。
- [X] **用 `strings` 搜 dex 里的中文** [V]（继承）——按 ASCII 切分丢弃中文字节，
  必须 `grep -a "中文"`。
- [X] **`echo "exit=$?"` 取 grep 退出码** [V]（继承）——取到的是上一条 `head` 的码。
- [X] 继承死路（详 `.handoffs/20261025214805-handoff.md` §4 及更早）：
  `assets/npatch/` 条目数当指纹 / `grep "0x34e"` 前缀匹配假阳性 / 后台
  `cmd | tail` 缓冲 / 门控用 `lspConfig.VERSION_CODE` / 模块进程复用
  `evaluateNPatchGate` / NPatch 状态塞进游戏包胶囊文案 / 用 hook 强制拦截代替
  "想起来问" / `PickVisualMedia` / `createChooser` 单目标直通 /
  `EXTRA_INITIAL_INTENTS` / `ResolveInfo.loadLabel()` / `color = Color(...)`
  做金色流光 / `loadingMore` 写正常路径末尾 / 0/1/2 固定分栏 / VPS 上 `sed`
  改 compose / 两个 BlurredBar 共用 backdrop / 相信 `UP-TO-DATE` / `>=` 放行
  未知 Fix 号 / 抓地力加地板 / hook `HybridComponent.EnableOTK` / hook
  `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号 / `g_*_active || g_config.enable_*` 双判据 /
  `baksmali` 搜中文（`\uXXXX` 转义假阴性）/ `Defaults` 是 private object。

## 5. 已知坑
- ⚠️ **`MarkdownText` 把连续非空行合并成一段** [V]——手写多段正文必须留空行，
  否则整篇糊成一坨。已固化进项目 CLAUDE.md 的 `ui/MarkdownText.kt` 条目。
- ⚠️ **阅读门控判据必须含 `maxValue == 0`** [V]——只判 `value >= maxValue` 时，
  文本恰好比视口多一行会让按钮从"可点"突然变"不可点"。同条目。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——`TZ=Asia/Shanghai date` 实测。
  影响 `created_at` 排序；**未修复**（需 `timedatectl set-ntp true`，未获授权）。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——实机跑两圈就丢第一圈的
  `LAPcfg` 证据行，诊断圈速问题时窗口太窄，跑完必须立刻拉日志。
- ⚠️ **上传成功但未破纪录时 Java 侧零日志** [V]（继承）——"零日志"既可能是
  "上传成功没破纪录"也可能是"根本没上传"，**必须查服务端库才能区分**。
- ⚠️ **服务端 `pedal_mode` 的历史数据不可信** [V]（继承）——本修复之前所有圈报的
  都是设置值，「用原生按键跑出 single/dual」的历史成绩都带虚高加成。是否回溯未定案。
- ⚠️ **`LAPcfg` 判定行只在配置为 single/dual 时进入修正分支** [V]（继承）——
  配置本来是 `off` 时打的是 `no correction` 那条。
- ⚠️ **NPatch 门控有两条判据不同的路径** [V]（继承）——游戏进程判框架+管理器双 850；
  模块进程只判管理器。见 memory `npatch-version-gate-independent-dimension`。
- ⚠️ **产物实证三链** [V]（继承）——见 memory `apk-artifact-verification-chain`。
- ⚠️ **native 运行时开关必须单一事实源** [V]（继承）——见 memory
  `runtime-switch-single-source-of-truth`。
- ⚠️ **待决事项必须问；已由明文策略定好的事不许问** [V]（继承）——见 memory
  `ask-before-any-approach`。
- ⚠️ **设备操作按每条命令授权** [V]（继承）——见 memory `device-gate-hook-deny-not-ask`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20（Flyme，Android 16，NPatch 850）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）发版**：`1.0.4 Beta 3` 已含「踏板上报降级」+ 本次「转向限制警示」，
   构建、装机、双向验证均已完成，但**未发布**（无 `v1.0.4*` tag）。发布 = 打 tag + push，
   CI 自动发 Pre-release。⚠️ 发版时机完全由你决定，我不主动提议。
2. **（待你决定）VPS 时钟修复**：`sudo timedatectl set-ntp true` 或起 chrony。
   属对外部服务器改动，需明确许可。
3. **（待你决定）历史 `pedal_mode` 是否回溯**：本修复前的成绩都带虚高的线性踏板加成。
4. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：服务端已有能力（`8a2da78`），
   模块 UI 侧 `grep hideHidden|hide_masked|隐藏已屏蔽` **零命中**（本次已重探 [V]）。
5. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像 + 备份 `.tar.gz`
   + `.env.bak-*`。清理前先确认当前实跑镜像 tag。
6. **（待你决定）大奖赛页的产品形态**：当前是占位 Toast「开发中，敬请期待！」。
7. **（待你决定）NPatch 版本号换算表补全**：`docs/NPATCH_CACHE_CRASH_NOTES.md`
   §14.3 只记录了 847/849/850 的发布说明原文，**未做偏移换算**。

## 7. 留给用户的开放问题
- **50% 踏板占比阈值是否合适**？实测双峰分布下 10%~50% 结果一致，但未来出现更多
  混用玩家时可能要调。日志里每圈都有 `pedal usage N/M frames (X%)` 可直接标定。
- **历史虚高成绩是否回溯**？（见 §6.3）
- **NPatch 门控要求 850**。846 及更早的用户升级模块后会被拦（零 hook + 循环 Toast）。
  是否要先在群里通知？（继承）
- 圈速卡的「间隔 2 字符」是否合适？（继承，未回答）
- 「全服最快圈数量」显示跨版本累计数，是否需要在界面标注"含历史版本"？（继承）
- 大奖赛页最终要做什么？（继承）
- 排序方式是否需要记忆？（继承）
- **屏蔽后积分可能上涨**（金标成绩顶上会吃加成），界面是否提示？（继承）
