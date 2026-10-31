# HANDOFF — 读全文再开始干活

生成时间: 2026-10-31T12:47:00+08:00 · Git HEAD: `f4aadb2`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `f4aadb2`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `f4aadb2`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`app/src/main/kotlin/tools/alamobile/mod/config/ModConfig.kt`](app/src/main/kotlin/tools/alamobile/mod/config/ModConfig.kt)
  的 `Defaults` 对象（768 行起）与 public `DEFAULT_*` 常量（81 行起）

## 1. 当前目标
无进行中的功能切片。本会话完成：**默认配置调整（5 项）+ 振感冲突弹窗补标题 +
升版 1.0.5 Alpha 1 / 105110 + 装机验证通过**。用户已确认「没问题」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **默认值调整**（commit `f4aadb2`）：
  - 隐藏油门和刹车按键 关→**开**（`DEFAULT_HIDE_GAME_PEDALS`）
  - 线性踏板 单踏板→**双踏板**（`DEFAULT_PEDAL_MODE`）
  - V10 开场引擎声浪 关→**开**（`DEFAULT_ENABLE_V10_SOUND`）
  - 抓地力反馈 全部→**关闭**（`SlipFeedbackMode.OFF`；路肩振感本就默认关）
- [V] **振感冲突弹窗补标题**「⚠️振感冲突提示」（`ConfigurePagerMiuix.kt:1191`）
- [V] **升版** 1.0.4/104300 → **1.0.5 Alpha 1/105110**
  （`app/build.gradle.kts` + `module.prop` 两处同步）
- [V] **默认值单一事实源收敛**：新增 public 常量 `DEFAULT_HIDE_GAME_PEDALS` /
  `DEFAULT_ENABLE_V10_SOUND` / `DEFAULT_PEDAL_MODE`，`Defaults` 引用它们；
  `AlaMobileModule` 三处 `?:` 兜底与 `PedalOverlayView` 默认构造参数改引用常量
- [V] **装机验证通过**：魅族 20（`192.168.50.142:5555`）安装 105110 成功，
  `lastUpdateTime=2026-10-31 12:43:31`；用户确认「没问题」
- [V] **README 未改**（用户明确要求：测试版不公开发布，只给内测群）
- [V] 工作区干净，`main...origin/main` 已同步

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
BUILD SUCCESSFUL in 2m 22s        ← 0 error（67 warnings 已在 baseline）

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 29s

$ aapt2 dump badging app-release.apk
package: name='tools.alamobile.mod' versionCode='105110' versionName='1.0.5 Alpha 1'

$ unzip -p APK META-INF/xposed/module.prop
version=1.0.5 Alpha 1 / versionCode=105110

$ unzip -p APK 'classes*.dex' | grep -ac "振感冲突提示"   → 1
$ unzip -p APK 'classes*.dex' | grep -ac "1.0.5 Alpha 1"  → 3

$ adb -s 192.168.50.142:5555 install -r ... → Success
$ dumpsys package → versionCode=105110 versionName=1.0.5 Alpha 1
```

## 3. 决策与理由
- **默认值兜底提升为 public 常量** [V]——`Defaults` 是 private object，早期启动路径
  够不到，只能硬编码字面量 ⇒ 改默认值时必然分裂。提升后两边一个真源。
  否决方案：只改 `Defaults` 不动兜底，会让「默认说开、兜底说关」长期潜伏。
- **README 不动** [V]——用户定案：测试版不公开发布，只给内测群。已写入记忆
  `test-builds-not-public`。
- **存量用户不吃新默认值**（未改迁移逻辑）[V]——本地 JSON 已存旧值，只有全新
  安装/清数据生效。是否要做迁移留给用户决定（§7）。
- **一个 commit 装下默认值 + 版本号** [V]——同一条用户指令下达，属同一变更集。

## 4. 失败的尝试 — 不要再试
- [X] **把 `gh api .../jobs/<id>/logs` 里的 `echo "::error::..."` 当成错误输出**：
  那是 `run:` 块脚本源码整段回显。见全局记忆 `gh-actions-log-shows-script-source`。
- [X] **把完整 README 稿贴进终端让用户审阅**：终端不渲染 Markdown。见全局记忆
  `write-file-not-terminal-preview`。
- [X] **CI 里用 `grep 'versionName'` 松匹配提取版本名**：会命中注释里的散文行。
- [X] **以为 APK 大小相同 = 代码没变**：判据只能是 dex 内容 grep。
- [X] **跑全盘 `find / -name aapt2 -type f`** [V]——120s 未完成被转后台。说不出耗时
  上界的命令就不该跑；定向路径是 Gradle 缓存
  `~/.gradle/caches/*/transforms/*/aapt2-*-linux/aapt2`。
- [X] 继承死路（详 `.handoffs/20261031124618-handoff.md` §4 及更早）：
  整圈五维全填 0 / 按原始两列比对 mode/strength / 只看 API 前 N 条就断言"最快圈" /
  `strings | grep` 搜 APK 中文 / `baksmali` 后直接 grep 中文 / 条件调用 `remember` /
  替用户决定"原始值"口径 / 从 UI 截图反推数据库记录 / 把已确认的信息再问一遍 /
  `onDismissRequest` 与 `onDismissFinished` 合并 / `assets/npatch/` 条目数当指纹 /
  `PickVisualMedia` / `createChooser` 单目标直通 / `ResolveInfo.loadLabel()` /
  `color = Color(...)` 做金色流光 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 /
  hook `HybridComponent.EnableOTK` / hook `drsToggle` / 直写 `_currentDRSState` /
  整段 `md.disasm(detail=True)` / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at` / 升测试版号时改 README。

