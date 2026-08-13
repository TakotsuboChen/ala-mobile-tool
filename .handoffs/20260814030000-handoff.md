# HANDOFF — 读全文再开始干活

生成时间: 2026-08-13T22:35:00+08:00 · Git HEAD: `4ea4ce0`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `4ea4ce0` (2026-08-13)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `4ea4ce0`；变了说明快照可能过期
- 待重探的 [?]: 无
- 先读: `CLAUDE.md` + 本文件

## 1. 当前目标
**切页掉帧修复完成。** 底栏快速切换卡死（M36 已修）+ 切页期间掉帧（本次修）两层问题都已解决，真机验证"非常流畅"。完成定义：底栏快速来回切换满帧、切页动画期间无可见掉帧 — 已满足。

## 2. 已验证状态 — 工作实际停在哪
- [V] **切页掉帧修复已提交并推送**——`git log` HEAD=`4ea4ce0`，commit message `perf(ui): 全盘照搬 KernelSU backdrop 分层，修复切页掉帧`，2 文件 +34/-18。
- [V] **Release APK 构建通过**——`./gradlew :app:assembleRelease` → BUILD SUCCESSFUL in 1m 42s，产物 `app/build/outputs/apk/release/app-release.apk`。
- [V] **真机验证通过**——用户反馈"可以，非常流畅"。设备 `381QYFCN22B9A`（骁龙 8 Gen 2），`adb install -r` 成功。
- [V] **gfxinfo 数据**——Janky frames 8.04%（首次），切页帧分布 50th=10ms / 90th=19ms / 99th=53ms；用户实测流畅。
- 工作区: `git status` 干净，无未提交改动。

### 测试/build 输出（本次交接 run）
```
./gradlew :app:assembleRelease → BUILD SUCCESSFUL in 1m 42s
adb install -r app-release.apk → Success
用户反馈: "可以，非常流畅"
```

## 3. 决策与理由
- **P0：`contentReady` 判断从 `settledPage` 改为 `currentPageOffsetFraction != 0f`** [V]——`settledPage` 是离散整数，动画一启动就跳到目标值，导致 `beyondViewportPageCount` 在动画进行中就从 0 放开到 1，重内容在动画过程中就 compose，挤占主线程。改用 `currentPageOffsetFraction`（连续浮点，动画进行中非 0，停稳后回 0）判断动画状态，配合 `withFrameNanos {}` 等一帧，让重内容在动画已停止的静态画面上 compose，stutter 不可见。照搬 KernelSU `DeferredContent.kt` 的 `rememberContentReady` 思路。
- **P1：backdrop 重命名 `backdrop` → `blurBackdrop`，明确职责** [V]——原命名 `backdrop` 与子页面各自的 backdrop 命名冲突，且注释未说明它是"给底栏 + 包裹 pager 的外层 backdrop"。重命名后代码自文档化。纯重命名，行为不变。
- **P2：`blurRadius` 从 12f 改回 KernelSU 原值 25f** [V]——之前自作主张减半到 12f 导致模糊效果偏弱；改回 25f 与 KernelSU 一致，视觉无差异。

## 4. 失败的尝试 — 不要再试
- **共享同一个 `LayerBackdrop` 实例给底栏 + 子页面 `layerBackdrop()`** [X]——同一个 `LayerBackdrop` 实例在 render tree 多处 `layerBackdrop()` 挂载，`libhwui` 的 `RenderThread` 在 `prepareTreeImpl` 遍历时访问到被释放的 GPU 资源 → `SIGSEGV @ RenderThread`。KernelSU 的真实结构是嵌套两层 backdrop：外层给底栏 + 包裹 pager，子页面各自创建独立的 backdrop 实例。不要再共享实例。
- **移除外层 `layerBackdrop` 让子页面各自 `rememberBlurBackdrop`** [X]——底栏的 `BlurredBar` 依赖外层 backdrop 捕获 pager 内容做模糊，移除后底栏模糊消失（用户反馈"底栏模糊又没了"）。底栏的 blur 必须依赖外层 backdrop，不能只靠子页面的。
- **在 `remember{}` 里调 `evaluate(awaitService=true)`** [V]（前向搬运 M36）——`remember` 在 composition 期间同步执行，`awaitService=true` 的 3 秒轮询循环直接阻塞主线程。不要再把重型同步操作放 `remember{}`。
- **用 `Handler.postDelayed` 做"异步"写配置** [V]（前向搬运 M36）——runnable 实际跑在 main looper，`ModConfig.write` 的 JSON 序列化+Binder IPC+文件写+广播全在主线程。Compose 项目应该用 `rememberCoroutineScope() + withContext(IO)`。
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
