# HANDOFF — 读全文再开始干活

生成时间: 2026-10-18T21:00:39+08:00 · Git HEAD: `98c3431`（模块仓）
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `98c3431`（已 push）· 服务端 `main` @ `f48e658`（未动）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `98c3431`——HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项（振感验收环境、`isTiltSteer` 反向等）
- 先读: `docs/NPATCH_CACHE_CRASH_NOTES.md` §14（NPatch 版本号换算表）

## 1. 当前目标
无进行中的功能切片。本次会话完成两件收尾工作：① NPatch 846 共存包入库并全项验证；
② 修正上一轮对 NPatch 版本号的错误推算，把换算表与 846 改动固化进文档。

用户 2026-10-18 对围场功能定案「功能基本正常，暂时这样，下个会话再改」。

## 2. 已验证状态 — 工作实际停在哪
- [V] **NPatch 846 共存包已入库**：`安装包/Ala Mobile Pro 8.0.6 Fix 2.apk`
  （631,356,783 B，sha256 `9a626be3…bee59`），来源 = 魅族 20
  `/sdcard/Download/...-846-npatched_sign.apk`，2026-10-18 20:49 落盘。
- [V] **包内框架版本**：`assets/npatch/config.json` → `lspConfig.VERSION_CODE=846` /
  `VERSION_NAME=1.0.9`；`assets/npatch/` 条目数 = 5（已修补形态）。
- [V] **包身份**：`com.Takotsubo.AlamobileFormula` / `versionCode=200150` /
  `versionName=8.0.6 Fix 2` / 含 `VIBRATE` 权限 → 与 `VersionGate.SUPPORTED_COEX_FIX=2` 精确匹配。
- [V] **签名可覆盖安装**：`apksigner verify` → Verifies（v3）；
  证书 SHA-256 `b3720382…3a57` 与 845 代**逐位相同**。
- [V] **loader 含 MagicOS 修复**：`libnpatch.so` 命中 `f962cd83` 独有指纹
  `SvcBypass: cannot make [{:#x},{:#x}) readable, skipping scan`，`nm -D` 有 `U mprotect@LIBC`。
- [V] **管理器版本**：设备 `top.nkbe.npatch` → `versionCode=846` / `versionName=1.0.9` /
  `lastUpdateTime=2026-10-18 20:37:59`。
- [V] **旧包已删**：目录现只有标准名 + `原版.apk` 两个，无冗余副本。
- [V] **文档已更新**：`docs/NPATCH_CACHE_CRASH_NOTES.md` 新增 §14（+55 行，585→640）；
  开头速查表加 §14 指针；`CLAUDE.md` NPatch 条目补换算摘要。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`，服务端 `1.0.1`。未打 tag、未发 Release。
- 工作区: 干净（本 handoff 提交前）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ apksigner verify --print-certs "Ala Mobile Pro 8.0.6 Fix 2.apk"
  → Verifies；SHA-256: b37203824e58bfeb33b62cb92d8a3c744fb011b1562923e0fa4e09f63e583a57
$ unzip -p <apk> assets/npatch/config.json | grep VERSION_CODE
  → "VERSION_CODE":846
$ grep -a 'SvcBypass: cannot make' libnpatch.so
  → SvcBypass: cannot make [{:#x},{:#x}) readable, skipping scan
$ git push → e973f64..3c89f30, 3c89f30..98c3431（均成功）
```
（本次会话未改 Kotlin/C，无需 `:app:lint`。）

## 3. 决策与理由
- **846 直接覆盖标准名，旧 845 不保留** [V]——用户原话「删除旧包，谁让你留了」。
  `coex-apk-builder` skill 已规定只长期留「标准名 + 原版」两个。见 memory `no-extra-apk-copies`。
- **版本号换算锚定 +5，不用 +6** [V]——锚点 = `31301d7c` 公开 count 831 ↔ 文档记 836。
  故 846 = `e6a1fd21`（dev HEAD），而非我一度误判的"只到 845"。
- **核对手法优先发布说明 > 数提交 > 偏移硬推** [V]——偏移依赖私有仓提交节奏，随时会变。
  本次误判正是硬推偏移所致，被用户截图当场证伪。
