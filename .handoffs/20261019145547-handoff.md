# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T22:00:00+08:00 · Git HEAD: `fc8cf0d`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `fc8cf0d`（已 push）· 服务端 `main` @ `b70d429`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `fc8cf0d`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/DriverScreen.kt:162-215`
  （分页 effect，本会话加了诊断日志）

## 1. 当前目标
无进行中的功能切片。本会话两件事：① 处理上一份 HANDOFF 遗留的两条重探项
（DriverScreen 补分页诊断日志 + compose 镜像 tag 不一致）；
② 装机实测分页，日志确认**哨兵值 bug 未复发**。

用户 2026-10-18 对围场功能定案「功能基本正常，暂时这样，下个会话再改」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **DriverScreen 分页诊断日志已加**：`import Logger`(:61) +
  `Logger.i(TAG_DRIVER, "load more: offset=... total=... track/ver/zero/best")`
  （分页 collect 内、发请求前）+ `private const val TAG_DRIVER = "Driver"`（文件尾）。
  比榜单页多打**筛选指纹**——本页四维筛选，同一 offset 在不同筛选下是不同查询。
- [V] **compose 对齐 VPS**（姊妹仓 `b70d429`）：实际漂移比记录的严重——
  不只 tag，整份配置都漂了。`1.0.1`→`1.0.1-paging`、`RUST_LOG` info,warn→debug,info、
  **补 `GARAGE_ENDPOINT/KEY_ID/SECRET`**。最后一项后果最严重：
  `paddock-api/src/api/avatar.rs:46-47` 读不到凭据直接返回 `None` ⇒
  照仓库版重建 VPS **头像功能静默失效**。已写入记忆 `paddock-compose-must-match-vps`。
- [V] **装机实测分页通过**：魅族 20（`192.168.50.142:5555`），`lastUpdateTime` 21:55:58。
  车手主页 192 条 / 7 页，翻页间隔 **1.44 / 1.36 / 1.48 / 1.79 / 0.80s**，
  `<200ms` 连发 **0 次** ⇒ 哨兵值 bug 未复发。尾页 `offset=180` 响应仅 3622 字节
  （其他页 ~8600，即 12 条）⇒ `180+12=192=total`，之后无第 7 次请求。
  测试时段 198 条日志，`[W]`/`[E]` **零条**。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`，服务端 `1.0.1`。未打 tag、未发 Release。
- [V] **工作区干净**：两仓 `main...origin/main`，仅本 HANDOFF 提交内容待入。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
  → BUILD SUCCESSFUL in 1m 33s（66 warnings / 4 hints；baseline 既有项）
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 1m 31s，APK 21:52:44（源码 21:48:54，晚于改动 ✓）
$ unzip -p <apk> classes.dex | strings | grep "load more: offset="  → 1 处命中
$ unzip -p <apk> classes.dex | strings | grep -cx "Driver"          → 1 处命中
$ adb -s 192.168.50.142:5555 install -r <apk>                        → Success
$ adb ... dumpsys package | grep lastUpdateTime                      → 21:55:58
$ grep -v '^#' VPS-compose | diff - <(grep -v '^#' repo-compose)     → 配置行完全一致
$ python3 -c "yaml.safe_load(open('deploy/docker-compose.vps.yml'))" → YAML OK
```

## 3. 决策与理由
- **诊断日志带筛选指纹** [V]——DriverScreen 有四维筛选，同一 offset 在不同筛选下
  是不同查询；只打 offset 拿到日志也分不清是哪一条。榜单页没有筛选，故不需要。
- **`TAG_DRIVER` 与 `TAG_BOARD` 分开** [V]——按页面过滤日志。
- **compose 对齐实跑值而非改回 `1.0.1`** [V]——VPS 实跑的就是 `1.0.1-paging`，
  仓库副本是灾难恢复源，必须描述**现实**而不是理想。改回会让恢复出来是旧构建。
- **判据用日志间隔而非请求次数** [V]——6 次翻页拉完 192 条，看次数像"一口气拉完"，
  看间隔（1.44/1.36/1.48/1.79/0.80s）才看得出是人在滚动。见 memory
  `lazylist-pagination-sentinel-trap`。

## 4. 失败的尝试 — 不要再试
- [X] **以为 compose 漂移只是 tag 不一致** [V]——实际整份配置都漂了。
  **只 diff 单个字段会漏掉结构性缺失**（本次差点漏掉 Garage 三变量）。
  核对必须整文件 diff。
