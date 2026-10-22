# HANDOFF — 读全文再开始干活

生成时间: 2026-10-22T22:00:45+08:00 · Git HEAD: `b51371d`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 `main` @ `b51371d`（已 push）· 服务端 `main` @ `8a2da78`（已 push，本会话未动）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `b51371d`——HEAD 必是本次 handoff 提交，
  其 parent 才是文档记录的 SHA
- 待重探的 [?]: §5 中标记的继承项
- 先读: `native/src/steer_limit_hook.c` 的 `unlock_enabled()`（开关判据范式）

## 1. 当前目标
无进行中的功能切片。本会话修复了**运行时开关关不掉**的结构性缺陷：
四个 hook 的 `g_x_active || g_config.enable_x` 双判据使 config 在 install
后固化、运行时无写点 ⇒ 关开关必须重启游戏。四份已一并修复并构建验证。

## 2. 已验证状态 — 工作实际停在哪
- [V] **报障根因**：用户报「关掉解除最大转向角度限制依然还是开启状态，必须
  重启游戏」。日志铁证：`21:11:43 initSteerLimit unlock=true`（install 固化
  `g_config.enable_*`）→ `21:23:12 setUnlockSteerAngle false`（只清
  `g_unlock_active`）→ `21:25:14` 仍在打 `speed-based limit bypassed`。
- [V] **四处已修**：`steer_limit_hook.c:117` / `drs_hook.c:122,136` /
  `overtake_hook.c:74` / `next_lap_hook.c:92`——全部删掉 `||` 分支，
  `g_config.enable_*` 降级为 install 初值。其中 `next_lap` **默认开**，
  用户从开改关必中招，症状最隐蔽。
- [V] **构建**：`./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease
  -x :app:lintVitalRelease` → BUILD SUCCESSFUL in 2m34s（50 tasks, 16 executed）。
- [V] **产物实证**（不信 Gradle）：APK 内 `lib/arm64-v8a/libala-core.so` md5
  `95b884aa` == `stripped_native_libs/.../libala-core.so`；旧包
  （`/mnt/d/Downloads/QQ/Ala Mobile Tool 1.0.4 Alpha 4.apk`）的 so 是
  `faee5a10`，确已不同；源码 `21:54:53~21:55:18` < APK `21:58:16`。
- [V] **反汇编实证**：四个 proxy 的开关判据处均为**单次 `ldr`**（旧代码是
  两次 `ldr` + `orr`）：`proxy_set_max_steering_angle` `13d1c: ldr w8,[x8,#320]` /
  `proxy_invalidate_lap` `13a4c: ldr w8,[x8,#272]` /
  `proxy_touch_press_otk` `eebc: ldr w8,[x8,#2640]` /
  `proxy_on_drs_state_changed` `eaa4`+`eaf0` 两处均单次。
- [V] **顺带核查（未改动，确认无病）**：`pedal_hook.c` 的 `g_tc_active`/
  `g_abs_active` 是电平量不是配置开关；真正的 TC/ABS 开关由
  `pedal_set_tc_abs()` **直接写 `g_config.enable_tc/enable_abs` 本身** ⇒
  config 即唯一权威。`unlock_hook.c:495` 只在 install 期判定（不支持热切换）。
- [V] **版本号未动**：模块 `1.0.4 Alpha 4` / `104140`；服务端 `1.0.1`。
- [V] **工作区干净**：`git status --short --branch -uall` → `## main...origin/main`。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
BUILD SUCCESSFUL in 2m 34s
50 actionable tasks: 16 executed, 34 up-to-date

$ md5sum <APK 内 so> <stripped_native_libs so>
95b884aa235bd4b4f8239d19d0fe6b20  两处一致