## 5. 已知坑
- ⚠️ **改 `ModConfig` 默认值 ≠ 只改 `Defaults`** [V]——还要 grep `?: false|true` 兜底
  字面量与 `ModConfig.Settings(` 构造点。见项目记忆 `modconfig-add-field-sync`。
- ⚠️ **`enableTc`/`enableAbs` 是派生值**（`tcMode==DEFAULT || tcStrength!=OFF`），
  不是独立开关；改默认值只能动 `mode`+`strength`。
- ⚠️ **默认值改动在 APK 里「看不见」** [V]——R8 把 `const val` 内联成字节码立即数，
  dex 搜不到符号名。产物验证只能靠间接证据（UI 文案仍在 + 时间戳 + 构建非 UP-TO-DATE）。
- ⚠️ **魅族 20 的 IP 会来回变** [V]——2026-10-30 是 `.142→.123`，2026-10-31 又变回 `.142`。
  逐个试候选 IP，连上后必须核对 `ro.product.model`。
- ⚠️ **Compose `Row` 不自动收缩** [V]（继承）——含动态文本的并排行必须 `weight`+`Ellipsis`。
- ⚠️ **覆盖重发后已装用户收不到更新提示** [V]（继承）——更新检查按 versionCode 比较。
- ⚠️ **往 `build.gradle.kts` 顶部加注释时不要写形似赋值的散文行** [V]（继承）。
- ⚠️ **`gh run view --log` 只返回主 job 日志** [V]（继承）。
- ⚠️ **镜像 release 列表顺序在单 tag 手动触发时不保证正序** [?]（继承，未实测）。
- ⚠️ **`sort=time_desc/asc` 排的是上传时间** [V]（继承）——按圈速排用 `lap_desc`/`lap_asc`。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算** [V]（继承）。
- ⚠️ **产物验证的中文假阴性有两条独立来源** [V]（继承）——`strings` 与 `baksmali`；
  搜中文用 `grep -a` 打原始字节。
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]（继承）——走三层对拍。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]（继承）——先确认搜索覆盖范围。
- ⚠️ **冠军赛路径未验** [?]（继承）——代码层已确认，实跑未测。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 判定是反的** [?]（继承，未重新反汇编）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro，
  `192.168.50.142:5555` = 魅族 20（Flyme，Android 16）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 数据展示口径（精度/小数位/间距）先问再定
  17 下断言前先取证，没有全量数据就不下"这是最快/最好"这类结论
  18 大段文档写文件不贴终端 19 日志里有某行 ≠ 该行被执行了
  20 **用户说「我的设备没事但别人的可能有问题」时，不能因为本机复现不了就否定**

## 6. 下一步（有序）
1. **（待你决定）给内测群发 1.0.5 Alpha 1**：APK 已构建并装机验证，
   路径 `app/build/outputs/apk/release/app-release.apk`。
2. **（待你决定）存量用户是否要吃到新默认值**：当前只有全新安装/清数据生效。
   要触达存量用户需做迁移（改 `read()` fallback 或加版本迁移逻辑）。
3. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能在详情弹窗展示，
   回写游戏的路径未做。
4. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现 `LAPsetup: no setup object`。
5. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- 存量用户的默认值要不要迁移？还是让新默认只对全新安装生效？
- 两个反馈都默认关后，打开路肩振感**不会**弹「振感冲突提示」（该弹窗只在抓地力
  含振感时触发）。这是原有设计——是否要改成「两个都关时打开路肩也弹提示」？
- 覆盖重发/版本相同导致存量用户收不到更新提示，是否需要升 Fix 号？（继承）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承）
- 是否要按调教做榜（如「同一赛道同一调教分组」）？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
