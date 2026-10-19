# HANDOFF — 读全文再开始干活

生成时间: 2026-10-19T22:52:00+08:00 · Git HEAD: `ea2655a`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `ea2655a`（已 push）· 服务端 `main` @ `e878790`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `ea2655a`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/DriverScreen.kt`
  （`LapRow` / `TimetrialTab` / `FilterDialog` 的注释写满了「为什么」）

## 1. 当前目标
无进行中的功能切片。本会话完成车手主页计时赛页的两轮改造：
圈速卡三行改版 + 筛选收进弹窗 + 四档排序（跨两仓，服务端已部署）。

## 2. 已验证状态 — 工作实际停在哪
- [V] **圈速卡三行**：赛道名+「最佳」+圈速同行（圈速贴右缘）／时间+版本／
  踏板+TC+ABS。详情行「间隔 2 字符」流式排列，`TextMeasurer` 实测 `"00"` 宽度。
  缺失配置显式显示「缺失」。相邻记录间 `HorizontalDivider`（第 2 行起，背景色之内）。
- [V] **零辅助金标流光修复**：根因 = miuix `Text` 的 `color` 参数覆盖 `style.brush`。
  `goldShimmerStyle` 从 `private` 提为 `internal`，与排行榜共用同一份。
- [V] **筛选改版**：四维控件收进「筛选条件」入口卡（`ArrowPreference` + `FilterAlt`）
  + `FilterDialog`（草稿态，确认才写回）。筛选卡**随列表滚动不固定**。
  弹窗内 spinner `renderInRootScaffold = false`。
- [V] **四档排序**：`time_desc`（缺省）/`time_asc`/`lap_desc`/`lap_asc`。
  服务端 `DriverQuery::order_by()`，未知值回落缺省。
- [V] **「加载中…」常驻修复**：`loadingMore` 改 `try/finally` 复位 + UI 侧
  `entries.size < total` 第二道守卫。
- [V] **compose 漂移根治**：镜像 tag 变量化为 `${PADDOCK_IMAGE:-...}`，值落 VPS
  `~/paddock/.env`。部署只改 `.env`，compose 文件永不改动。
- [V] **已装机**：魅族 20（`192.168.50.142:5555`），APK 22:26:54，装机 22:29:39。
- [V] **服务端已部署**：`paddock-api:1.0.1-sort` 在跑，四档排序实测全部生效。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`，服务端 `1.0.1`。
- [V] **两仓工作区干净**：`main...origin/main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint                           → BUILD SUCCESSFUL in 2m 43s（0 errors）
$ ./gradlew :app:assembleRelease -x lintVital*  → BUILD SUCCESSFUL in 3m 47s，APK 22:26:54
$ cargo check -p paddock-api                    → Finished（2 个既有 warning）
$ docker run --rm <img> strings | grep ORDER    → 四个排序子句全在二进制里
$ ssh ... "docker compose config | grep image"  → image: paddock-api:1.0.1-sort
$ ssh ... "cat ~/paddock/docker-compose.vps.yml" | diff - deploy/...  → 无输出 ✓
排序实测（线上真数据）：
  time_desc → 10-13 22:25 → 22:24 → 22:18（时间降序）
  time_asc  → 09-05 15:14 ×4（时间升序）
  lap_desc  → 1:43.122 → 1:32.407 → 1:31.269（慢的在前）
  lap_asc   → 0:57.562 → 0:58.285 → 0:59.763（快的在前）
```

## 3. 决策与理由
- **详情行用「间隔 2 字符」流式排列，不用等分栏** [V]——等分栏在窄屏给「时间」
  这种长字段挤出省略号，而「版本：8.0.6」留一大片空白。用户先要求 0/1/2、1/3
  分栏，看到实机效果后改为字符间隔。字符间隔用 `TextMeasurer` 而非写死 dp，
  因为用户改系统字号后 12sp 的实际宽度会变。
- **圈速与赛道名同行** [V]——圈速是扫读信息；旧实现放右侧垂直居中，视觉上落在
  两行之间、左缘贴着详情行，读不出层级。
- **金标共用 `goldShimmerStyle` 而非复制一份** [V]——色值/周期副本会各自漂移；
  提 `internal` 让两处同一实现。
- **筛选弹窗用草稿态** [V]——直接改全局会让用户滑一下 spinner 就触发一次重新请求。
- **筛选卡随列表滚动不固定** [V]——用户明确要求「不用像排行榜那样搞」；筛选已收进
  弹窗，页首只剩一张入口卡，随内容滚走反而给列表让出可视高度。
- **compose tag 变量化而非"记得回写"** [V]——根因不是忘了回写，是流程本身必然产生
  漂移：每次部署都在 VPS 上 `sed` 改同一个文件。把可变部分移出去才是根治。

