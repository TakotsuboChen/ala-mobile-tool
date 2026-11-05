# HANDOFF — 读全文再开始干活

生成时间: 2026-11-05T22:38:00+08:00 · Git HEAD: `bd782c4`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `bd782c4`（工作切片 `c7ff86c` + 固化切片 `bd782c4`）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `bd782c4`——HEAD 必是本次 handoff 提交（步骤 7），其 parent 才是文档记录的 SHA
- 待重探的 [?]: 见 §5 标 `[?]` 的条目
- 先读: [`docs/notes/FPS_UNLOCK.md`](docs/notes/FPS_UNLOCK.md)（本次主要产出）

## 1. 当前目标
无进行中的功能切片。本会话完成：**新增「强制全局 120 FPS」开关**，
实机验证通过（冷启动直接进主界面即高帧率），已装机。

## 2. 已验证状态 — 工作实际停在哪
- [V] **工作切片 `c7ff86c`**：新增 `native/src/fps_unlock_hook.c/.h`，
  hook `Application.set_targetFrameRate`（RVA `0x3257B6C`），开关开启时把入参
  30/60 改写为 120。11 个文件，详见 commit message 与 `docs/notes/FPS_UNLOCK.md` §3。
- [V] **固化切片 `bd782c4`**：`docs/notes/FPS_UNLOCK.md`（五节骨架）+
  CLAUDE.md Files to Know + `docs/README.md` §5 索引。
- [V] **lint**：`./gradlew :app:lint` → `BUILD SUCCESSFUL in 2m 12s`，
  67 warnings / 0 error（全是既有基线）。
- [V] **构建**：`./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease
  -x :app:lintVitalRelease` → `BUILD SUCCESSFUL in 1m 53s`，APK 11,425,343 B。
- [V] **产物实证**：APK `22:25:24` > 源码 `22:21:09`；
  `fps_unlock: applied targetFrameRate`（native）命中 1；
  `Early fps-unlock hooks installed`（dex）命中 1；
  「强制全局 120 FPS」「可能增加发热与耗电」各命中 1；
  `FrameRateIcon` 的 path `M22,11H12a1,1,0,0,0-1,1V22` 命中 1。
- [V] **已装机**：`MEIZU_20`（192.168.50.142:5555），`install -r` Success。
- [V] **实机验证通过**：用户确认「冷启动直接进主界面就是高帧率，不用先进赛道」。
- 工作区: 干净，`main...origin/main` 已同步。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint
BUILD SUCCESSFUL in 2m 12s
Lint found 67 warnings, 4 hints (2 errors/14 warnings filtered by baseline)

$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 1m 53s

