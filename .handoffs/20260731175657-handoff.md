# HANDOFF — 读全文再开始干活

生成时间: 2026-07-31T10:00:00+08:00 · Git HEAD: 7c19017release:1.0.Beta2
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标
本次会话发布 **1.0.0 Beta 2** Pre-release——完成并已上线 GitHub Release。版本号 `versionCode=100220`、`versionName="1.0.0 Beta 2"`，三处同步（`app/build.gradle.kts` + `module.prop` + README 版本历史）。Release notes 已贴。CI run `30556486131` 全绿，release 签名 APK 已上传。**Beta 2 发布闭环完成；M16 真机视觉验证仍待用户确认。**

## 2. 已验证状态 — 工作实际停在哪

- [V] **版本号同步**：`app/build.gradle.kts` L15-16 = `versionCode 100220` / `versionName "1.0.0 Beta 2"`；`module.prop` L5-6 = `version=1.0.0 Beta 2` / `versionCode=100220`。`git show 7c19017` 确认。
- [V] **versionCode 100220 正确**：Beta 1=`100210`（CLAUDE.md 表格锚点），Beta 2 序列位 D=2 → `100220`。我中途算错成 `100230`（Beta 3 的号），被用户抓出修正。CLAUDE.md M8 段表格是 versionCode 单一真相源。
- [V] **README 重写**：功能列表从 Alpha 2 时代更新到 Beta 2（新增踏板拓扑下拉/双踏板仲裁/方向反转/手动换挡/长按重置/即时生效/激活卡片/激活检测/图标）；配置页补 Overlay 控件分组；版本历史补 Beta 2 条目，三个旧 tag 日期从 2025 修正为 2026（git log 真值：Alpha 1/2=2026-07-28、Beta 1=2026-07-29）。
- [V] **本地构建全绿**：`./gradlew :app:assembleDebug :app:lint` → `BUILD SUCCESSFUL in 43s`，50 actionable tasks。
- [V] **commit + tag + push**：commit `7c19017` "release: 1.0.0 Beta 2 (versionCode 100220)"，tag `v1.0.0-Beta-2`，`git push origin main` + `git push origin v1.0.0-Beta-2` 均成功。
- [V] **CI 全绿**：run `30556486131`（tag push 触发）5m28s 全绿，所有 13 个 step 过，含 "Decode release keystore"/"Build release APK"/"Rename APK to project naming convention"/"Upload APK artifact"/"Upload to Release"。
- [V] **Release 已发**：`gh release view v1.0.0-Beta-2` → `isPrerelease: true`，asset `label="Ala Mobile Tool v1.0.0 Beta 2.apk"`（name=`Ala.Mobile.Tool.v1.0.0.Beta.2.apk`，2.09 MB）。softprops/action-gh-release@v3 自动建 Release + 上传 APK；`gh release edit --notes-file` 贴上 Release notes 正文。
- [?] **M16 真机视觉验证未做**：APK 已装设备 381QYFCN22B9A，但用户未点开 ConfigActivity 确认 GitHub/QQ 图标形状 + 激活卡片显示（这是 Beta 2 前就遗留的待确认项）。

### 测试/build 输出 tail（本次交接 run 的真实输出）
```
$ ./gradlew :app:assembleDebug :app:lint
BUILD SUCCESSFUL in 43s
50 actionable tasks: 25 executed, 25 up-to-date

$ gh run watch 30556486131 --exit-status
✓ v1.0.0-Beta-2 CI · 30556486131
✓ build in 5m28s (ID 90918145226)
  ✓ Decode release keystore
  ✓ Build release APK
  ✓ Rename APK to project naming convention
  ✓ Upload APK artifact
  ✓ Upload to Release

$ gh release view v1.0.0-Beta-2
{"isPrerelease":true,"assets":[{"label":"Ala Mobile Tool v1.0.0 Beta 2.apk","name":"Ala.Mobile.Tool.v1.0.0.Beta.2.apk","size":2089780}]}
```

## 3. 决策与理由

