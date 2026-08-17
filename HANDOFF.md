# HANDOFF — 读全文再开始干活

生成时间: 2026-08-17T17:08:23+08:00 · Git HEAD: `8f01106`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: `main` @ `8f01106` (2026-08-17)
- 漂移检查: `git rev-parse HEAD` 是否仍 = `8f01106`；变了说明快照可能过期
- 先读: `HANDOFF.md` + `CLAUDE.md`

## 1. 当前目标

**修复最近三次 CI 失败（lint NewApi 错误）。** 已完成：3 个 lint error 全部修复，本地 lint + assembleRelease 全绿，CI run `32012990359` 全绿通过。遗留：position 合并修复待用户真机确认、janky 根因待排查。

## 2. 已验证状态 — 工作实际停在哪

- [V] **CI lint 修复完成** — 3 个 `NewApi` error（minSdk=26）：
  - `ConfigActivity.kt:86` `window.isNavigationBarContrastEnforced`（需 API 29）→ 加 `Build.VERSION.SDK_INT >= Q` 守卫 + import。
  - `themes.xml:6` `android:windowLayoutInDisplayCutoutMode`（需 API 27）→ 从 base 移除，拆到新增 `values-v27/themes.xml`。
  - 本地 `./gradlew :app:lint` → BUILD SUCCESSFUL（0 error）；`./gradlew :app:assembleRelease` → BUILD SUCCESSFUL。
- [V] **CI 全绿** — commit `8f01106` push 后 run `32012990359` 全步骤通过（lint / assembleRelease / rename / upload），5m53s。
- [V] **工作区干净** — `git status` 无未提交改动，`main` 与 `origin/main` 同步。
- [V] **自定义曲线编辑器** — 上一会话实现（多点控制点 + 保单调三次样条），本会话 CI 构建通过间接验证代码可编译。
- [V] **native 库加载修复** — 移除 `useLegacyPackaging`，`libshadowhook.so` 可被 linker 解析。
- [V] **配置实时生效修复** — `rebuildFromConfigChange`/`toggleOverlays` 优先用广播 JSON（`latestConfigJson`）代替 `readFromTargetProcess`。
- [?] **position 合并修复** — `resolveLatestSettings()` 从本地 externalFilesDir 合并 position，`mergePositionFromLocalPublic()` 公开化。用户未确认是否生效。
- [?] **切换模式后位置丢失** — 用户反馈"启动时调过位置大小的单踏板，切双踏板再切回单踏板，位置/大小变成默认状态"。上述 position 合并修复已尝试解决，用户未确认。

### 测试/build 输出（本次交接 run 的真实输出）
```
./gradlew :app:lint --no-daemon → BUILD SUCCESSFUL (0 error, 20 warnings, 1 hint)
./gradlew :app:assembleRelease --no-daemon → BUILD SUCCESSFUL (lintVitalRelease 无 error)
gh run watch 32012990359 → ✓ build in 5m53s, 全步骤通过
```

## 3. 决策与理由

- **`values-v27` 资源限定符拆分** [V]——`windowLayoutInDisplayCutoutMode` 需 API 27，base 放通用属性、`values-v27` 放高版本属性，系统自动按 API 选择，无需运行时判断。比代码 `if (SDK_INT >= 27)` 更干净。
- **`isNavigationBarContrastEnforced` 用 `SDK_INT >= Q` 守卫** [V]——API 29 才有的属性，`enableEdgeToEdge` 本身已做版本兼容，只需对这一个属性守卫。
- **移除 `useLegacyPackaging = true`** [V]——KernelSU 用 `System.loadLibrary`（从 APK 直接加载），我们走 `forceLoad`（解压单个 .so 再 load），依赖库必须已在文件系统上。不移除则 linker 找不到 `libshadowhook.so`。
- **rebuild 优先用广播 JSON 而非 readFromTargetProcess** [V]——daemon 写入滞后于广播，广播 JSON 是刚写入的最新 payload。但需合并本地 position 避免丢失位置。
- **保单调三次样条（Fritsch–Carlson）代替分段线性** [V]——分段线性不光滑，Fritsch–Carlson 经过所有控制点、连续梯度、段内单调无过冲。

## 4. 失败的尝试 — 不要再试

- [X] **`useLegacyPackaging = true`** — M40 照搬 KernelSU 加的，导致 `libshadowhook.so not found` 的 native 加载失败。不要再加。
- [X] **`readFromTargetProcess` 作为 rebuild 唯一配置来源** — daemon 写入滞后于广播，rebuild 读到旧 pedalMode/curve。`rebuildFromConfigChange` 和 `toggleOverlays` 必须优先用广播 JSON。不要再单用 `readFromTargetProcess`。
- [X] **手写 SwitchRow/SliderRow → miuix preference 组件** — M38 M39 已验证，换了仍 22-38% janky。
- [X] **关 blur / 移除 rememberContentReady / ModConfig.read 异步 / 各种 janky 优化** — 所有 M38-M40 的 janky 修复尝试均无效，详见旧 HANDOFF.md。

## 5. 已知坑

- ⚠️ **daemon 配置写入滞后于广播** [V]——`ModConfig.write` 先写 remote preferences（daemon），再发广播。但 daemon 异步绑定可能延迟，广播比 remote 先到。`readFromTargetProcess` 读 remote（daemon 旧值）≠ 刚写入的配置。解决方案：`rebuildFromConfigChange` 和 `toggleOverlays` 优先用广播 JSON。
- ⚠️ **广播 JSON 不含 position 字段** [V]——ConfigActivity 不管 position（游戏进程拖拽时 `saveOverlayPosition` 写本地）。用广播 JSON 解析 `Settings` 后必须从本地 externalFilesDir 合并 position，否则重建后位置/大小丢回默认。`resolveLatestSettings()` 已处理此逻辑。
- ⚠️ **AGP 9 不需要 kotlin-android 插件** [V]——AGP 9 内置 Kotlin 支持，`org.jetbrains.kotlin.android` 插件会报错。
- ⚠️ **NDK 29 下载失败** [V]——Clash TUN TLS 干扰，用本地 NDK 26 替代。
- ⚠️ **miuix SwitchPreference 在我们的 app 中 22% janky，KSU 同版本 0.10%** [V]——仍待排查，R8 优化差异可能。
- ⚠️ **lint 的 NewApi 检查会拦 minSdk 26 下的高版本 API** [V]——照搬 KernelSU 代码时注意 KernelSU minSdk 更高，其 API 调用可能超出我们的 minSdk。新增高版本 API 调用时用 `values-vNN` 拆分或 `SDK_INT` 守卫。

## 6. 下一步（有序）

1. **验证 position 合并修复** — 用户确认"切双踏板再切回单踏板，位置/大小是否保持"。如果仍丢回默认，需排查 `resolveLatestSettings()` 的 `mergePositionFromLocalPublic` 是否正确合并了 position。
2. **继续排查 janky 根因** — R8 映射文件对比（KSU dex=5.2MB vs 我们 2MB），见 M40 HANDOFF。

## 7. 留给用户的开放问题

- 切换模式后单踏板位置丢失问题是否已修复？需要用户真机验证。
- 自定义曲线图表布局（分隔线/卡片结构/轴标签位置）是否满意？之前用户说"不改了"，但交互细节可继续调整。
