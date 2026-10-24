# HANDOFF — 读全文再开始干活

生成时间: 2026-10-25T00:28:00+08:00 · Git HEAD: `21dfefa`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `21dfefa`（已 push）· 服务端未动
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `21dfefa`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/util/NPatchVersionGate.kt`（门控单一事实源）

## 1. 当前目标
无进行中的功能切片。本会话完成 **NPatch 版本门控**：游戏进程内要求框架版本与
管理器版本都精确等于 846，否则零 hook + 循环 Toast；概览页拆成两个并排胶囊。

## 2. 已验证状态 — 工作实际停在哪
- [V] **门控真机生效**：847 测试包装魅族 20 后，`ala_tool.log` 记录
  `[W/ForceUpdateGate] NPatch version MISMATCH → zero hooks, framework=846,
  manager=846, required=847, toast loop started`；此后日志彻底停止（10818 行后再无输出），
  `grep -c "shadowhook|native|overlay|pedal|drs|overtake|slip|kerb"` → **0 命中**；
  `ala_tool_native.log` mtime 仍停在 10-22 ⇒ ShadowHook 根本没 init。
- [V] **框架识别可用**：环境块打印 `框架: NPatch 框架=846 / 管理器=846`，
  两个物理分离的来源都读到且一致（`frameworkVersionCode` 注入值 + PackageManager 查询值）。
- [V] **846 已改回**：反汇编确认 `const-wide/16 v4, 0x34e`（846）在包、`0x34f`（847）零命中。
- [V] **双胶囊已装机验收**：用户确认「正常了」——两个并排胶囊都显示正确。
- [V] **构建**：`./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease
  -x :app:lintVitalRelease` → BUILD SUCCESSFUL in 2m5s。`:app:lint` → BUILD SUCCESSFUL
  （67 warnings / 0 errors，均为既有）。
- [V] **工作区干净**：`git status --short --branch -uall` → `## main...origin/main`。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
BUILD SUCCESSFUL in 1m 39s
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline lint-baseline.xml)

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 5s
50 actionable tasks: 12 executed, 38 up-to-date

