# HANDOFF — 读全文再开始干活

生成时间: 2026-07-28T23:50:00+08:00 · Git HEAD: 17528aa (将随本次 commit 前进)
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

发布 v1.0.0-Beta-1 (100210)：共存版稳定性大修 + CI 自动构建 + tag 触发 Pre-release。

完成定义：tag `v1.0.0-Beta-1` push 后，GitHub Action 自动构建并发布 Pre-release 到 Releases 页。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `17528aa`，本次新增 8 个文件改动（含 README/CLAUDE/CI/版本号/三个 bug 修复）。
- [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL。
- [V] `./gradlew :app:assembleRelease` BUILD SUCCESSFUL（本地 keystore 存在，走 release 签名）。
- [V] 共存版"踏板抖动"根因查明：**游戏内置刹车辅助**在弯道自动介入，与模块无关。用户在游戏设置里关掉辅助即可。证据：logcat 显示 `throttle/brake` 值稳定，但用户仍感觉抖；最终用户确认是辅助功能未关。
- [V] 三个真实 bug 已修复（虽非抖动元凶，但确实存在）：
  1. 双 ClassLoader 守卫（`AlaMobileModule.kt`）— LSPosed 在共存版用双 ClassLoader 注入，第二个 ClassLoader 的 hook 全失败但启动第二个 writer 线程。
  2. 废除 IPC 文件路径（`pedal_hook.c`/`PedalOverlayView.kt`/`GearShiftView.kt`）— `RandomAccessFile.seek+write` 非原子，`pread` 读到半截数据。
  3. rawY + 配置值坐标（`PedalOverlayView.kt`）— pairip 壳反复 relayout，`event.getY()` 漂移。
- [V] 版本号已更新：`build.gradle.kts` versionCode=100210/versionName=1.0.0-Beta-1；`module.prop` 同步。
- [V] GitHub Action `.github/workflows/build.yml` 升级：tag `v*` 触发 Pre-release，用 `softprops/action-gh-release@v2`。
- [V] `build.gradle.kts` signingConfigs 支持环境变量 + keystore 不存在时 fallback 到 debug 签名（CI 永不失败）。

### 测试/build 输出 tail（本次交接 run 的真实输出）

```
$ ./gradlew :app:assembleRelease
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect
> Task :app:assembleRelease
BUILD SUCCESSFUL in 1m 17s
```

## 3. 决策与理由

- 共存版抖动根因是游戏辅助，不做"禁刹车辅助"功能 [V]——交给用户手动关。理由：根因是游戏设置，不是模块逻辑；做 hook 强关辅助属于额外功能，本次不做。
- 废除文件 IPC 路径，两版统一走 JNI 直调 [V]——双 ClassLoader 守卫已保证 JNI 在共存版可靠可用，IPC 兜底是历史包袱。否决方案：继续优化文件 I/O，因 `RandomAccessFile.seek+write` 非原子注定无法消除竞态。
- 双 ClassLoader 守卫用 `System.setProperty` [V]——进程级共享（bootstrap ClassLoader），跨 LSPosed 双 ClassLoader 可见。否决方案：native 层 `pthread_once`，因 `forceLoad` 解压 .so 成 temp 文件再 `System.load`，linker 当成独立 DSO，static 变量不共享。
- rawY + 配置值坐标 [V]——`event.getY()` 和 `getLocationOnScreen()` 都依赖运行时 layout，pairip 壳会漂移；只有配置值（存在 JSON）稳定。原版上配置值==实际值，行为不变。
- CI keystore 用环境变量 + fallback debug 签名 [V]——保证 CI 永远能产出可安装 APK。本地构建用 release keystore，CI 配了 secret 用 CI keystore，没配 fallback debug。

## 4. 失败的尝试 — 不要再试

- **方案 1：Java 层 `onModuleLoaded` 立即 `markInitialized()`** [V]——自杀。`onModuleLoaded` 在 `onPackageReady` 之前触发，立刻立标记会拦掉同一个 ClassLoader 自己后续的回调，整个模块初始化被跳过，overlay 消失、unlock 失效。不要再试。
- **方案 2：在 IPC 文件路径上优化写法** [V]——`File.writeText()`/`RandomAccessFile.seek+write`/mmap/全局目录 全试过，全失败。`seek+write` 非原子，`pread` 读半截数据。不要再试。
- **方案 3：用 `getLocationOnScreen()` 重建相对坐标** [V]——它报告的是 view 实际位置，pairip 壳 relayout 时同样漂移。只有配置值稳定。不要再试。
- **方案 4：怀疑 JNI 参数错位** [?]——曾因 grep 误过滤 `Hooked ... at 0x...` 行（含 "at "）误判 hook 没装。实际全装上了。不要再试这个 grep。
- **方案 6：CI 装 `platforms;android-37`** [V]——`sdkmanager "platforms;android-37"` 报 `Failed to find package`，仓库未发布。`--channel=3` 也不行。不要再试。
- **方案 7：复制 `android-37.0` → `android-37` + sed 改 id** [V-最终成功]——关键在 sed 要改 4 处：source.properties 的 `Pkg.Path` + `ApiLevel=37.0→37`（整数），package.xml 的 `path` + `<api-level>37.0→37</api-level>`。之前失败是因为只改了部分，且当时误以为 `ApiLevel=36`（实际是 37.0）。symlink 不行（报 inconsistent location）。
- **方案 8：符号链接 `android-37.0` → `android-37`** [V]——同样 `inconsistent location` 警告 + 找不到 platform。不要再试。
- **方案 9：`android.suppressUnsupportedCompileSdk=37`** [V]——只抑制 AGP 的 maxSdk 警告，不抑制 miuix AAR metadata 的硬要求。不解决。不要再试。

## 5. 已知坑

- **LSPosed 双 ClassLoader 注入** [V]——共存版（重打包/pairip 壳）触发 `LspModuleClassLoader` + `VectorModuleClassLoader` 双注入。第二副本 hook 全失败（ShadowHook "Not initialized"），但仍启动 writer 线程。必须 Java 层 `System.setProperty` 守卫。
- **pairip 壳 relayout 漂移** [V]——共存版 `com.pairip.application.Application` 反复 relayout overlay view，`event.getY()` 和 `getLocationOnScreen()` 都漂移。必须用 `event.rawY - settings.pedalPosition.topPx()` 重建坐标。
- **keystore 不在 git** [V]——`*.keystore` 在 `.gitignore`。CI 需配 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 三个 secret，否则 fallback debug 签名。
- **module.prop versionCode 必须同步** [V]——LSPosed 用 module.prop 识别模块更新，build.gradle 版本号变了必须同步 module.prop。
- **`MotionEvent.getY()` vs `getRawY()`** [V]——getY 相对 view 左上角，getRawY 屏幕绝对坐标。pairip 壳干扰前者，后者稳定。
- **游戏刹车辅助** [V]——弯道自动刹车，表现为"突突突顿挫"，和模块无关。用户需在游戏设置里关掉。
- **CI 无法构建 APK (compileSdk=37)** [V-已解决]——原以为 Google SDK 仓库未发布 `platforms;android-37`，实际是 GitHub runner 预装了 `android-37.0`（ApiLevel=37，真 API 37 platform，Platform 17）。**解法**：复制 `android-37.0` → `android-37`，并 sed 改 `source.properties` 的 `Pkg.Path` + `ApiLevel=37.0→37`（AGP 按整数比较，37.0≠37），以及 `package.xml` 的 `path` + `<api-level>` 标签。symlink 不行（报 inconsistent location）。**另：Aliyun 镜像在 CI 上偶发 502，`settings.gradle.kts` 已用 `System.getenv("CI") == null` 条件化，CI 直连 Google Maven。**
- **lint 假绿** [V-已解决]——旧 CI 用 `|| true` + `continue-on-error: true` 双重吞错，lint 红了也显示绿。已去掉，用 lint baseline 锁存量 3 个 NewApi（BillingHook.defaultClassLoader / VersionGate.longVersionCode）。
- **GitHub Actions `if` 里不能直接引用 `secrets`** [V]——`if: ${{ secrets.X != '' }}` 会导致 workflow 结构错误 0s 失败。必须先 `env: KS: ${{ secrets.X }}` 再 `if: ${{ env.KS != '' }}`。

## 6. 下一步（有序）

1. ~~已完成~~ v1.0.0-Beta-1 Pre-release 已发布（[GitHub Release](https://github.com/TakotsuboChen/ala-mobile-tool/releases/tag/v1.0.0-Beta-1)），release APK 已上传。
2. ~~已解决~~ CI 现在真能构建 APK（lint + assembleRelease），tag push 时自动上传到 Release。不再需要本地构建上传。
3. 在共存版和原版上各跑一次冒烟测试，确认 overlay 显示、踏板响应、换挡、billing 解锁都正常。
4. （可选）在 GitHub 仓库 Settings → Secrets 添加 `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS`，以便未来 CI 能用 release 签名（当前 CI 产出 debug 签名 APK，可安装但升级需卸载重装）。
5. （可选）修 lint baseline 锁住的 3 个 NewApi（BillingHook.defaultClassLoader / VersionGate.longVersionCode），让 baseline 清零。

## 7. 留给用户的开放问题

- 是否需要做"禁刹车辅助"hook 功能？（本次决定不做，交给用户手动关。如果后续很多用户反馈，可考虑。）
- CI 是否需要配 release keystore secret？（没配也能跑，但产出 debug 签名 APK，用户升级需卸载重装。）
- 是否需要在共存版上进一步验证 billing 解锁的稳定性？（本次只验证了 overlay + 踏板 + 换挡。）
