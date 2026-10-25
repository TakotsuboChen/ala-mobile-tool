# HANDOFF — 读全文再开始干活

生成时间: 2026-10-25T01:12:00+08:00 · Git HEAD: `74ea465`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `74ea465`（已 push）· 服务端未动（`8a2da78`，10-20）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `74ea465`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/util/NPatchVersionGate.kt`（门控单一事实源）

## 1. 当前目标
无进行中的功能切片。本会话完成 **NPatch 850 适配**：打 Fix 3 共存包（仅改
versionName 后缀）、模块门控升到 850 + Fix 3、模块发 `1.0.4 Beta 1`，已装机
实测通过。

## 2. 已验证状态 — 工作实际停在哪
- [V] **NPatch 850 实测通过**：用户报「测试正常」——Fix 3 包经 NPatch 850 注入
  自签后，模块功能正常（MEIZU 20 / Android 16）。模块 Alpha 5 装机测试。
- [V] **Fix 3 = Fix 2 + 仅 versionName**：`unzip -l` 条目数 587 = 587，diff 无差异；
  `classes.dex` / `classes2.dex` / `resources.arsc` / `libil2cpp.so` /
  `global-metadata.dat` 五个关键文件 sha256 **逐字节相同**。
- [V] **Fix 3 产物**：`安装包/Ala Mobile Pro 8.0.6 Fix 3 原版.apk`（629,928,350 B，
  sha256 `e0e4e953…`，npatch 条目 0）+ `Ala Mobile Pro 8.0.6 Fix 3.apk`
  （630,816,017 B，md5 `0c82727057d0988603b39776dbeadba2`，npatch 条目 4）。
- [V] **850 的 npatch 布局变化**：846 是 5 条目（`so/{arm64-v8a,x86_64}/libnpatch.so`），
  850 是 **4 条目**（扁平 `assets/npatch/libnpatch.so` 551,176 B 单库）。
  `origin.apk` 629,928,350 B 与推送的裸包**字节数完全相同** ⇒ 只包壳、本体未改。
- [V] **签名逐代相同**：`b3720382…3a57`（850）与 846 代一致 ⇒ 老用户可覆盖安装。
- [V] **门控常量**：`SUPPORTED_NPATCH_VERSION_CODE = 850L`、`SUPPORTED_COEX_FIX = 3`、
  `SUPPORTED_VERSION_NAME/CODE` 未动（8.0.6 / 200150）。
- [V] **模块产物**：`versionName='1.0.4 Beta 1'` / `versionCode='104210'`；
  baksmali 反查 `const-wide/16 0x352`（850）3 处、`0x34e`（846）**0 处**；
  签名 `CN=AlaMobileTool` / `e0bc20a5…`。
- [V] **装机**：魅族 20 已装 `1.0.4 Beta 1` / `104210`（`install -r` Success）。
- [V] **构建 + lint**：`assembleRelease` BUILD SUCCESSFUL（2m49s）；
  `:app:lint` BUILD SUCCESSFUL exit 0（1m56s，67 warnings / 0 errors）。
- [V] **旧包已清理**：删除 Fix 1/2 四份（约 2.4GB），`安装包/` 只留 Fix 3 两份
  + 三份历史包（`官方原版.apks` / `Takotsubo 共存版.apk` / `(NPatch打包 + 自签).apk`）。
- [V] **工作区干净**：`git status --short --branch -uall` → `## main...origin/main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 49s
50 actionable tasks: 20 executed, 30 up-to-date

$ ./gradlew :app:lint
BUILD SUCCESSFUL in 1m 56s
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by lint-baseline.xml)

$ aapt2 dump badging app-release.apk
versionCode='104210'  versionName='1.0.4 Beta 1'