## 4. 失败的尝试 — 不要再试
- [X] **`color = Color(0xFFDAA520)` 做金色流光** [V]——miuix `Text` 的 `color`
  覆盖 `style.brush`，退化成纯静态金色。必须 `Color.Unspecified`。
- [X] **`loadingMore = false` 写在正常路径末尾** [V]——effect key 变化取消协程，
  复位语句执行不到 ⇒ 记录 <30 条时底部永久「加载中…」。必须 `try/finally`。
- [X] **0/1/2、1/3 固定分栏排详情行** [V]——用户实机看到后否决（窄屏长字段省略号）。
- [X] **在 VPS 上 `sed -i` 改 `docker-compose.vps.yml` 换 tag** [V]——两次漂移事故的
  共同病因。现在 tag 走 `.env` 的 `PADDOCK_IMAGE`，永不改 compose 文件。
- [X] **`grep "踏板模式："` 在 dex 里搜字符串** [V]——全角冒号在 smali 里是
  `：` 转义，且 R8 会内联/拆分字符串常量。必须走 mapping.txt + baksmali
  反查混淆名再搜 `\uXXXX` 转义形式。
- [X] 继承死路（详 `.handoffs/20261019224941-handoff.md` §4 及更早）：
  以为两个 BlurredBar 共用 backdrop 会冲突 / 左右容器不同却只调对齐参数 /
  `listState.scrollToItem(0)` 放在请求前 / `unzip -p | strings | grep TextOverflow` /
  zsh 里 `unzip -p "$APK" classes*.dex` glob 不展开 / 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / 抓地力加地板 / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号。

## 5. 已知坑
- ⚠️ **miuix `Text` 的 `color` 覆盖 `style.brush`** [V]——渐变/流光文字必须
  `Color.Unspecified`。见 memory `miuix-text-color-overrides-brush`。
- ⚠️ **协程在途标志必须 `try/finally` 复位** [V]——"数据少时才复现"是指纹。
  见 memory `coroutine-flag-needs-try-finally`。
- ⚠️ **部署只改 `.env` 的 `PADDOCK_IMAGE`，永不 sed compose** [V]——见 memory
  `paddock-compose-must-match-vps`（已重写为新流程）。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——本会话三次 `adb install` 均获明确
  指令（「安装」/「装」），未启动 App、未读日志。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——见 memory
  `ask-before-designing-ui`。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）——见 memory
  `rank-row-number-not-rank`。
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]（继承）——见 memory
  `compose-maxlines-overflow-orthogonal`。
- ⚠️ **R8 内联 value class 使 `strings` 搜符号假阴性** [V]（继承）；
  中文串用 `grep -a` 字节搜，且 smali 里是 `\uXXXX` 转义形式。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）——
  见 memory `lazylist-pagination-sentinel-trap`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20。本会话只装了魅族 20。
- ⚠️ **`/health` 端点不存在（404）** [V]（继承）——探活用
  `/v1/leaderboard/points`。别把 404 误读成"服务挂了"。
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
- ⚠️ **`crash_hook_register` 注册表容量 64** [?]（继承）——当前约 41 项。
- ⚠️ **用户日志在 Windows 盘** [?]（继承）——`/mnt/d/Downloads/QQ/ala_tool_log_*.txt`。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **等用户对计时赛页的进一步反馈**：圈速卡三行观感、间隔 2 字符是否合适、
   筛选弹窗交互、四档排序。异常先拉模块进程日志
   （`/data/data/tools.alamobile.mod/files/ala_tool.log`，需 root），
   分页日志判据看 `load more: offset=... sort=...` 的**相邻时间间隔**。
2. **（待用户决定）大奖赛页的产品形态**：当前是占位文案。
3. **（可选）VPS 清理**：`~/paddock` 已积累多个历史镜像（`1.0.1-paging`、
   `1.0.1-driverstats`、`1.0.1-recfix`、`1.0.1-sort`）+ 备份 `.tar.gz` +
   多个 `docker-compose.vps.yml.bak-*`。清理前先确认当前实跑是 `1.0.1-sort`。
4. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块
   `1.0.4 Alpha 4`、服务端 `1.0.1` 均未动。

## 7. 留给用户的开放问题
- 圈速卡的「间隔 2 字符」是否合适？现在是全局统一值，若第 2 行（时间/版本）
  与第 3 行（踏板/TC/ABS）观感不同，可以分开调。
- 「全服最快圈数量」显示跨版本累计数（Takotsubo = 5）。是否需要在界面上
  标注"含历史版本"？
- 大奖赛页最终要做什么？当前只有占位。
- 排序方式是否需要记忆（下次进页面保持上次选择）？当前每次进页面重置为时间降序。
