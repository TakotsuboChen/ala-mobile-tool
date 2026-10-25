# HANDOFF — 读全文再开始干活

生成时间: 2026-10-25T20:50:03+08:00 · Git HEAD: `ce6873b`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `ce6873b`（已 push）· 服务端未动（`8a2da78`，10-20）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `ce6873b`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `native/src/lap_hook.c`（踏板占比判定实现）+ 项目 CLAUDE.md 的
  `native/src/lap_hook.c` 条目（完整判据与实测依据）

## 1. 当前目标
无进行中的功能切片。本会话完成：**修复「用游戏原生按键跑圈却上报线性踏板、
白吃 10% 围场积分加成」**（模块 `1.0.4 Beta 3` / `104230`，已装机、双向实机验证）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **根因**：`pedal_mode` 上报的是**设置值**，服务端 `score.rs` 的
  `pedal_linear` 判的却是"这一圈实际用了什么输入"（`matches!(pedal_mode,
  "single"|"dual")` → +10%，注释明写"原生按键（'off'）不算线性踏板"）。
  设置双踏板 + 全程用原生按键 ⇒ 白送 10%。
- [V] **修法**：`pedal_hook.c` 的 `proxy_fixed_update` 在玩家车白名单分支内每
  物理帧调 `lap_note_pedal_frame(g_throttle_active || g_brake_active)`；
  `lap_hook.c` 圈边界快照+归零，圈完成时设置若是 2/3 但活跃帧占比
  < `PEDAL_USAGE_MIN_PCT`(50) 则改写为 1(`off`)，并打 `LAPcfg: pedal usage` 汇总行。
- [V] **阈值 50% 的实测依据**：`D:\Downloads\QQ` 全部 36 份日志、212 个连续圈
  （`wall/lap ∈ [1.0,1.35]` 过滤掉暂停/读盘）→ 0–10% 有 99 圈、90–100% 有 96 圈、
  中间地带仅 17 圈且多为换圈间隙手指没松的假象。**双峰分布 = 两种输入方式互斥**，
  50% 落在最宽空档（40–50% 为 0 圈）。
- [V] **实机双向验证**（魅族 20，Monza，服务端数据库直接查证）：
  - 圈1 `1:21.646`，用户自述「少部分单踏板 + 大部分原生按键」→ 库内 `pedal_mode=off` ✅
  - 圈2 `1:15.527`，`LAPcfg: pedal usage 3632/3781 frames (96%) >= 50% → report 2`
    → 库内 `pedal_mode=single` ✅
  - 对照历史（10-06 全部 4 圈均 `single`，无此修复）——修复前后行为差异确凿。
- [V] **分母正确性自检**：`3781 帧 / 75.527s = 50.06Hz`，与游戏物理帧率吻合
  ⇒ 只统计玩家车一辆（AI 车计入会是 8 倍）。
- [V] **产物三链**（release）：时间戳 `20:41:22` 晚于全部源码改动；
  `aapt2 dump badging` → `versionCode='104230' versionName='1.0.4 Beta 3'`；
  `libala-core.so` 含三条 `LAPcfg: pedal usage` 串 + `lap_note_pedal_frame`
  符号（`GLOBAL FUNC 40 字节`，未被内联）；APK 内 `module.prop` 已同步。
- [V] **构建 + lint**：`assembleRelease -x lintVital*` BUILD SUCCESSFUL (2m16s)；
  `:app:lint` BUILD SUCCESSFUL（67 warnings / 0 errors）。
- [V] **装机**：`adb -s 192.168.50.142:5555 install -r` → Success；
  `dumpsys package` → `versionCode=104230 versionName=1.0.4 Beta 3`。
- [V] **工作区干净**：`git status --short --branch -uall` → `## main...origin/main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 16s
50 actionable tasks: 20 executed, 30 up-to-date

$ ./gradlew :app:lint
BUILD SUCCESSFUL in 1m 38s
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline)

$ aapt2 dump badging app-release.apk
package: name='tools.alamobile.mod' versionCode='104230' versionName='1.0.4 Beta 3'

$ adb -s 192.168.50.142:5555 install -r app-release.apk
Success

$ dumpsys package tools.alamobile.mod | grep version
    versionCode=104230 minSdk=26 targetSdk=35
    versionName=1.0.4 Beta 3
```

