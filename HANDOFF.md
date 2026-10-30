# HANDOFF — 读全文再开始干活

生成时间: 2026-10-30T23:00:00+08:00 · Git HEAD: `0518f43`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `0518f43`（已 push）· 服务端 `main` @ `04587d1`（已 push，线上已部署）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `0518f43`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: [`.github/workflows/sync-lsposed-mirror.yml`](.github/workflows/sync-lsposed-mirror.yml)
  §99-112（versionName 提取，本次修复处）

## 1. 当前目标
无进行中的功能切片。本会话完成三件事：**README 整体重构（473→155 行）**、
**v1.0.4 正式版发版（含 LSPosed 镜像同步）**、**修复镜像同步的 CI 回归**。

## 2. 已验证状态 — 工作实际停在哪
- [V] **README 重构已发版**：`e59ea83`（`README.md` 473→155 行 / 40K→8K）。
  8 节结构：标题徽章 / 免责声明 / 演示（4 GIF 全保留，2×2 排布）/ 功能（6 行分类表）/
  安装（Root + NPatch）/ 快速上手（三道门）/ 已知问题 / 构建·文档·社区·许可证。
  删掉：技术架构树、配置项全表、版本历史折叠块、「开发中」整节。
- [V] **v1.0.4 正式版已发布**：版本 `1.0.4` / versionCode **104300**（`build.gradle.kts`
  与 `module.prop` 双处一致）。Tag `v1.0.4`，源 Release 已建，APK
  `Ala.Mobile.Tool.v1.0.4.apk`（11.4 MB），`isPrerelease=false`。
- [V] **LSPosed 镜像已同步**：`104300-1.0.4` @ `Xposed-Modules-Repo/tools.alamobile.mod`，
  body 为手工 Notes，README 已同步含 `**Source URL:**` 尾行。
- [V] **CI 回归已修复**：`65dd0cc`——`sync-lsposed-mirror.yml:107` 的松匹配
  `grep 'versionName'` 改为 `grep -oE 'versionName = "[^"]+"'`。
- [V] **固化产物已提交**：`0518f43`（CLAUDE.md 版本命名节补一条红线）。
- [V] 工作区干净（两仓均已 push）。`README.md.bak` 已删除（不进仓库）。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
Lint found 67 warnings, 4 hints (and 2 errors and 14 warnings filtered by baseline)
BUILD SUCCESSFUL in 1m 59s        ← 0 error

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 1m 47s

$ aapt2 dump badging app-release.apk
package: name='tools.alamobile.mod' versionCode='104300' versionName='1.0.4'

