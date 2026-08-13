# HANDOFF — 读全文再开始干活

生成时间: 2026-08-13T21:30:00+08:00 · Git HEAD: `9b5c2f3`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `9b5c2f3` (2026-08-13)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `9b5c2f3`；变了说明快照可能过期
- 待重探的 [?]: 无
- 先读: `CLAUDE.md` + 本文件

## 1. 当前目标
**UI 流畅性修复完成。** 底栏快速切换卡顿问题已通过照搬 KernelSU 流畅性方案解决，真机验证"非常流畅"。完成定义：底栏快速来回切换不再卡死、滑块切换不掉帧 — 已满足。

## 2. 已验证状态 — 工作实际停在哪
- [V] **底栏卡顿修复已提交并推送**——`git log` HEAD=`9b5c2f3`，commit message `perf(ui): 修复底栏快速切换卡顿，对齐 KernelSU 流畅性方案`，2 文件 +128/-54。
- [V] **Release APK 构建通过**——`./gradlew :app:assembleRelease` → BUILD SUCCESSFUL in 1m 53s，产物 `app/build/outputs/apk/release/app-release.apk`。
- [V] **真机验证通过**——用户反馈"可以，非常流畅"。设备 `381QYFCN22B9A`，`adb install -r` 成功。
- 工作区: `git status` 干净，无未提交改动。