## 3. 决策与理由
- **判据来源 = native 实际写入，不是 UI 状态** [V]——用户选「native 跟踪实际
  写入」。否决「Java 读 overlay 是否展开」：控件展开着却用原生按键仍会误报。
- **粒度 = 本圈活跃帧占比，不是"碰过一次就算"** [V]——用户选按占比判定。
  一次性触摸（开 DRS 点刹车）不该把整圈算成线性踏板。
- **阈值 50% 由历史日志实测决定，不由我拍** [V]——用户原话「你看里面以往的
  日志来决定」。212 圈双峰分布证明 10%~50% 之间任意值结果一致，取语义最直白的 50%。
- **采样点选 `proxy_fixed_update` 而非 `proxy_car_controller`** [V]——后者在
  结算/进站/暂停时可能不推进，`FixedUpdate` 恒 50fps 分母才稳定。
- **必须放在 `is_target_player_car` 白名单分支内** [V]——AI 车同样走
  FixedUpdate，在外采样会把分母刷成 8 倍、真实占比被稀释到 1/8 以下。
- **Java 侧不加查询接口** [V]——native 已打完整汇总行（帧数/占比/判定），
  Java 再查一次是死代码。中途加过 `queryLapPedalUsage` 又删掉了。
- **版本号 `1.0.4 Beta 3` / `104230`** [V]——用户指定。
- **顺手修正 `ModConfig.kt` 的过时注释** [V]——原文「踏板按设置取值（用户定案：
  按设置计）」已被本次改动推翻，留着会让下一个人以为代码与注释矛盾。

## 4. 失败的尝试 — 不要再试
- [X] **把"没看到证据"说成"无法确认"** [V]——验证上传时我说「Java 侧零日志，
  无法确认服务端是否收到」，用户：「**无法确认服务端？你傻逼吗**」。
  SSH 主机/端口/密钥路径**逐字写在记忆里**（`paddock-vps-ssh-port-4142`）。
  教训见 memory `check-memory-before-asking` 的 2026-10-25 小节。
- [X] **GNU `objdump` 反汇编 aarch64** [V]——本机 objdump 报
  `can't disassemble for architecture UNKNOWN`，且没有 NDK 的 `llvm-objdump`、
  没有 capstone。验证 native 改动只能靠 `readelf -sW` 查符号 + `strings` 查特征串。
- [X] **`unzip -p app.apk lib/...so | nm -D -`** [V]——管道给 nm 读不了（nm 要文件）。
  先 `unzip -o ... -d tmpdir` 再对文件跑 `readelf`/`strings`。
- [X] **`unzip -p app.apk classes*.dex`** [V]（继承）——zsh 的 glob 在 unzip 内
  不展开，报 `no matches found`。先解压到临时目录再逐个 `grep -a`。
- [X] **用 `strings` 搜 dex 里的中文** [V]（继承）——按 ASCII 可打印字符切分，
  中文字节被丢弃。必须 `grep -a "中文" classes*.dex`。
- [X] **`echo "exit=$?"` 取 grep 退出码** [V]——取到的是上一条 `head` 的码。
  判「零命中」要看有没有输出行，不能看这个码。
- [X] 继承死路（详 `.handoffs/20261025204959-handoff.md` §4 及更早）：
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
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]——手机 `20:34:56` vs 服务器 `20:31:51`
  （`TZ=Asia/Shanghai date` 实测）。数据库 `TimeZone=UTC` 配置本身正确，是宿主
  时钟漂移。影响 `created_at` 排序与依赖时间的业务逻辑；排查时曾被误导一次。
  **未修复**（需 `timedatectl set-ntp true`，属对外部服务器改动，未获授权）。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]——`native_log.c` 的
  `truncate_log_file` 保留后半，实机跑两圈就丢了第一圈的 `LAPcfg` 证据行
  （20:27:54 之前全没了）。诊断圈速问题时窗口太窄，跑完必须立刻拉日志。