$ aarch64-linux-gnu-objdump -d <带符号 so>  # 四个 proxy 判据处均单次 ldr
```

## 3. 决策与理由
- **单一事实源，而非同步两个变量** [V]——`g_*_active` 成为运行时唯一权威。
  否决方案「在 `set_active()` 里顺手回写 `g_config.enable_*`」，否决原因 =
  那只是同步两个变量、仍留两个可写点，下一个照抄模板的人还会再犯。
- **四个文件一起修，不只修用户报的那个** [V]——同一形态逐字复制在四份里，
  用户只报转向是因为他这三个开关全程没关过。见 memory
  `runtime-switch-single-source-of-truth`。
- **静态实证代替装机验证** [V]——反汇编看判据指令数量足以证明代码形态改对；
  真正的手感验证仍需装机（见 §6）。

## 4. 失败的尝试 — 不要再试
- [X] **`g_x_active || g_config.enable_x` 双判据** [V]——config 在 install 后
  固化、运行时无写点 ⇒ `0 || true` 恒真 ⇒ 功能永远关不掉。判据：运行时有没有
  一个 setter 写得到那个变量？写不到就不能让它参与判定。
- [X] **在 setter 里回写 `g_config.enable_*` 来"修"** [V]——见 §3 否决原因。
- [X] 继承死路（详 `.handoffs/20261022220045-handoff.md` §4 及更早）：
  `PickVisualMedia` / `createChooser` 单目标直通 / `EXTRA_INITIAL_INTENTS` /
  `ResolveInfo.loadLabel()` / Kotlin 块注释写 `image/*` / `color = Color(...)` 做金色流光 /
  `loadingMore` 写正常路径末尾 / 0/1/2 固定分栏 / VPS 上 `sed` 改 compose /
  两个 BlurredBar 共用 backdrop / `listState.scrollToItem(0)` 放请求前 /
  `unzip -p | strings | grep`（中文需 `grep -a` 直接搜 dex）/ 相信 `UP-TO-DATE` /
  `>=` 放行未知 Fix 号 / 抓地力加地板 / hook `HybridComponent.EnableOTK` /
  hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` /
  编辑层尺寸=控件尺寸 / 变暗层插 index 0 / `coroutineScope{}` 内多源竞速 /
  Remote Preferences `remove()` / 全局挂 `tnum` / `FontFamily.Monospace` /
  用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底 / 给旧 APK 留回滚副本 /
  用 `+6` 偏移硬推 NPatch 版本号 / 改完文案就构建装机（未先确认）/
  把 UI 文案表格贴在正文里让用户自由回复 / 按「每维度获胜圈」筛缺失配置 /
  按 `lap_id` 快照实现屏蔽（应用谓词）。

## 5. 已知坑
- ⚠️ **native 运行时开关必须单一事实源** [V]——见 CLAUDE.md Key Code Conventions
  首条与 memory `runtime-switch-single-source-of-truth`。新增 hook 时
  `g_config.enable_*` 只作 install 初值。
- ⚠️ **隐藏/屏蔽类功能：路径全覆盖 + 范围对 + 判据形态对** [V]（继承）——见
  memory `data-filter-must-cover-all-recalc-paths`。
- ⚠️ **UI 的图标/文案/命名是用户的决策域，必须用 AskUserQuestion 逐条确认** [V]（继承）——
  见全局记忆 `ask-before-designing-ui`。
- ⚠️ **VPS compose 必须带 `-f docker-compose.vps.yml`，切镜像只改 `.env`** [V]（继承）——
  见 memory `paddock-deploy-chain-local-build` / `paddock-compose-must-match-vps`。
- ⚠️ **本机已有 docker，真库测试直接本地起临时库** [V]（继承）——见 memory
  `sql-test-must-run-serial-and-temp-db`。
- ⚠️ **中文文案验证必须直接 grep dex 字节** [V]（继承）——`unzip -p | strings | grep` 会假阴性。
- ⚠️ **`/handoff` 的 push 无需额外授权，且必须做完** [V]（继承）——报告里出现「待 push」= 违规。
- ⚠️ **选图/选文件必须自绘选择器** [V]（继承）——见 memory `android-file-picker-no-gms`。
- ⚠️ **miuix `Text` 的 `color` 覆盖 `style.brush`** [V]（继承）——见 memory
  `miuix-text-color-overrides-brush`。
- ⚠️ **协程在途标志必须 `try/finally` 复位** [V]（继承）——见 memory
  `coroutine-flag-needs-try-finally`。
- ⚠️ **设备操作必须事先获许可** [V]（继承）——本会话未操作任何设备。
- ⚠️ **名次必须 `row_number()` 非 `rank()`** [V]（继承）——见 memory `rank-row-number-not-rank`。
- ⚠️ **Compose `maxLines` 与 `overflow` 正交** [V]（继承）——见 memory
  `compose-maxlines-overflow-orthogonal`。
- ⚠️ **LazyList 分页哨兵值 `?: -1` 参与 `>= total - N` 恒真** [V]（继承）——见 memory
  `lazylist-pagination-sentinel-trap`。
- ⚠️ **多设备时 adb 必须 `-s`** [V]（继承）——`e98a35cb` = 一加平板 2 Pro（OPD2413），
  `192.168.50.142:5555` = 魅族 20。
- ⚠️ **VPS docker 需 `sudo`** [V]（继承）。**`/v1/health` 可用、裸 `/health` 是 404** [V]（继承）。
- ⚠️ **zsh 下 `rm -rf dir/*` 遇空目录会 `no matches found` 整条命令失败** [V]——
  用 `rm -rf dir && mkdir -p dir` 替代。
- ⚠️ **`carModifier.isTiltSteer`(0x1C4) 的判定是反的** [?]（继承）。
- ⚠️ **本机（MEIZU 20）不是振感类改动的验收环境** [?]（继承）。
- ⚠️ **`versionName`/`versionCode` 锁不住构建时间** [?]（继承）——看日志环境块的构建指纹。
- ⚠️ **旧版（9/14 前）有「卡加载」bug** [?]（继承）——判据 = 日志含
  `Pushed game logs via chunked broadcast`。
- ⚠️ **改 `SUPPORTED_COEX_FIX` 是发版动作** [?]（继承）。
- ⚠️ **`Fallback` 振感路径仍是死代码路径** [?]（继承）。
- ⚠️ **NPatch 843 起仍有边界：管理器晚于游戏启动时不会自动重连** [?]（继承）。
- ⚠️ **路肩（primitive）全机型正常，抓地力（连续波形）全机型异常** [?]（继承）。
- ⚠️ **共存版 versionCode 绝不可与游戏原值脱钩** [?]（继承）。
- ⚠️ **对话纪律** [?]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本
  4 调查日志先对齐「几次」计数 5 修 UI 时序先拉触摸时间线 6 日志判不了的结论不要用日志反驳用户体感
  7 别在用户锁屏时操作设备 8 动用户设备状态前先说明 9 不要主动提版本号 10 跑长耗时/带 timeout
  的命令前必须先说明 11 对外交流不用内部编号，用现象描述 12 数据还有没有用由需求方判断，不替对方筛
  13 用户要的 APK 先看 `/sdcard/Download/` 14 未经许可不得操作设备（最高优先级）。

## 6. 下一步（有序）
1. **（待你决定）装机验证本次修复**：装 `app/build/outputs/apk/release/app-release.apk`
   到设备，进游戏后拨动这四个开关的任意一个，确认立即生效（不需重启）。这是唯一
   能证明修复对用户有效的环节——反汇编只证明代码形态对。
2. **（待你决定）车手主页「隐藏已屏蔽记录」开关**：该用户仍有 21 条红标
   （142 条圈里），要不要在 `DriverScreen` 的筛选里加开关？
3. **（待你决定）VPS 清理**：`~/paddock` 已积累多个历史镜像 + 备份 `.tar.gz`
   + `.env.bak-*`。清理前先确认当前实跑是 `1.0.1-autohide`。
4. **（待你决定）大奖赛页的产品形态**：当前是占位文案。
5. **（条件式，用户发话才做）发版**：先问版本号 → tag → CI。当前模块
   `1.0.4 Alpha 4`、服务端 `1.0.1` 均未动。

## 7. 留给用户的开放问题
- 本次修复是否要先给报障用户一个热修包？还是并入下个常规版本？
- 圈速卡的「间隔 2 字符」是否合适？（两轮前遗留，未回答）
- 「全服最快圈数量」显示跨版本累计数。是否需要在界面上标注"含历史版本"？
- 大奖赛页最终要做什么？当前只有占位。
- 排序方式是否需要记忆（下次进页面保持上次选择）？
- **屏蔽后积分可能上涨**（金标成绩顶上会吃加成）。界面上目前不提，是否需要提示？
- 屏蔽现在是「持续生效的开关」——开启后，之后每次旧版模块上传缺失圈都会被
  自动屏蔽且**无任何提示**。要不要给用户通知/日志可见性？