$ adb install -r app/build/outputs/apk/release/app-release.apk
Performing Streamed Install
Success
```

## 3. 决策与理由
- **hook 改「入参」而不是「写字段」** [V]——`odometerHandler.OnDestroy` 退出
  赛道时会调 `savePlayerSettings`，`BuildPlayerSettings_V2` 把 `sV` 全部字段
  写回 `saveFile`（含 `+0x20`）⇒ 写字段会永久污染用户存档；且移动端下拉只有
  2 项，`set_value(2)` 越界会让设置页显示与行为不一致。否决：写
  `sV->_graphicsFramerate`（三个缺陷无法规避）。
- **只匹配 30/60，不用区间判据** [V]——未来若出现 90 档，白名单不会误改；
  300（demo 死代码）与 `-1`（原生刷新率）一律透传。
- **走早期 deferred 块 + 装好后补设一次** [V]——见 §4 第一条，这是踩过的坑。
- **默认关闭** [V]（用户上一轮定案）——本作 `DynamicResolution` 是禁用状态
  （`Enable()` 全 `.so` 零调用者），没有自动降分辨率兜底，弱设备强推 120
  可能 40~50 横跳、反而更差。
- **图标用用户提供的 SVG** [V]——`viewBox="1 1 22 22"` 原点非零，走
  `viewportOriginX/Y=1` 平移（不要改 path 坐标，见 CLAUDE.md CustomIcons 条）。

## 4. 失败的尝试 — 不要再试
- [X] **把 `initFpsUnlock` 放 15s 延迟块**（第一版实现）——首次启动那次 30 fps
  在 15s 块之前就设完了，hook 漏掉。实机现象「刚开游戏进主界面锁 30，玩过再回
  主菜单才是高帧率」。⚠️ 这**不是**模块引入的问题：用户原生设 60 时同样如此。
  修法 = 提前安装 + 装好后补调一次，**改回调逻辑无效**（回调压根没被调用）。
- [X] **直接写 `GlobalVariables.sV->_graphicsFramerate = 2`**——存档永久污染
  （`odometerHandler.OnDestroy` 隐式存盘）+ UI 错乱（下拉只有 2 项）+
  被 `acceptNewValues` 冲掉。三个缺陷都无法规避。
- [X] **先 hook `setGameAndGraphicsVariables` 再裸调一次**——多一层 methodInfo
  依赖、调用次数变两次；唯一优势场景（值不为 0/1/2）在移动端不可达。
- [X] **只改 Android 层 `preferredRefreshRate` 代替改 IL2CPP**——两层正交，
  屏幕 120Hz 而 `targetFrameRate` 仍 60 时每帧重复显示两次，流畅度不变。
- [X] **NDK `ANativeWindow_setFrameRate`**——Unity 已通过 `dlopen`+`dlsym`
  实现并在 `SwappyGL::swap` 里调用，重复无益。
- [X] 继承死路（详 `.handoffs/20261105223736-handoff.md` §4 及更早）：靠业务日志
  判断模块是否注入 / `Mailbox write OK` 当游戏侧通道证据 / 「不崩溃+功能全无」
  直接判 Bug C / `@{围场用户名}` 做 TG 提及降级 / 无身份时"只显示名字不加 @" /
  容器加 `HTTP_PROXY` 不配 `NO_PROXY` / 修改已应用的迁移文件 / `text_mention`
  当 `@username` / `teloxide` / 共用 QQ 的 `SendJob` / 启动时自动 `setWebhook` /
  本地共用生产 token / `getUpdates` 探活 / `try_send` 入队 / `handle_*` 返回
  空串表示静默 / 整圈五维全填 0 / `strings | grep` 搜 APK 中文 / `baksmali` 后
  grep 中文 / 条件调用 `remember` / `PickVisualMedia` / `createChooser` /
  `ResolveInfo.loadLabel()` / 相信 `UP-TO-DATE` / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 全局挂 `tnum` /
  `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 /
  `g_*_active || g_config.enable_*` 双判据 / 扫描 `this` 前 N 字节找候选指针 /
  `saveOverlayPosition` 刷新 `saved_at` / `scrollToItem` 跨数据集归位 /
  `firstVisibleItemIndex > 0` 才归位。

## 5. 已知坑
- ⚠️ **首次启动那条更早的 30 fps 设置路径仍未反汇编定位** [?]——功能上已被
  「安装后补设」覆盖，但如果那条路径在运行期会再触发，补设可能被覆盖。
  目前实测未见复发。见 `docs/notes/FPS_UNLOCK.md` §5。
- ⚠️ **hook 目标只有 56 字节且前 4 条含 `adrp`** [?]——PC 相对寻址，
  ShadowHook 需重定位才能装。本次安装成功，但升版后要重验
  （失败码从 `shadowhook_get_errno()` 取）。
- ⚠️ **只解决软件渲染上限** [V]——游戏窗口 `preferredRefreshRate` = 0（无偏好），
  屏幕停 60Hz 时目标 120 无体感改善。本次目标设备已能跑满，但换设备需重测。
- ⚠️ **`LogExporter` 对「段缺失」是静默的** [?]（继承，未做）——`foundAny` 只要
  模块日志读到就是 `true`，游戏进程日志缺失完全不提示。**待改**：每段显式输出
  `[未找到：<路径>]`。
- ⚠️ **`baksmali` 输出目录别放 `/tmp`** [V]（继承）——本机 `/tmp` 是 4.9G tmpfs。
- ⚠️ **`grep -a` 与逐 dex 检查** [V]——`classes*.dex` 通配在 zsh 下可能零匹配而
  静默返回 0；APK 内只有 `classes.dex` 一个。