$ unzip -p <apk> META-INF/xposed/module.prop
version=1.0.4 / versionCode=104300
```

## 3. 决策与理由
- **README 走极简版而非中等版**（用户选定）[V]——现稿 473 行里约 200 行是配置项全表与
  技术架构树，那是 `docs/` 的职责；版本历史归 GitHub Releases。
- **版本徽章按 1.0.4 正式版写**（用户选定）[V]——发版同时把代码版本号从
  `1.0.4 Beta 3 (104230)` 升到 `1.0.4 (104300)`。
- **4 个演示 GIF 全保留**（用户选定）[V]——虽然合计 54 MB，但排成 2×2 表格后不占纵向空间。
- **免责声明提到最顶部** [V]——面向普通玩家的法律免责文本，比塞在末尾折叠块更合适。
- **「开发中」整节删除** [V]——其中自动 DRS / 自锁式超车 / 禁止删除下一圈在 1.0.4
  已全部转正（`ConfigurePagerMiuix.kt:379/393/407` 实证），只剩手动换挡仍禁用，并入「已知问题」。
- **镜像 tag 422 的根因是正则而非 token** [V]——见 §4 第一条。

## 4. 失败的尝试 — 不要再试
- [X] **把 `gh api .../jobs/<id>/logs` 里的 `echo "::error::TARGET_REPO_TOKEN secret is not set"`
  当成错误输出**（本次会话犯的错）：那是 `run:` 块脚本源码的整段回显。据此断定"缺 secret"
  让用户去补 token，用户当场反问「以前都能发，现在怎么让我补」。真实错误只有
  `HTTP 422: Validation Failed / tag_name is not well-formed`。
  见全局记忆 `gh-actions-log-shows-script-source`。**日志里有这行 ≠ 这行被执行了。**
- [X] **把完整 README 稿贴进终端让用户审阅**（本次会话犯的错）：终端不渲染 Markdown，
  用户回「你先写，你在终端输出文字没格式我怎么看」。见全局记忆
  `write-file-not-terminal-preview`。
- [X] **CI 里用 `grep 'versionName'` 松匹配提取版本名**：会命中 `build.gradle.kts`
  顶部注释里的散文行（无引号），`sed` 替换不掉就原样输出整段中文 ⇒ 镜像 tag 非法。
- [X] 继承死路（详 `.handoffs/20261030225940-handoff.md` §4 及更早）：
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
  `saveOverlayPosition` 刷新 `saved_at`。

## 5. 已知坑
- ⚠️ **往 `build.gradle.kts` 顶部加注释时，不要写含 `versionName`/`versionCode`
  字样且形似赋值的散文行** [V]——CI 的版本提取靠文本匹配，一行散文就能打穿镜像同步。
  改注释后跑一遍：`grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1`。
- ⚠️ **`gh run view --log` 只返回主 job 日志**，失败子 job 要用
  `gh api repos/<o>/<r>/actions/jobs/<job_id>/logs` [V]——且必须剥颜色码
  `sed 's/\x1b\[[0-9;]*m//g'` + 排除 `[36;1m` 行，否则读到的全是脚本源码。
- ⚠️ **镜像 release 列表顺序在单 tag 手动触发时不保证正序** [?]——本次 `v1.0.4` 排在
  `v1.0.3` 之前（按创建时间倒序）。工作流设计是按 versionCode 升序逐个创建，
  下次全量同步（`tag` 留空）应恢复正序，未实测。
- ⚠️ **`sort=time_desc/asc` 排的是上传时间（`created_at`），不是圈速** [V]——
  要按圈速排序用 `lap_desc`/`lap_asc`。判「是否个人最快」必须查 `best_laps` 表。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算才会更新** [V]。
- ⚠️ **魅族 20 的无线调试 IP 由 DHCP 分配会变** [V]——端口 5555 固定，IP 会变。
- ⚠️ **产物验证的中文假阴性有两条独立来源** [V]（继承）——`strings`（ASCII-only）
  与 `baksmali`（`\uXXXX` 转义）。判据：搜中文就用 `grep -a` 打原始字节。
- ⚠️ **参数化格式串无法用 `strings` 验证** [V]（继承）——必须走三层对拍。
- ⚠️ **"有输出 ≠ 读对了"** [V]（继承）——`LAPsetup` 有日志但读的是默认构造对象。
- ⚠️ **`grep` 零命中 ≠ 不存在** [V]（继承）——先确认日志/搜索覆盖范围。
- ⚠️ **冠军赛路径未验** [?]（继承）——代码层已确认（`lap_hook.c:564-595`），实跑未测。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）——影响 `created_at` 排序；未修复。
- ⚠️ **native 日志 2MB 滚动截断太快** [V]（继承）——跑两圈就丢第一圈证据。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 判定是反的** [?]（继承，未重新反汇编）。
- ⚠️ **多设备时 adb 必须 `-s`** [V]——`e98a35cb` = 一加平板 2 Pro，
  `192.168.50.123:5555` = 魅族 20（Flyme，Android 16，NPatch 850）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 **数据展示口径（精度/小数位/间距）先问再定**
  17 **下断言前先取证，没有全量数据就不下"这是最快/最好"这类结论**
  18 **大段文档写文件不贴终端**（见全局记忆 `write-file-not-terminal-preview`）
  19 **日志里有某行 ≠ 该行被执行了**（见 `gh-actions-log-shows-script-source`）

## 6. 下一步（有序）
1. **（待你决定）给用户发版通知**：v1.0.4 已发布，含 5 条 Breaking Changes
   （仅共存版 / 精确 Fix 3 / NPatch 850 / 必须登录围场 / 模块落后被拦）。
   QQ 群公告文案需要写吗？
2. **（待你决定）「应用调教」功能**：调教数据已能取到、已入库、已能在详情弹窗展示，
   回写游戏的路径未做。
3. **（待你决定）冠军赛路径补验**：跑一场冠军赛，看是否出现 `LAPsetup: no setup object`。
4. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- 镜像 release 列表顺序问题是否需要修？（单 tag 触发时 `v1.0.4` 排在 `v1.0.3` 前）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承，未答复）
- 是否要按调教做榜（如「同一赛道同一调教分组」）？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
- 历史虚高 `pedal_mode` 成绩是否回溯？（继承）
- NPatch 门控要求 850，846 及更早用户会被拦，是否先群里通知？（继承）