$ unzip -p <Fix3.apk> assets/npatch/config.json | grep VERSION_CODE
"VERSION_CODE":850
```

## 3. 决策与理由
- **Fix 3 只改 versionName 后缀，不动包内容** [V]——用户定案。因为双精确相等门控
  要求包形态与框架版本配套，同引擎内升 NPatch 也要发新 Fix 号。
- **门控升级而非临时放宽** [V]——用户选「改成 Fix 3 + NPatch 850，发模块新版」，
  否决「临时放宽为兼容旧值」与「先不动模块只测 846」。
- **版本号 `1.0.4 Beta 1` / `104210`** [V]——用户指定，编码符合规范
  （A.B.C=104 · 阶段2=Beta · D=1 · 保留0）。
- **模块自带 ShadowHook，与 NPatch 847 剥离无关** [V]——`libala-core.so` 的
  `NEEDED` 指向模块 APK 自己的 `libshadowhook.so`（101,880 B）。真风险是
  bhook（inline hook）与 ShadowHook 的框架共存，实测无冲突。
  见 memory `npatch-strips-own-shadowhook-not-modules`。
- **产物判据改用 config.json 的 VERSION_CODE** [V]——条目数随 NPatch 版本变
  （846=5 / 850=4），不能当指纹。见 memory `npatch-artifact-fingerprint`。

## 4. 失败的尝试 — 不要再试
- [X] **「assets/npatch/ 条目数 = 5」当已修补指纹** [V]——850 改成 4 条目，
  实测时一度误判成「修补失败/缺文件」。改用 `config.json` 的 `VERSION_CODE`。
- [X] **`grep "0x34e"` 判常量是否进包** [V]——命中的是 `0x34e696b7`（32 位哈希），
  **前缀匹配假阳性**。必须匹配完整指令形态 `const-wide/16 vN, 0x34e`。
- [X] **后台跑 `cmd | tail -8`** [V]——`tail` 缓冲到进程结束才吐字，输出文件
  长时间 0 字节，误以为卡住。后台命令直接重定向全量输出。
- [X] **把「要不要 git commit」拿去 AskUserQuestion** [V]——用户答「/handoff 才
  提交啊，你神经吗」。全局 CLAUDE.md 已写明提交策略，问它 = 把定好的规则推回用户。
  见 memory `ask-before-any-approach` 的反面小节。
- [X] 继承死路（详 `.handoffs/20261025011016-handoff.md` §4 及更早）：
  门控 `assets/npatch/config.json` 的 `lspConfig.VERSION_CODE`（那是打包时用的版本，
  不是注入时的）/ 模块进程复用 `evaluateNPatchGate`（恒 Pass）/ NPatch 状态塞进游戏包
  胶囊文案 / 文案指向"下载共存版" / 用 hook 强制拦截代替"想起来问" /
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
- ⚠️ **NPatch 门控有两条判据不同的路径** [V]——游戏进程判框架+管理器双 850；
  模块进程只判管理器。改门控前先确认改的是哪条。见 CLAUDE.md `NPatchVersionGate.kt`
  条目与 memory `npatch-version-gate-independent-dimension`。
- ⚠️ **模块进程 UI 显示"已适配"不等于游戏放行** [V]——管理器版本 ≠ 包内框架版本
  时会误判。游戏内循环 Toast 才是权威提示。
- ⚠️ **850 起 `assets/npatch/` 是 4 条目扁平布局** [V]——见 memory
  `npatch-artifact-fingerprint`。判注入版本只认 `config.json` 的 `VERSION_CODE`。
- ⚠️ **产物实证三链** [V]——见 memory `apk-artifact-verification-chain`。
  APK 大小相同 ≠ 没变。
- ⚠️ **`baksmali` 输出把中文转成 `\uXXXX`** [V]——搜 ASCII 前缀或直接
  `unzip -p | grep -a`。
- ⚠️ **native 运行时开关必须单一事实源** [V]（继承）——见 memory
  `runtime-switch-single-source-of-truth`。
- ⚠️ **隐藏/屏蔽类功能：路径全覆盖 + 范围对 + 判据形态对** [V]（继承）——见 memory
  `data-filter-must-cover-all-recalc-paths`。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——见 memory `ask-before-designing-ui`。
- ⚠️ **待决事项必须用 AskUserQuestion；已由明文策略定好的事不许问** [V]——见 memory
  `ask-before-any-approach`（含本次两处实例）。
- ⚠️ **报症状时先消歧** [V]——见 memory `ask-before-any-approach` 的消歧小节。
- ⚠️ **VPS compose 必须带 `-f docker-compose.vps.yml`，切镜像只改 `.env`** [V]（继承）。
- ⚠️ **`/handoff` 的 push 无需额外授权，且必须做完** [V]（继承）。
- ⚠️ **选图/选文件必须自绘选择器** [V]（继承）——见 memory `android-file-picker-no-gms`。
- ⚠️ **miuix `Text` 的 `color` 覆盖 `style.brush`** [V]（继承）。
- ⚠️ **协程在途标志必须 `try/finally` 复位** [V]（继承）。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——见 memory `no-autonomous-device-control`。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]（继承）——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20（Flyme 12.6，Android 16，NPatch 850）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [?]（继承，`EnvInfo` 已修）。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 管理器晚于游戏启动时不会自动重连** [?]（继承，843 起仍有边界）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）发版**：`1.0.4 Beta 1` 已构建并装机验证，但**未发布**。发布 = 打 tag
   + push，CI 自动发 Pre-release（Beta 走 prerelease=true）。⚠️ 发版时机完全由你决定，
   我不主动提议。
2. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：服务端已有能力（`8a2da78` 把屏蔽
   从快照升级为持续生效开关），模块 UI 侧 `grep hideHidden|hide_masked|隐藏已屏蔽`
   **零命中**，开关未做。
3. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像 + 备份 `.tar.gz`
   + `.env.bak-*`。清理前先确认当前实跑镜像 tag。
4. **（待你决定）大奖赛页的产品形态**：当前是占位 Toast「开发中，敬请期待！」。
5. **（待你决定）NPatch 版本号换算表补全**：`docs/NPATCH_CACHE_CRASH_NOTES.md`
   §14.3 只记录了 847/849/850 的发布说明原文，**未做偏移换算**（缺 850 对应的公开
   镜像 commitCount）。要补需按 §14 的「核对手法」重新锚定。

## 7. 留给用户的开放问题
- **NPatch 门控现在要求 850**。846 及更早的用户升级模块后会被拦（零 hook +
  循环 Toast，文案指向「到 QQ 群下载最新 NPatch」）。是否要先在群里通知？
- 圈速卡的「间隔 2 字符」是否合适？（遗留，未回答）
- 「全服最快圈数量」显示跨版本累计数。是否需要在界面上标注"含历史版本"？
- 大奖赛页最终要做什么？当前只有占位。
- 排序方式是否需要记忆（下次进页面保持上次选择）？
- **屏蔽后积分可能上涨**（金标成绩顶上会吃加成）。界面上目前不提，是否需要提示？
