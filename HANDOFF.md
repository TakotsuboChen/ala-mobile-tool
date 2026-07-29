# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T01:17:18+08:00 · Git HEAD: 468bbde（交接前；本次改动尚未 commit）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

配置页 UI 重构 + "手动换挡"开关接线。**已完成，编译通过，待 commit。**

完成定义：① 新增"Overlay 控件"小标题分组；② "显示悬浮窗""线性踏板"（原"踏板覆盖"）移入该组；
③ 新增"手动换挡（开发中）"开关，UI 禁用但绑定真实状态 `enableManualShift`；④ 移除原"关闭自动换挡"开关
（语义被手动换挡吞并：开手动换挡 ⇒ 关游戏自动换挡）；⑤ 开关的 false 真实流到 OverlayManager（不创建
GearShiftView）和 native（`disableAutoGear = enableManualShift` 派生）。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `468bbde`（交接前），工作树有 6 个文件 modified 未 commit。
- [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL in 14s（改动后编译通过）。
- [V] `ModConfig.Settings` 新增 `enableManualShift: Boolean` 字段，JSON key `enable_manual_shift`，
  默认 `false`。read/write/defaultSettings 三处都加了。
- [V] `ConfigUiState` 新增 `enableManualShift: MutableState<Boolean>`，`ConfigMainScreen` 的
  `remember{}` 和 `saveNow` 的 `ModConfig.write(...)` 都带上新字段。
- [V] `ConfigurePage` 三组结构：功能开关（自动 DRS 灰、解锁付费内容）/ Overlay 控件（显示悬浮窗、
  线性踏板、手动换挡灰）/ 踏板映射。手动换挡开关 `enabled=false` 但 `checked=uiState.enableManualShift.value`，
  `onCheckedChange` 真实写状态 + `onSave()` 持久化。
- [V] 顺带修老 bug：原"自动 DRS（开发中）" `checked = false` 硬编码，现绑 `uiState.enableAutoDrs.value`，
  禁用开关也反映真实配置值。
- [V] `AlaMobileModule.onPackageReady` 读 `settings.enableManualShift`，派生 `disableAutoGear = enableManualShift`
  传给 `NativeBridge.initWithOffsets`。native 签名未改（`disableAutoGear` 语义不变）。
- [V] `OverlayManager.addGamingOverlays` 用 `if (settings.enableManualShift)` 守卫 `GearShiftView` 创建；
  关时 `gearView` 保持 null，不 add gear edit layer。`addEditLayers` 拆成 `addPedalEditLayer` +
  `addGearEditLayer`。
- [V] `OverlayManager.toggleOverlays`/`toggleEditMode` 判空条件从 `pedalView == null || gearView == null`
  改为只判 `pedalView == null` —— 避免手动换挡关时 gearView 永远 null 触发重复 add pedalView bug。
- [V] `PedalOverlayView` 默认参数 `ModConfig.Settings(...)` 补 `enableManualShift = false`（否则编译失败）。
- [?] 真机冒烟测试未跑（仅编译验证）。需用户在设备上确认：手动换挡关时换挡 Overlay 不出现、踏板正常。

### 测试/build 输出 tail（本次交接的真实输出）

```
$ ./gradlew :app:assembleDebug
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:dexBuilderDebug
> Task :app:mergeProjectDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug
BUILD SUCCESSFUL in 14s
39 actionable tasks: 9 executed, 1 from cache, 29 up-to-date
```

## 3. 决策与理由

- UI 分组用 SmallTitle + Card，"Overlay 控件"独立组 [V]——用户拍板，把输入类控件（悬浮窗显隐、踏板、
  换挡）聚成一组，与功能开关（DRS、付费）分离。否决：塞进功能开关，因语义混杂。
- "踏板覆盖"改名"线性踏板" [V]——用户视角命名（描述用户感知而非技术机制）。summary 仍保留机制说明
  避免名字变抽象。字段名 `enableControlReplacement` 不改（保 JSON 向后兼容）。
- "关闭自动换挡"开关删除，语义并入"手动换挡" [V]——用户拍板：开手动换挡理应顺带关自动换挡，
  两个互斥行为合并成一个开关。否决：保留两个独立开关，因用户心智模型上它们是一个动作的两面。
- `enableManualShift` 派生 `disableAutoGear` 而非删 `disableAutoGear` 字段 [V]——避免改 NativeBridge
  JNI 签名 + native 代码。`disableAutoGear` 仍留 JSON 向后兼容旧配置，但 module 层不再直接读它。
- 禁用开关仍绑真实状态 + onSave [V]——用户明确要求：开关虽 UI 不可调，但"当前不可调的关状态"要真实
  流到 OverlayManager 和 native。禁用 ≠ 假数据。否决：`checked = false` 硬编码（原自动 DRS 的写法），
  因不反映真实配置值，误导用户且语义上不真实。
- `OverlayManager.toggleOverlays` 判空改只判 pedalView [V]——手动换挡关时 gearView 永远 null 是正常态，
  原条件会每次 toggle 触发 `addGamingOverlays()` 重复 add pedalView。`gearView?.visibility` 用 safe-call 跳过。

## 4. 失败的尝试 — 不要再试

- **`PedalOverlayView` 默认参数漏 `enableManualShift`** [V]——给 `ModConfig.Settings` 加字段后，
  `PedalOverlayView` 的默认参数构造没补，编译失败 `No value passed for parameter 'enableManualShift'`。
  加数据类字段时必须 grep 所有 `ModConfig.Settings(...)` 构造点同步。不要再漏。
- （搬运自旧 HANDOFF，仍有效）
- **`gh run download` 验证 `archive: false` artifact** [V]——CLI 把 apk 当目录解包。验证 artifact 用 curl/浏览器。
- **`gh release view` 查 asset 文件名** [V]——API 返回点号风格，浏览器保留空格。看浏览器。
- **`android-actions/setup-android@v4` 装 `platforms;android-37`** [V]——Google SDK 仓库未发布。
- **symlink `android-37.0` → `android-37`** [V]——AGP 报 inconsistent location。必须复制 + 改路径标识。
- **只改 source.properties 不改 package.xml** [V]——4 处都要改。
- **YAML `- name:` 值含冒号未加引号** [V]——workflow 0s 失败。
- **旧 CI 的 `|| true` + `continue-on-error` 吞错** [V]——lint 假绿。已去掉。
- **`onModuleLoaded` 立即 `markInitialized()`** [?]——自杀，拦掉同 ClassLoader 后续回调。
- **IPC 文件路径优化写法** [?]——`RandomAccessFile.seek+write` 非原子。全试过全失败。
- **`getLocationOnScreen()` 重建相对坐标** [?]——pairip 壳 relayout 漂移。用 rawY + 配置位置。

## 5. 已知坑

- **加 `ModConfig.Settings` 字段必同步所有构造点** [V]——`ModConfig.defaultSettings()`、
  `ModConfig.read()`、`ConfigMainScreen.remember{}`、`ConfigMainScreen.saveNow`、`PedalOverlayView` 默认参数。
  漏一个编译失败。本次漏了 PedalOverlayView 默认参数。
- **禁用开关的 `checked` 必须绑真实状态** [V]——`enabled=false` 只锁交互不锁数据。硬编码 `checked=false`
  是 bug（误导用户 + 不真实）。自动 DRS 和手动换挡都绑 `uiState.xxx.value`。
- **`OverlayManager` 判空触发 addGamingOverlays 不能用 gearView** [V]——手动换挡关时 gearView 永远 null
  是正常态，用它判空会重复 add。只用 pedalView。
- （搬运自旧 HANDOFF，仍有效）
- **`archive: false` 对 apk 有效但 CLI 误导** [V]——验证用 curl/浏览器。
- **`gh release view` asset name 转义空格成点号** [V]——看浏览器。
- **AGP ApiLevel 整数比较** [V]——复制 platform 后必须 sed `37.0`→`37`。
- **AGP inconsistent location 检查** [V]——必须改 package.xml `path` + source.properties `Pkg.Path`。
- **Google SDK 仓库未发布 `platforms;android-37`** [V]——GitHub runner 预装 `android-37.0` 可复制改路径。
- **Aliyun 镜像 CI 502** [V]——`settings.gradle.kts` 用 `System.getenv("CI") == null` 条件化。
- **lint baseline 不锁 AGP AAR metadata 检查** [V]——AAR metadata 必须从根上修。
- **miuix 0.9.3 `minCompileSdk=37` 硬要求** [V]——不能降 compileSdk。
- **LSPosed 双 ClassLoader 注入** [?]——共存版触发双注入，必须 `System.setProperty` 守卫。
- **pairip 壳 relayout 漂移** [?]——用 `event.rawY - settings.pedalPosition.topPx()`。
- **keystore 不在 git** [V]——`.gitignore` 排除，CI 靠 `KEYSTORE_BASE64` secret。
- **module.prop versionCode 必须同步** [?]——LSPosed 用 module.prop 识别更新。
- **游戏刹车辅助** [?]——弯道自动刹车，和模块无关，用户在游戏设置关掉。
- **GitHub Actions `if` 里不能直接引用 `secrets`** [V]——先 `env:` 再 `if: env.X != ''`。
- **keystore 解码 step 的 `KEYSTORE_PATH` 只在解码成功时设** [V]——`if: env.KS_B64 != ''` 守卫整个 step。
- **`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 走 Gradle 默认值** [V]——真实密码=默认值 `alamobiletool`，
  改 keystore 密码必须同步设这两个 secret。
- **workflow `prerelease: true` 硬编码** [V]——正式版发 Release 时必须改 `false`。

## 6. 下一步（有序）

1. 用户真机冒烟测试：手动换挡关时换挡 Overlay 不出现 + 踏板正常；自动 DRS 开关状态正确显示。
2. （可选）后续真正实现"手动换挡"开关：① 加 `enableManualShift` 到 native hook 关闭游戏自动换挡逻辑
   （需从 `il2cpp-dumps/v8.0.0/dump.cs` 找 `IRSDrivetrain.automatic` 字段或 `SetGear` 相关 method offset）；
   ② `GearShiftView` 已经会渲染，开关打开后 OverlayManager 会自动创建它。
3. （可选）后续真正实现"自动 DRS"开关：`drs_hook.c` 已有骨架，需接 `inDRSZone`/`throttle`/`steeringAngle`/
   `speed` IL2CPP 实例字段读取（见 CLAUDE.md TODO(human) 第 2 点）。
4. （可选）发正式版 Beta 2：versionCode=100220 + versionName=`1.0.0 Beta 2` → commit → tag → push。
5. （可选）修 lint baseline 锁住的 3 个 NewApi。

## 7. 留给用户的开放问题

- 真机冒烟测试是否通过？（手动换挡关时换挡 Overlay 不出现、踏板正常、自动 DRS 开关显示真实状态）
- "手动换挡"开关后续开发时，是直接在 native 层读 `disableAutoGear`（=派生值）还是新加一个
  `enableManualShift` JNI 参数？（前者无需改 native 签名，后者语义更清晰）
- 配置页要不要再加一个"重置位置"按钮用于 overlay 位置编辑？（当前长按 toggle 进 edit mode）