- **Beta 2 = Pre-release（prerelease=true）** [V]——CLAUDE.md 发布阶段策略：Alpha/Beta 发 Pre-release，Stable 才发 Release。workflow `prerelease: true` 硬编码，对 Beta 正确，不需改。否决方案：发 Stable 翻 `prerelease: false`（不到时候，Beta 2 是预发布）。
- **版本号 100220 而非 100230** [V]——Beta 1 锚点 `100210` → D 位递增 → Beta 2=`100220`。否决方案：`100230`（那是 Beta 3 的，我中途误算，被用户抓出）。**教训：versionCode 必须用 CLAUDE.md M8 表格的现成锚点反推，不能凭空算，也不能拿 HANDOFF "下一步发 X" 当当前版本号。**
- **Release notes 按用户可感知变化组织，不按提交清单** [V]——Beta 1 之后 34 个提交，其中 4 个是已 revert 的 Beta 2 测试 tag 噪声，实际 30 个功能提交覆盖 M7~M16。按"新功能/修复/技术细节/升级须知"四段组织，不按 commit log 平铺。否决方案：平铺提交清单（信息密度低，用户读不下去）。
- **日期必须从 git tag 真值取，不抄旧 README** [V]——旧 README 把 Alpha/Beta 都写成 2025 年（本身错），我抄旧条目未独立验证，被用户抓出。三个 tag 真值都是 2026 年。**教训：抄旧内容时每条事实都要独立验证，不能假设旧内容对。**

## 4. 失败的尝试 — 不要再试

- **versionCode 凭空算成 100230** [V]——根因有二：(1) 没拿 CLAUDE.md 表格里 Beta 1=`100210` 这个现成锚点反推；(2) 串了 HANDOFF "下一步发 Beta 3 用 `100230`"，把未来计划误当当前版本号。修正为 `100220`。不要再试——versionCode 必须用锚点反推。
- **README 版本历史抄旧条目未独立验证日期** [V]——旧 README 写 2025 年，我照搬，被用户抓出。三个 tag 真值都是 2026 年。不要再试——每条事实独立验证。
- **（前向搬运，仍成立）** `System.getProperty(MODULE_LOADED_FLAG)` 作 ConfigActivity 激活判定 [V]——ConfigActivity 进程不被注入，property 永不设上。已改用 `App.xposedService != null`。
- **（前向搬运，仍成立）** daemon `module_loaded` 持久标记作主判定 [V]——语义松（关了 Manager 仍残留）。被 service 绑定状态取代。
- **（前向搬运，仍成立）** `PathBuilder` 手动转译 SVG path 含 arc [V]——QQ path 含相对 `a` 命令，参数顺序敏感，手写易错位。用 PathParser。
- **（前向搬运，仍成立）** `path()` DSL 的 `pathData: List<PathNode>` 参数 [V]——`path()` 只接 `PathBuilder.() -> Unit`，`group()` 才有 `pathData` 重载。
- **（前向搬运，仍成立）** miuix `primaryContainer`/`primaryVariant` 作已激活底色 [V]、**miuix 0.9.3 有 SuperDialog** [V]、**openRemoteFile 读模块 filesDir** [V]、**legacy `de.robv.android.xposed.XSharedPreferences`** [V]（libxposed API 102 禁用）、**模块进程写公共 `/sdcard/`** [V]、**ContentProvider 跨进程** [V]、**createPackageContext** [V]、**5 参 call 重载** [V]、**by lazy 只改缓存不够** [V]、**applyCurve 作用单字段** [V]、**BRAKE 从底向上画水位式** [V]、**M12 OverlayEditView 传 settings.*Position 作 defaultPosition** [V]、**SINGLE/DUAL 共用 pedal_position 字段** [V]、**统一公式画两种方向刹车** [V]——均不再试。

## 5. 已知坑