$ baksmali d classes.dex -o out && grep -rn "0x34e\|0x34f" out/
out/qi0.smali:1409:    const-wide/16 v16, 0x34e
out/zd1.smali:87:    const-wide/16 v0, 0x34e
out/tm1.smali:660:    const-wide/16 v3, 0x34e
# 0x34f 零命中
```

## 3. 决策与理由
- **门控判据 = 精确等于，不是 `>=`** [V]——用户原话「以后永远精确等于」。
  否决方案「>= 846 放行」：用户明确否决过「什么大于等于，精确等于还">= 846 才放行"，你管这个叫等于？」
- **用 `frameworkVersionCode` 不用 `frameworkVersion`** [V]——NPatch 846 上后者
  硬编码返回 `"v1.0.7"`（上游 `core/build.gradle.kts:250` 未随发版更新），
  与包内 `config.json` 的 `1.0.9` 都对不上。
- **游戏进程与模块进程的判据必须分开** [V]——模块 App 是普通 App，永不被 Xposed
  注入 ⇒ `currentFrameworkKind()` 恒 `UNKNOWN` ⇒ `evaluateNPatchGate` 在模块进程
  恒返回 Pass。早期复用导致 `GameVersionStatus.NPatchOutdated` 成为**死分支**（实机：
  门控已生效但胶囊仍绿色「已适配」）。模块进程改用 `evaluateManagerVersionGate`（只看管理器）。
  见 memory `npatch-version-gate-independent-dimension`。
- **两个独立胶囊而非一个** [V]——用户定案「只显示 Ala Mobile Pro 的，不显示 NPatch 的」。
  游戏包版本与 NPatch 版本正交，合成一个会丢失"哪个组件有问题"。见 §4 相关死路。
- **框架信息走进程级 `System.setProperty`** [V]——用户定案。否决方案「写信箱文件
  传给模块进程」：用户选了纯查管理器版本，不需要。
- **装机前必须先问设备** [V]——用户定案设备必须点名，不得从 `adb devices` 推断。

## 4. 失败的尝试 — 不要再试
- [X] **门控游戏包内 `assets/npatch/config.json` 的 `lspConfig.VERSION_CODE`** [V]——
  那是"打包时用了哪个 NPatch"，不是"当前注入的是哪个"；且 cache 模式下游戏进程读不到。
  用户原话：「我要的是 NPatch 这个 App 的门控……不是门控共存版游戏本体」。
  整条实现已 revert 重写。
- [X] **模块进程复用 `evaluateNPatchGate`** [V]——恒返回 Pass，UI 分支永远走不到。
- [X] **把 NPatch 状态塞进游戏包胶囊的文案里** [V]——用户要的是两个并排胶囊。
- [X] **文案指向"下载共存版"** [V]——用户两次纠正「没水喝就去买辣条？」。必须指向
  「到 QQ 群下载最新 NPatch」。
- [X] **用 hook 强制拦截代替"想起来问"** [V]——用户否决 `guard-adb.py` /
  `session-redlines.py`，原话「不要这样，就要想起来问」。两脚本已删除、settings.json hooks 段已清空。
- [X] 继承死路（详 `.handoffs/20261025002748-handoff.md` §4 及更早）：
  `PickVisualMedia` / `createChooser` 单目标直通 / `EXTRA_INITIAL_INTENTS` /
  `ResolveInfo.loadLabel()` / `color = Color(...)` 做金色流光 / `loadingMore` 写正常路径末尾 /
  0/1/2 固定分栏 / VPS 上 `sed` 改 compose / 两个 BlurredBar 共用 backdrop /
  `unzip -p | strings | grep`（中文需 `grep -a` 直接搜 dex）/ 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / 抓地力加地板 / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号 / 改完文案就构建装机（未先确认）/
  `g_*_active || g_config.enable_*` 双判据 / `baksmali` 搜中文（`\uXXXX` 转义假阴性）。

## 5. 已知坑
- ⚠️ **NPatch 门控有两条判据不同的路径** [V]——见 CLAUDE.md `NPatchVersionGate.kt`
  条目与 memory `npatch-version-gate-independent-dimension`。改门控前先确认改的是哪条。
- ⚠️ **模块进程 UI 显示"已适配"不等于游戏放行** [V]——管理器版本 ≠ 包内框架版本时
  会误判。游戏内循环 Toast 才是权威提示。
- ⚠️ **产物实证三链** [V]——见 memory `apk-artifact-verification-chain`。
  APK 大小相同 ≠ 没变（三次构建都是 11392575 字节但内容确实变了）。
- ⚠️ **`baksmali` 输出把中文转成 `\uXXXX`** [V]——`grep "NPatch："` 恒零命中，
  搜 ASCII 前缀或直接 `unzip -p | grep -a`。
- ⚠️ **native 运行时开关必须单一事实源** [V]（继承）——见 memory
  `runtime-switch-single-source-of-truth`。
- ⚠️ **隐藏/屏蔽类功能：路径全覆盖 + 范围对 + 判据形态对** [V]（继承）——见 memory
  `data-filter-must-cover-all-recalc-paths`。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——见 memory `ask-before-designing-ui`。
- ⚠️ **报症状时先消歧** [V]——「不显示」可能是缺一部分而非全没有，见 memory
  `ask-before-any-approach`。
- ⚠️ **VPS compose 必须带 `-f docker-compose.vps.yml`，切镜像只改 `.env`** [V]（继承）。
- ⚠️ **中文文案验证必须直接 grep dex 字节** [V]（继承）。
- ⚠️ **`/handoff` 的 push 无需额外授权，且必须做完** [V]（继承）。
- ⚠️ **选图/选文件必须自绘选择器** [V]（继承）——见 memory `android-file-picker-no-gms`。
- ⚠️ **miuix `Text` 的 `color` 覆盖 `style.brush`** [V]（继承）。
- ⚠️ **协程在途标志必须 `try/finally` 复位** [V]（继承）。
- ⚠️ **设备操作必须事先获许可** [V]（继承）。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]（继承）——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20（Flyme 12.6，Android 16，NPatch 846）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [?]（继承）。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）。
- ⚠️ **改 `SUPPORTED_COEX_FIX` / `SUPPORTED_NPATCH_VERSION_CODE` 都是发版动作** [?]。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）发版**：NPatch 门控是**用户可感知的破坏性变更**——所有 NPatch 版本
   ≠ 846 的用户升级模块后会被拦（零 hook + 循环 Toast）。发版前需先确认用户群里的
   NPatch 版本分布，或先在群里发通知。**版本号定多少必须问用户**。
2. **（待你决定）`.claude/skills/coex-apk-builder` 的 NPatch 升版流程实跑一次**：
   本次只改了文档，没有真的打过一个新 NPatch 版本的包来验证第 7 步。
3. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：该用户仍有 21 条红标（142 条圈里）。
4. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像 + 备份 `.tar.gz`
   + `.env.bak-*`。清理前先确认当前实跑是 `1.0.1-autohide`。
5. **（待你决定）大奖赛页的产品形态**：当前是占位文案。

## 7. 留给用户的开放问题
- **NPatch 门控会拦住所有版本 ≠ 846 的用户**。是否要先在群里通知？还是等他们
  自己撞到 Toast 再说？Toast 文案已指向「到 QQ 群下载最新 NPatch」。
- 圈速卡的「间隔 2 字符」是否合适？（三轮前遗留，未回答）
- 「全服最快圈数量」显示跨版本累计数。是否需要在界面上标注"含历史版本"？
- 大奖赛页最终要做什么？当前只有占位。
- 排序方式是否需要记忆（下次进页面保持上次选择）？
- **屏蔽后积分可能上涨**（金标成绩顶上会吃加成）。界面上目前不提，是否需要提示？