### 测试/build 输出（本次交接 run）
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 53s
adb install -r app-release.apk → Success
用户反馈: "可以，非常流畅"
```

## 3. 决策与理由
- **P0 根因：`OverviewPage.kt:129` 的 `remember { mutableStateOf(LsposedStatus.evaluate(context, awaitService = true)) }` 同步阻塞主线程最多 3 秒** [V]——`evaluate(awaitService=true)` 内有 `while + Thread.sleep(100)` 轮询循环（`LsposedStatus.kt:84-98`），配合 `beyondViewportPageCount=1`，切到非相邻页时 `OverviewPage` 被销毁，切回来时 `remember{}` 重新执行 → 每次切换冻结 3 秒。修复：`remember` 只用 `awaitService=false` 快速返回，`LaunchedEffect + withContext(IO)` 异步升级到准确值。否决方案：直接删 `awaitService` 轮询——会丢失 LSPosed daemon 异步绑定的兜底，首次进配置页可能误判未激活。
- **P0 次因：`ModConfig.write` 在 main looper Handler 上执行** [V]——`saveHandler.postDelayed` 看似异步，但 runnable 跑在 main looper。`ModConfig.write` 同步做 JSON 序列化 + Binder IPC + 文件写 + 广播，与底栏动画帧竞争主线程。修复：改用 `saveScope.launch { delay(300); withContext(IO) { ModConfig.write(...) } }`。否决方案：用 HandlerThread——额外线程开销且与 Compose 协程模型不统一。
- **P1：两个 `LaunchedEffect` 监听 `settledPage` 和 `currentPage` 重复触发 `syncPage()`** [V]——两者底栏切换时都变，`syncPage()` 被调两次，每次写 `mutableIntStateOf(selectedPage)` 触发所有底栏 `NavigationBarItem` 重组两遍。修复：删 `currentPage` 的 `LaunchedEffect`，对齐 KernelSU `MainActivity.kt:287-289`。
- **P2：引入 `contentReady` 延迟加载** [V]——冷启动时三页同时 compose 开销大，`beyondViewportPageCount = if (contentReady) 1 else 0`，首次 `settledPage` 稳定后才放开。对齐 KernelSU `rememberContentReady`。
- **P2：`animateToPage` 从 `tween+animateScrollBy` 改为 `springAnimateToPage`** [V]——KernelSU `BottomBar.kt:69-112` 的 `scroll + Animatable + spring spec`（stiffness=322.2, dampingRatio≈0.9），`MutatePriority.UserInput` 抢占手势优先级，快速连续点击时能立即打断旧动画。tween 的 EaseInOut 在快速切换时显得机械。
- **每个页面各自 `rememberBlurBackdrop` 是 miuix 标准用法，保持现状** [V]——核对 KernelSU `HomeMiuix.kt:88` 等页面，每个页面都各自创建 backdrop，`beyondViewportPageCount=1` 时同时存活的 backdrop 数量有限（2 个），不是主要卡顿源。

## 4. 失败的尝试 — 不要再试
- **在 `remember{}` 里调 `evaluate(awaitService=true)`** [V]——`remember` 在 composition 期间同步执行，`awaitService=true` 的 3 秒轮询循环直接阻塞主线程。不要再把重型同步操作放 `remember{}`。
- **用 `Handler.postDelayed` 做"异步"写配置** [V]——runnable 实际跑在 main looper，`ModConfig.write` 的 JSON 序列化+Binder IPC+文件写+广播全在主线程。Compose 项目应该用 `rememberCoroutineScope() + withContext(IO)`。
- **（前向搬运 M35）只改 smali 不清 stamp 元数据** [V]——8.0.4 共存版第一版只 patch 了 LicenseClient/LicenseActivity smali，但 manifest 保留 stamp.type/CHECK_LICENSE/stamp-cert-sha256 → 仍跳 Play。必须 smali patch + 清 stamp 元数据全做。
- **（前向搬运 M35）apktool 默认 doNotCompress 不够** [V]——apktool b 产出 APK 的 assets 文件被 Defl 压缩 → Unity 黑屏。必须手动补全 apktool.yml doNotCompress 列表。
- **（前向搬运 M34）所有 Java 层绕过 pairip 的思路** [V]——checkLicense→return-void、performLocalInstallerCheck→return true、LicenseActivity→空壳、删 LicenseActivity + licensecheck smali、删 splits0/stamp/derived metadata 单独做——**单独做任一项都不够，必须全做**。
- **（前向搬运 M33-M14 死路）** XSharedPreferences、openRemoteFile 跨进程、ContentProvider 跨进程 NPatch、getRunningTargets 判激活、gh release edit --body-file——均不再试。

## 5. 已知坑
- **⚠️ NPatch 需要管理器唤醒注入** [V]——清数据/冷启动后直接开游戏不注入，需先开 NPatch 管理器。见 memory `npatch-needs-manager-wakeup`。
- **⚠️ NPatch 配置同步依赖管理器进程** [V]——NPatch 无 daemon，经 `content://top.nkbe.npatch.remote` ContentProvider 桥接。见 memory `npatch-config-sync`。
- **⚠️ apktool 2.7.0 doNotCompress 丢失** [V]——反编译 split APK 合并的单 APK 时 doNotCompress 不完整。见 `coex-apk-builder` SKILL.md 阶段 7。
- **⚠️ /tmp 是 tmpfs 只有 7.3G** [V]——解压多个 500MB+ APK 会占满。工作目录放项目 `build/`。
- **⚠️ coex APK 用 CN=AlaMobileTool 签名，8.0.0 coex 用 CN=Mod** [V]——签名不同，升级需先卸载。

## 6. 下一步（有序）
1. **等待用户 NPatch 注入 + 自签 + 分发后的终端用户反馈**——如果终端用户报问题，先查 logcat 有没有模块注入日志（没有 = NPatch 没注入，提醒先开管理器）。
2. **如果用户要求新功能或修 bug**——正常开发流程，模块代码在 `app/src/main/kotlin/tools/alamobile/mod/`。
3. **如果 Ala Mobile 发布新版本**——按 `coex-apk-builder` SKILL.md 第 11 节「版本更新适配清单」走。

## 7. 留给用户的开放问题
- NPatch 路径下模块的所有功能（踏板/换挡/DRS/解锁/音乐替换）是否全部正常？用户只确认了"适配成功"，未逐一验证功能。
- 是否需要把 8.0.4 共存版 + NPatch 注入 + 自签的完整产物发布到 GitHub Release？