- ⚠️ **上传成功但未破纪录时 Java 侧零日志** [V]——`uploadLap` 成功路径只在
  服务端返回 `toast` 时打日志，`parseToast` 返回 null 就全程静默。所以"零日志"
  既可能是"上传成功没破纪录"也可能是"根本没上传"，**必须查服务端库才能区分**。
- ⚠️ **服务端 `pedal_mode` 的历史数据不可信** [V]——本修复之前所有圈报的都是
  设置值，「用原生按键跑出 single/dual」的历史成绩都带虚高加成。是否回溯修正
  未定案。
- ⚠️ **`LAPcfg` 判定行只在配置为 single/dual 时进入修正分支** [V]——配置本来是
  `off` 时打的是 `no correction` 那条，别拿它当"修复没生效"。
- ⚠️ **NPatch 门控有两条判据不同的路径** [V]（继承）——游戏进程判框架+管理器双 850；
  模块进程只判管理器。见 memory `npatch-version-gate-independent-dimension`。
- ⚠️ **产物实证三链** [V]（继承）——见 memory `apk-artifact-verification-chain`。
- ⚠️ **`baksmali` 输出把中文转成 `\uXXXX`** [V]（继承）。
- ⚠️ **native 运行时开关必须单一事实源** [V]（继承）——见 memory
  `runtime-switch-single-source-of-truth`。
- ⚠️ **待决事项必须问；已由明文策略定好的事不许问** [V]（继承）——见 memory
  `ask-before-any-approach`。
- ⚠️ **设备操作按每条命令授权** [V]——见 memory `device-gate-hook-deny-not-ask`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20（Flyme，Android 16，NPatch 850）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 管理器晚于游戏启动时不会自动重连** [?]（继承，843 起仍有边界）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）发版**：`1.0.4 Beta 3` 已构建、装机、双向验证，但**未发布**。
   发布 = 打 tag + push，CI 自动发 Pre-release。⚠️ 发版时机完全由你决定，我不主动提议。
2. **（待你决定）VPS 时钟修复**：`sudo timedatectl set-ntp true` 或起 chrony。
   属对外部服务器改动，需明确许可。
3. **（待你决定）历史 `pedal_mode` 是否回溯**：本修复前的成绩都带虚高的线性踏板加成。
4. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：服务端已有能力（`8a2da78`），
   模块 UI 侧 `grep hideHidden|hide_masked|隐藏已屏蔽` **零命中**，开关未做。
5. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像 + 备份 `.tar.gz`
   + `.env.bak-*`。清理前先确认当前实跑镜像 tag。
6. **（待你决定）大奖赛页的产品形态**：当前是占位 Toast「开发中，敬请期待！」。
7. **（待你决定）NPatch 版本号换算表补全**：`docs/NPATCH_CACHE_CRASH_NOTES.md`
   §14.3 只记录了 847/849/850 的发布说明原文，**未做偏移换算**。

## 7. 留给用户的开放问题
- **50% 阈值是否合适**？实测双峰分布下 10%~50% 结果一致，但未来出现更多混用
  玩家时可能要调。日志里每圈都有 `pedal usage N/M frames (X%)` 可直接标定。
- **历史虚高成绩是否回溯**？（见 §6.3）
- **NPatch 门控要求 850**。846 及更早的用户升级模块后会被拦（零 hook + 循环 Toast）。
  是否要先在群里通知？（继承）
- 圈速卡的「间隔 2 字符」是否合适？（继承，未回答）
- 「全服最快圈数量」显示跨版本累计数，是否需要在界面标注"含历史版本"？（继承）
- 大奖赛页最终要做什么？（继承）
- 排序方式是否需要记忆？（继承）
- **屏蔽后积分可能上涨**（金标成绩顶上会吃加成），界面是否提示？（继承）