- ⚠️ **`const val Long` 的 RVA 不能靠 grep 验证** [V]——被内联成二进制常量。
- ⚠️ **NPatch 的 `android.util.Log` 只进 logcat 不落文件** [V]（继承）。
- ⚠️ **Bug E 不需要 logcat，Bug C 必须** [V]（继承）——先数导出日志段数。
- ⚠️ **VPS 直连 `api.telegram.org` 被 DNS 污染** [V]（继承）；**`NO_PROXY` 不可省** [V]（继承）。
- ⚠️ **已应用的迁移文件不能改** [V]（继承）。
- ⚠️ **Telegram 隐私模式对已存在的群不立即生效** [V]（继承）；**`entity` 偏移是 UTF-16 code unit** [V]（继承）。
- ⚠️ **`tg_broadcast_chats` 与 `bot_broadcast_groups` 是两套独立配置** [V]（继承）。
- ⚠️ **`@username` 补录需要用户先发过言或点「检查状态」** [V]（继承）。
- ⚠️ **`edited_message` 当前忽略** [V]（继承）。
- ⚠️ **`bot_known_groups` 4 群 vs `bot_broadcast_groups` 2 群** [?]（继承，疑正常）。
- ⚠️ **`reqwest` 对 `NO_PROXY` 的 CIDR 语法未单独验证** [?]（继承）。
- ⚠️ **supergroup 迁移链路未实机触发** [?]（继承）。
- ⚠️ **QQ markdown 通道对纯 `***`/`# ` 的渲染未真机验证** [?]（继承）。
- ⚠️ **魅族 20 的 IP 会来回变** [V]（继承）——`.142`/`.123` 都出现过。
- ⚠️ **覆盖重发后已装用户收不到更新提示** [V]（继承）——按 versionCode 比较。
- ⚠️ **`sort=time_desc/asc` 排的是上传时间** [V]（继承）。
- ⚠️ **`best_laps` 是物化快照，回填 `laps` 后必须重算** [V]（继承）。
- ⚠️ **VPS 系统时钟慢 3 分钟** [V]（继承）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时
  3 装机前验设备 APK 版本 4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线
  6 日志判不了的结论不要用日志反驳用户体感 7 别在用户锁屏时操作设备
  8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout 的命令前必须
  先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）
  15 已确认的信息不要重复问 16 数据展示口径（精度/小数位/间距）先问再定
  17 下断言前先取证 18 大段文档写文件不贴终端 19 日志里有某行 ≠ 该行被执行了
  20 用户说「我的设备没事但别人的可能有问题」时，不能因为本机复现不了就否定

## 6. 下一步（有序）
1. **（待你决定）刷新率那一层**：若在别的设备上 120 不生效，才需要做
   `WindowManager.LayoutParams.preferredRefreshRate`。动手前必须先测
   `Display.getRefreshRate()` 实际值 + `logcat | grep -i swappy`。
   见 `docs/notes/FPS_UNLOCK.md` §5。
2. **（待你决定）修 `LogExporter` 的「段缺失静默」**：每段显式输出 `[未找到：<路径>]`。
   独立小改动，与任何功能无关。
3. **（待你决定）存量用户的 `@username` 补齐**（服务端）。
4. **（待你决定）Telegram 播报目标群提示**：管理端缺「勾了 QQ 不等于 TG 也播报」说明。
5. **（待你决定）服务端 1.0.2 发版**——版本号是你的决策域。
6. **（待你决定）「应用调教」功能**：数据已能取到、已入库、已能展示，回写游戏未做。
7. **（待你决定）历史研究成果回收**：`docs/README.md` §7 的 8 个主题表。

## 7. 留给用户的开放问题
- 换设备（不同屏幕刷新率）时 120 是否仍生效？要不要现在补刷新率那层？
- 本次 120 FPS 要不要进下一个版本的 Release Notes？
- 弱设备上开 120 会不会反而更差？要不要加个提示文案？
- 首次启动那条更早的 30 fps 路径要不要继续挖？（功能已覆盖，根因未知）
- 覆盖重发/版本相同导致存量用户收不到更新提示，是否需要升 Fix 号？（继承）
- `position saved` 每帧落盘要不要改成松手才落盘？（继承）
- 是否要按调教做榜？（继承）
- `selectedSimples`（0x10，int[]）语义未解析——要不要一起读？（继承）
- 负前束/正前束的 UI 表述未核对（实测 `toe=-0.0326/0.0884`）。（继承）
- 50% 踏板占比阈值是否合适？（继承）
