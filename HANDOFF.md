# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T17:08:12+08:00 · Git HEAD: `cec8954`（模块仓）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `cec8954`（已 push）· 服务端 `main` @ `f48e658`（已 push）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `cec8954`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 有两条（用户体感验收、生产库 SQL 长跑稳定性）
- 先读: `app/src/main/kotlin/tools/alamobile/mod/ui/screen/paddock/DriverScreen.kt`（车手主页全文）

## 1. 当前目标
围场四项功能已全部落地并部署上线、装机完成。用户 2026-10-18 定案
**「功能基本正常，暂时这样，下个会话再改」**。无进行中的工作切片。

## 2. 已验证状态 — 工作实际停在哪
- [V] **服务端已部署上线**：镜像 `paddock-api:1.0.1-paging`，`paddock-app-1` Up，
  `curl /v1/health` → `{"status":"ok","version":"1.0.1"}`。
- [V] **分页行为实测**：积分榜 `limit=3` → `entries=3, total=109`；
  赛道榜 page1 `ranks=[1,2,3]`、page2 `ranks=[4,5,6]`（全局名次未按页重排）。
- [V] **车手主页实测**（真实数据 CharlieYeAilian）：`reg_seq=34, total_points=2062,
  best=16, zero=16`，`total=185`；`best=1` → 16 条且全 personal_best；
  `assist=zero` → 91 条且全零 score；`gp_index=0` → 7 条；不存在用户 → 404。
- [V] **真库 SQL 测试 8/8 全绿**（含新增分页测试），临时库跑完即删，未触碰生产库。
- [V] **模块已装机 MEIZU 20**：`install -r` → Success，
  `lastUpdateTime=2026-10-18 17:04:09`，`versionCode=104140` / `versionName=1.0.4 Alpha 4`。
- [V] **构建验证**：`:app:compileDebugKotlin` 成功；`:app:lint` 0 error（66 warnings 与基线一致）；
  `assembleRelease` 成功；APK 16:44:49 晚于源码 16:40:21；
  dex 字节级命中 `号车手`/`仅零辅助`/`仅个人最佳`/`车手主页`/`/v1/driver/`/`limit=`/`offset=`。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`，服务端 `1.0.1`。未打 tag、未发 Release。
- **工作区**: 两仓均干净（本 handoff 提交前）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ cargo test --release --bin paddock-api -- --ignored --test-threads=1
  → test result: ok. 8 passed; 0 failed; 0 ignored
    （并行跑会 1 passed / 6 failed —— 见 memory sql-test-must-run-serial-and-temp-db）

$ ./gradlew :app:lint            → BUILD SUCCESSFUL（0 error / 66 warnings）
$ ./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 2m 17s
$ adb install -r app-release.apk → Performing Streamed Install / Success
```

## 3. 决策与理由
- **圈速记录数据源 = `laps` 全量留档，不是 `best_laps`** [V]——用户定案要看进步曲线；
  `best_laps` 只服务榜单与"仅个人最佳"筛选。否决：只显示每人每赛道最佳（看不出进步）。
- **统一 30 条/页** [V]——用户第一次说"30 条…再加载后面的 50 条"，追问后明确回「30 条」。
  服务端硬上限 100 防手改 URL 拉全表。
- **排序必须下推到 SQL** [V]——原实现 Rust 侧 `sort_by` 后切片，`LIMIT/OFFSET` 语义失效。
  赛道榜 `rank` 改用 `row_number() OVER (ORDER BY lap_ms)` 全局算，原 `enumerate()` 会让
  第二页从 1 重排。
- **`total` 用独立 `count(*)` 查询** [V]——`count(*) OVER ()` 在空页（offset 越界）拿不到值，
  客户端会把"没有更多"误判成"榜是空的"。
- **车手主页是独立公开端点 `/v1/driver/{user_id}`，不复用 `/v1/me`** [V]——后者是 Bearer
  本人查询，且响应形状被登录恢复路径依赖，动它有回归风险。
- **`total_points_of()` 抽出共享** [V]——`/v1/me` 与车手主页同口径，两份副本必然漂移。
- **信息卡整卡可点而非只点箭头** [V]——箭头是视觉指引，整卡热区更符合 miuix preference 习惯。
- **`TRACK_NAMES` / `boardRowShape` 提为 internal** [V]——赛道名是双仓契约，不能复制第二份。

