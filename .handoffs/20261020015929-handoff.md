# HANDOFF — 读全文再开始干活

生成时间: 2026-10-20T00:38:54+08:00 · Git HEAD: `7b2a71b`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `7076b7e`（已 push）· 服务端 `main` @ `e878790`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `7076b7e`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/AvatarScreen.kt`
  （文件头 + `ChooserImageContract` / `collectImagePickers` 的注释写满了「为什么」）

## 1. 当前目标
无进行中的功能切片。本会话完成头像选图改造：脱离 GMS 依赖，改为自绘应用选择器。

## 2. 已验证状态 — 工作实际停在哪
- [V] **选图不再依赖谷歌服务**：`queryIntentActivities` 汇总 `GET_CONTENT` + `OPEN_DOCUMENT`
  候选（按包名去重）→ 自绘 miuix `OverlayDialog` 列表 → `setComponent` 定向启动。
- [V] **名称/图标显示为应用名**：取 `ApplicationInfo.loadLabel/loadIcon`，
  不是 `ResolveInfo.loadLabel()`（后者返回活动名「媒体选择工具」「选择」）。
- [V] **manifest 补 `<queries>`**：两个 intent 声明（GET_CONTENT / OPEN_DOCUMENT + `image/*`）。
- [V] **已装机验收**：魅族 20（`192.168.50.142:5555`），APK 00:35:59，装机 00:35:59，
  用户确认「好了，完美」。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`。
- [V] **工作已提交并 push**：`e580abd`（fix）+ `7b2a71b`（docs）→ `b37f28f..7076b7e main -> main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint                           → BUILD SUCCESSFUL in 2m 21s（0 errors，67 warnings）
$ ./gradlew :app:assembleRelease -x lintVital*  → BUILD SUCCESSFUL in 2m 48s，APK 00:35:59
产物验证：
  dex 含「图片选择候选」日志串 + 「选择图片」标题
  mapping.txt 中 collectImagePickers / ChooserImageContract 保留（42 处）
  aapt2 dump xmltree → <queries> 含两个 <intent> 声明
装机：adb -s 192.168.50.142:5555 install -r → Success，lastUpdateTime=00:35:59
```

## 3. 决策与理由
- **自绘选择器而非系统 chooser** [V]——三次实机证伪后定案：系统 chooser 是否画列表、
  是否尊重 `EXTRA_INITIAL_INTENTS` 全由 OEM 决定，应用侧无法保证。自绘则完全可控。
- **同时查 `GET_CONTENT` 和 `OPEN_DOCUMENT` 两个 action** [V]——相册类 App 通常只注册
  `GET_CONTENT`，文件管理器通常只注册 `OPEN_DOCUMENT`。只查一个会漏掉一整类。
- **按 packageName 去重、优先 GET_CONTENT** [V]——同一 App 可能两个 action 都注册，
  不去重会出现两行同名条目。
- **label 取 `ApplicationInfo`** [V]——`ResolveInfo.loadLabel()` 返回最具体的 activity label，
  用户认不出。见 memory `android-file-picker-no-gms`。
- **`toBitmapCompat()` 手写而非引 core-ktx** [V]——项目未引该依赖，为一个转换函数加依赖不划算。

## 4. 失败的尝试 — 不要再试
- [X] **`ActivityResultContracts.PickVisualMedia`** [V]——API < 33 回落到 Google 系统照片
  选择器组件（`ACTION_SYSTEM_FALLBACK_PICK_IMAGES`），无 GMS 设备 `launch()` 抛
  `ActivityNotFoundException`。这是用户最初报的「需要谷歌服务」。
- [X] **`createChooser(裸 GET_CONTENT)`** [V]——ChooserActivity 的**单目标直通**：候选只有
  1 个（谷歌相册）时不画列表直接启动，用户看到「又变成直接弹出谷歌相册」。
- [X] **基底换 `OPEN_DOCUMENT` + `EXTRA_INITIAL_INTENTS` 补候选** [V]——Flyme 定制
  ChooserActivity **忽略 `EXTRA_INITIAL_INTENTS`**，仍单目标直通到文件管理器。
- [X] **`ResolveInfo.loadLabel()` 做行标题** [V]——返回活动名（「媒体选择工具」「选择」），
  不是应用名。
- [X] **Kotlin 块注释里写 `image/*`** [V]——Kotlin 块注释**支持嵌套**（Java 不支持），
  `/*` 会开启嵌套注释，导致文件末尾「Unclosed comment」编译失败。改用 `image/x`。
- [X] 继承死路（详 `.handoffs/20261020003854-handoff.md` §4 及更早）：
  `color = Color(...)` 做金色流光 / `loadingMore` 写正常路径末尾 / 0/1/2 固定分栏 /
  VPS 上 `sed` 改 compose / `grep "踏板模式："` 搜 dex / 两个 BlurredBar 共用 backdrop 会冲突 /
  `listState.scrollToItem(0)` 放请求前 / `unzip -p | strings | grep TextOverflow` /
  相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 / 抓地力加地板 / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号。

## 5. 已知坑
- ⚠️ **`/handoff` 的 push 无需额外授权，且必须做完** [V]——2026-10-20 上一轮 handoff
  把三个提交全留在本地，用户当场质问。skill 步骤 3/5/7 各写了 `git push`，
  全局 Git 策略也把 `/handoff` 列为例外。**自检判据**：报告里出现「待 push」= 违规。
  已在 skill 顶部加显式断言 + 更新全局记忆 `git-commit-policy`。
- ⚠️ **选图/选文件必须自绘选择器** [V]——见 memory `android-file-picker-no-gms`。
- ⚠️ **label 层级** [V]——`ApplicationInfo` = 应用名；`ResolveInfo.loadLabel()` = 活动名。
- ⚠️ **Kotlin 块注释可嵌套** [V]——注释里避免 `/*` 字面量。
- ⚠️ **miuix `Text` 的 `color` 覆盖 `style.brush`** [V]（继承）——渐变/流光文字必须
  `Color.Unspecified`。见 memory `miuix-text-color-overrides-brush`。
- ⚠️ **协程在途标志必须 `try/finally` 复位** [V]（继承）——见 memory
  `coroutine-flag-needs-try-finally`。
- ⚠️ **部署只改 `.env` 的 `PADDOCK_IMAGE`，永不 sed compose** [V]（继承）——见 memory
  `paddock-compose-must-match-vps`。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——本会话三次 `adb install` 均获明确指令
  （「安装」/ 隐含授权），未启动 App、未读日志。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——见 memory `ask-before-designing-ui`。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）——见 memory `rank-row-number-not-rank`。
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]（继承）——见 memory
  `compose-maxlines-overflow-orthogonal`。
- ⚠️ **R8 内联 value class 使 `strings` 搜符号假阴性** [V]（继承）；中文串用 `grep -a` 字节搜。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）——
  见 memory `lazylist-pagination-sentinel-trap`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20。本会话只装了魅族 20。
- ⚠️ **`/health` 端点不存在（404）** [V]（继承）——探活用 `/v1/leaderboard/points`。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [V]（继承）——看日志环境块的
  构建指纹（memory `build-fingerprint-in-logs`）。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——判据 = 日志含
  `Pushed game logs via chunked broadcast`。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [?]（继承）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（本会话重探：`native/src/crash_hook.c:52`
  `#define HOOK_REG_MAX 64`）——当前约 41 项。
- ⚠️ **用户日志在 Windows 盘** [?]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **等用户对头像选图的其他反馈**：若某些 ROM 上候选列表为空，说明 `<queries>` 未生效——
   先看模块日志的 `图片选择候选 N 个` 那行（`/data/data/tools.alamobile.mod/files/ala_tool.log`，需 root）。
2. **（待用户决定）大奖赛页的产品形态**：当前是占位文案。
3. **（可选）VPS 清理**：`~/paddock` 已积累多个历史镜像（`1.0.1-paging`、
   `1.0.1-driverstats`、`1.0.1-recfix`、`1.0.1-sort`）+ 备份 `.tar.gz` +
   多个 `docker-compose.vps.yml.bak-*`。清理前先确认当前实跑是 `1.0.1-sort`。
4. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块
   `1.0.4 Alpha 4`、服务端 `1.0.1` 均未动。

## 7. 留给用户的开放问题
- 圈速卡的「间隔 2 字符」是否合适？（上会话遗留，未回答）
- 「全服最快圈数量」显示跨版本累计数。是否需要在界面上标注"含历史版本"？（上会话遗留）
- 大奖赛页最终要做什么？当前只有占位。
- 排序方式是否需要记忆（下次进页面保持上次选择）？（上会话遗留）