- **验证"修复是否在包里"用该提交独有字符串指纹，不看版本号** [V]——
  `SvcBypass: cannot make …` 只在 `f962cd83` 引入。
- **manager 的 UI 改动不进游戏包** [V]——`assets/npatch/` 只有 loader 与核心 so；
  "关于页显示版本号"要验管理器 APK（`dumpsys package top.nkbe.npatch`）。

## 4. 失败的尝试 — 不要再试
- [X] **给旧 APK 留 `(NPatch 845)` 回滚副本** [V]——用户当场否决「谁让你留了」。
  skill 的「多代时用后缀区分」是**文件名语义**，不是**保留许可**。直接覆盖，旧包删掉。
- [X] **用 `+6` 偏移硬推版本号** [V]——得出「846 只含到 845，要重打成 847」的错误结论，
  被用户甩出 TG 发布说明（三条改动全列）证伪。必须先锚定再推算，或直接数提交。
- [X] 继承死路（详 `.handoffs/20261018170812-handoff.md` §4 及更早）：VPS 上 `apt-get update`
  构建镜像 / `FROM` 复用 alpine 镜像 + COPY glibc 产物 / 轮询等待慢构建 / 真库测试并行跑 /
  `--rerun-tasks` 重编 / 相信 `UP-TO-DATE` / `>=` 放行未知 Fix 号 / 抓地力加地板 /
  `HapticGenerator` / `pm grant VIBRATE` / hook `HybridComponent.EnableOTK` / hook `drsToggle` /
  直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 /
  变暗层插 index 0 / 踏板「单向补送」/ `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / versionCode 方案 806301。

## 5. 已知坑
- ⚠️ **NPatch `VERSION_CODE` = `git commitCount`** [V]——私有仓与公开镜像差 5，
  **版本号不能当代码内容用**；偏移会随私有仓发展变化，新增记录必须重新锚定。
  见 memory `npatch-versioncode-is-commitcount`、`docs/NPATCH_CACHE_CRASH_NOTES.md` §14。
- ⚠️ **`strings` 查不到中文 ≠ 改动没进包** [V]——`strings` 默认只提 ASCII，用 `grep -a` 字节搜。
- ⚠️ **zsh 里 `UID` 是只读内置变量** [V]——赋值静默失败，换 `DID`/`USER_ID`。
- ⚠️ **设备上有两台** [?]——`e98a35cb` = 平板 OPD2413（一加平板 2 Pro），
  `192.168.50.142:5555` = 魅族 20。多设备时 adb 必须 `-s` 指定，否则报 more than one device。
- ⚠️ **`loadingMore` 绝不能放进 `LaunchedEffect` 的 key** [?]（继承）——会永久卡"加载中…"。
- ⚠️ **分页 effect 的 key 必须含数据条数** [?]（继承）。
- ⚠️ **UI 的图标/文案/命名是用户的决策域** [V]（继承）——新增设置项前先问。
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
1. **等用户实机反馈**（围场四功能）：① 长昵称是否省略成 `1234567…`；② 信息卡右侧箭头点击；
   ③ 排行榜下滑自动续 30 条；④ 车手主页四维筛选；⑤ 榜单点行进主页。
   异常先拉 `/sdcard/Android/media/com.Takotsubo.AlamobileFormula/ala_tool.log`。
2. **（可选）846 包分发**：新包已入库待发。发之前确认用户是否需要（不主动提版本号/发版）。
3. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块 `1.0.4 Alpha 4`、
   服务端 `1.0.1` 均未动。
4. **（可选）服务端镜像清理**：VPS 上 40+ 历史镜像占空间；compose 当前指向 `1.0.1-paging`，
   而仓库 `deploy/docker-compose.vps.yml:8` 写的是 `paddock-api:1.0.1`（**两者不一致，待对齐**）。

## 7. 留给用户的开放问题
- `deploy/docker-compose.vps.yml` 的镜像 tag（`1.0.1`）与 VPS 实跑 tag（`1.0.1-paging`）
  不一致，要不要改成实际值？
- 车手主页的「赛季积分 / 赛季胜场」在围场主页仍是「暂无」占位，要不要接服务端数据？
- 分页"到底"目前靠 `entries.size >= total` 静默判断，是否需要显式"没有更多了"提示？