## 4. 失败的尝试 — 不要再试
- [X] **在 VPS 上 `apt-get update` 构建镜像** [V]——国内源 5 分钟跑不完。
  改用本地 `docker build`（仓库自带 Dockerfile，缓存全命中 1.1s）→ save|gzip → scp → load。
- [X] **`FROM paddock-api:1.0.1` + COPY 本地 cargo 产物** [V]——现有镜像是 **alpine(musl)**，
  本地 `cargo build` 出 glibc 二进制 → 容器 255 反复重启，日志只报误导性的
  `exec ... no such file or directory`。已回滚。要复用基础层必须先让产物 ABI 一致。
- [X] **轮询等待慢构建** [V]——用户当场纠正「慢就顺手解决慢的问题啊」。
  见 memory `fix-slow-operations-not-wait`（全局）。
- [X] **真库测试并行跑** [V]——`wipe()` 互相清空 → 6 个 FK 约束失败，看着像代码 bug。
  必须 `--test-threads=1`。
- [X] 继承死路（详 `.handoffs/20261018161710-handoff.md` §4 及更早）：`--rerun-tasks` 重编 /
  相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 / 抓地力加地板 / `HapticGenerator` /
  `pm grant VIBRATE` / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 /
  变暗层插 index 0 / 踏板「单向补送」/ `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301。

## 5. 已知坑
- ⚠️ **`loadingMore` 绝不能放进 `LaunchedEffect` 的 key** [V]——effect 内把它置 true 会触发
  重组 → key 变化 → Compose 取消自身协程 → 收尾的 `loadingMore = false` 永不执行 →
  永久卡"加载中…"。两处分页（LeaderboardScreen / DriverScreen）均已改用协程内局部 `busy`。
- ⚠️ **分页 effect 的 key 必须含数据条数** [V]——`entries.size`/`currentSize` 进 key，
  每加载一页重启一次 effect；否则 `snapshotFlow` 的 `distinctUntilChanged` 不会重新评估。
- ⚠️ **`strings` 查不到中文 ≠ 改动没进包** [V]——`strings` 默认只提 ASCII。判据用
  `grep -c -a <中文>` 直接字节搜。另 `unzip -p "$APK" classes*.dex` 的 glob 是 shell 展开，
  APK 里没有该字面文件名，必须 `unzip -Z1` 列真实条目名。
- ⚠️ **zsh 里 `UID` 是只读内置变量** [V]——赋值静默失败，后续命令拿到空串。
  写 shell 脚本时换 `DID`/`USER_ID` 一类名字。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——新增设置项前先问。见全局记忆 `ask-before-designing-ui`。
- ⚠️ **物理上限解除 ≠ 视觉/UI 跟着变** [V]（继承）——见 memory `physics-limit-vs-visual-readout`。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **IL2CPP 浮点字段必须用 `find_field_access_f.py`** [V]（继承）。
- ⚠️ **日志 2MB 滚动截断会吃掉安装期证据** [V]（继承）。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——任何 adb 动作都算。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [V]（继承）——看日志环境块的构建指纹。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——判据 = 日志含 `Pushed game logs via chunked broadcast`。
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
1. **等用户实机反馈**（当前定案「暂时这样，下个会话再改」）：需要确认的是
   ① 长昵称是否真的尾部省略成 `1234567…`；② 信息卡右侧箭头点击是否进得去；
   ③ 排行榜下滑是否自动续 30 条；④ 车手主页四维筛选手感；⑤ 榜单点行进主页。
   有异常先拉 `/sdcard/Android/media/com.Takotsubo.AlamobileFormula/ala_tool.log`。
2. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块 `1.0.4 Alpha 4`、
   服务端 `1.0.1` 均未动。
3. **（可选）服务端镜像清理**：VPS 上 `paddock-api:v1..v39` 等 40+ 历史镜像占空间，
   清理前先确认无回滚需求。compose 当前指向 `1.0.1-paging`。

## 7. 留给用户的开放问题
- 车手主页的「赛季积分 / 赛季胜场」在围场主页仍是「暂无」占位，要不要接服务端数据？
- 分页的"到底"目前靠 `entries.size >= total` 静默判断，是否需要显式的"没有更多了"提示？
- VPS 上的历史镜像要不要清？