- **versionCode 必须用 CLAUDE.md M8 表格锚点反推** [V]——Beta 1=`100210`→Beta 2=`100220`→Beta 3=`100230`。不能凭空算，不能拿 HANDOFF 未来计划当当前号。
- **抄旧内容要独立验证每条事实** [V]——旧 README 日期错（2025→应为 2026），不验证就抄会传播错误。
- **GitHub Release asset name vs label** [V]——`name` 是 URL 文件名（不能含空格，softprops 自动替换成点），`label` 是 UI 显示名。M8 "space-preserving" 指 label。浏览器 Release 页面显示 label，实际下载文件名是 name。
- **ConfigActivity 进程不被 LSPosed 注入** [V]——`onModuleLoaded` 只在目标 App 进程调。新方案用 `App.xposedService` 绑定状态绕开。
- **LSPatch 用 legacy `assets/xposed_init`** [V]——不走 libxposed API 102 的 `onModuleLoaded`，不绑 daemon。`xposedService == null` 自动落 Non-root 手动路径。
- **XposedServiceHelper 异步绑定** [V]——ConfigActivity.onCreate 时 `App.xposedService` 可能仍 null，`evaluate` 的 `awaitService` 轮询兜 3s。
- **miuix 默认 primary 是蓝不是绿** [V]——`0xFF3482FF`。配色跟 KernelSU（绿调），不要用 miuix 语义色 token 作已激活底，要硬编码 KernelSU 的绿值。
- **miuix 0.9.3 没有 SuperDialog** [V]——用 `OverlayDialog`（`top.yukonga.miuix.kmp.overlay.OverlayDialog`）。
- **Android 13+ registerReceiver 需 flag** [V]——用 `ContextCompat.registerReceiver(..., RECEIVER_EXPORTED)`。
- **lint baseline 不覆盖新错误** [V]——加新代码必须本地 `./gradlew :app:lint`。
- **Android 11+ scoped storage / 包可见性** [V]——定向广播 + Remote Preferences 是可靠跨进程 IPC。
- **广播首次启动滞后（M11）** [V]——已由 Remote Preferences 根治。
- **PedalOverlayView 构造拷 settings 快照** [V]——配置变更必须重建 view。
- **applyCurve exponent 方向** [V]——<1 是 ease-out，拟真用 0.66。
- **双踏板仲裁只作用于 DUAL** [V]——SINGLE 单 view 内 updateSingle 已自洽。
- **ConfigProvider.kt 已废弃** [?]——广播方案落地后未使用，Remote Preferences 方案下更无用，待清理。
- **共存版双 ClassLoader** [?]——LSPosed 注入两次，markNativeInstalled() 守卫拦第二个。
- **pairip 壳 relayout 漂移** [?]——共存版 view 位置漂移，用 rawY - settings.pedalPosition.topPx() 绕开。
- **libxposed-service 依赖已就位** [V]——`implementation(libs.libxposed.service)` version 102.0.0。

## 6. 下一步（有序）

1. **M16 真机视觉验证**（Beta 2 唯一遗留）——打开 ConfigActivity 概览页：(a) 激活卡片在 LSPosed Manager 启用模块时应显示"已激活"（绿色调），禁用模块后显示"未激活"（红色调）；(b) "GitHub 源代码"行显示 Octocat 图标，"QQ 群"行显示 QQ 企鹅。形状不对就调 SVG path。
2. **清理废弃 IPC 层**（可选）——删 `ConfigProvider.kt` + manifest provider 声明（广播 + Remote Preferences 方案下已无用）。
3. **M16 视觉验证通过后**——真机安装 Beta 2 APK 跑一次冒烟（overlay/pedal/shift/billing + 双踏板仲裁 + 刹车方向反转 + 配置即时生效）。
4. **后续 Stable 1.0.0**——所有 Beta 闭环 + 真机全过后，versionCode `100300`，versionName `1.0.0`，workflow `prerelease: false`。

## 7. 留给用户的开放问题
- M16 真机视觉验证：GitHub/QQ 图标形状对不对？激活卡片两态配色对不对？
- 是否现在清理废弃的 ConfigProvider.kt + manifest provider 声明？
- 是否把两条发版教训（versionCode 锚点反推、抄旧内容独立验证事实）写进 memory？我本会话末尾问过，用户未答（被 /handoff 打断）。