- [X] 继承死路（详 `.handoffs/20261018215848-handoff.md` §4 及更早）：
  把 `OPD2413` 说成 "OPPO 平板" / `unzip -p | strings | grep TextOverflow` 验证
  Compose value class（R8 内联成 int，恒零命中）/ Edit 报错后直接重试同一字符串 /
  zsh 里 `unzip -p "$APK" classes*.dex` glob 不展开 / VPS 上 `apt-get update` 构建镜像 /
  `FROM` 复用 alpine 镜像 + COPY glibc 产物 / 轮询等待慢构建 / 真库测试并行跑 /
  `--rerun-tasks` 重编 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 / 抓地力加地板 /
  `HapticGenerator` / `pm grant VIBRATE` / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板「单向补送」/ `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301 /
  给旧 APK 留 `(NPatch 845)` 回滚副本 / 用 `+6` 偏移硬推 NPatch 版本号。

## 5. 已知坑
- ⚠️ **`deploy/docker-compose.vps.yml` 必须与 VPS 逐字一致** [V]——
  见 memory `paddock-compose-must-match-vps`（含核对命令与本次漂移详情）。
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]（继承）——只写 `maxLines = 1`
  默认 `Clip` 硬裁切。见 memory `compose-maxlines-overflow-orthogonal`。
- ⚠️ **R8 内联 value class 使 `strings` 搜符号假阴性** [V]（继承）——
  但**日志字符串不受影响**（是真正的方法实参常量），`strings` 对它是有效验证手段。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 比较恒真** [V]——
  空状态必须短路返回 `false`。判据看**请求间隔**不看次数。
  见 memory `lazylist-pagination-sentinel-trap`（含 2026-10-18 实机复验数据）。
- ⚠️ **围场 UI 日志在模块进程 `filesDir`，需 root** [V]（继承）——
  见 memory `module-log-two-process-split`。
- ⚠️ **日志文件 2MB 滚动截断** [V]（继承）——本次已滚掉 21:20 之前的记录。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——任何 adb 动作都算。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——新增设置项前先问。
- ⚠️ **物理上限解除 ≠ 视觉/UI 跟着变** [V]（继承）——见 memory `physics-limit-vs-visual-readout`。
- ⚠️ **`strings` 查不到中文 ≠ 改动没进包** [V]（继承）——用 `grep -a` 字节搜。
- ⚠️ **NPatch `VERSION_CODE` = `git commitCount`** [V]（继承）——
  见 memory `npatch-versioncode-is-commitcount`。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **IL2CPP 浮点字段必须用 `find_field_access_f.py`** [V]（继承）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [V]（继承）——
  看日志环境块的构建指纹（见 memory `build-fingerprint-in-logs`）。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——
  判据 = 日志含 `Pushed game logs via chunked broadcast`。
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
1. **等用户对围场其余功能的反馈**：① 信息卡右侧箭头点击；② 车手主页四维筛选
   （现已有诊断日志可查）；③ 榜单点行进主页；④ 长昵称在**车手主页**与**主页卡片**的省略表现。
   异常先拉模块进程日志（`/data/data/tools.alamobile.mod/files/ala_tool.log`，需 root）。
2. **（待用户决定）榜单页 `offset=0` 重复拉取**：日志里 21:56:09 与 21:56:12
   两次 `offset=0`，中间无筛选切换日志。可能是下拉刷新/切 tab/重组，**当前无法区分**。
   用户尚未表态是否要加「重拉第一页的触发原因」日志。
3. **（可选）846 包分发**：`安装包/Ala Mobile Pro 8.0.6 Fix 2.apk` 已入库待发。
   发之前确认用户是否需要（不主动提版本号/发版）。
4. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块 `1.0.4 Alpha 4`、
   服务端 `1.0.1` 均未动。
5. **（可选）服务端镜像与备份清理**：VPS `~/paddock` 已 208MB，40+ 历史镜像
   （`v1`~`v39` 等）+ 多个 `.tar.gz`/`.tar.zst` 备份包。

## 7. 留给用户的开放问题
- 榜单页 `offset=0` 在 2.3s 内被拉两次，要不要加日志区分「下拉刷新 / 切 tab / 重组」？
- `paddock-api:1.0.1-paging` 这个 tag 是否要提升为正式版本号？现在它挂在 `1.0.1`
  名下但内容已含分页功能，语义不匹配。
- 车手主页仍有「计时赛积分 / 有成绩赛道 / 零辅助最快圈」三列，是否也要调整？
- 分页"到底"目前靠 `entries.size >= total` 静默判断，是否需要显式"没有更多了"提示？
