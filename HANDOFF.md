# HANDOFF — 读全文再开始干活

生成时间: 2026-07-30T02:30:00+08:00 · Git HEAD: 5d86f09（交接前；本次改动尚未 commit）
恢复方式: 对 Claude 说"读一下 HANDOFF.md，按头部 Git HEAD 复核本文件"。
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待。

## 1. 当前目标

线性踏板下拉化 + 双踏板模式 + 响应曲线拆分与修复。**UI 改动完成、编译通过、装机验证，但真机实测发现运行时未生效——根因是 OverlayManager 的 `settings` 是 `by lazy` 缓存旧值，游戏进程不重读配置。**

完成定义（UI 层）：① "线性踏板" Switch→下拉（关/单/双踏板）；② 死区/过渡点移入 Overlay 控件 Card、仅 SINGLE 显示、带 AnimatedVisibility 动画；③ 响应曲线拆成油门+刹车两条下拉；④ curve exponent 改 0.42（ease-out）；⑤ 底栏挡内容修复（三页面加 bottomBarHeight）；⑥ Card 内行间距统一 12垂直+16水平；⑦ 自动 DRS 默认关+读时强制 false；⑧ 解锁付费内容上移+删描述。
**未完成（运行时层）**：⑨ 游戏内切关/双踏板仍显示单踏板控件；⑩ 拟真曲线仍线性。两者根因相同：OverlayManager 缓存配置，不重读。

## 2. 已验证状态 — 工作实际停在哪

- [V] 当前分支 `main`，HEAD `5d86f09`（交接前），10 个文件 modified 未 commit。
- [V] `./gradlew :app:assembleDebug` BUILD SUCCESSFUL in 3s。
- [V] 装机成功（adb install -r），UI 层所有改动在配置页可见。
- [?] **真机实测失败** [用户反馈]：① 切"关"/"双踏板"模式，游戏内仍是单踏板控件（上半油门下半刹车）；
  ② 响应曲线切"拟真"仍是线性。**这不是曲线函数方向问题，是配置值根本没流到运行时。**
- [V] 根因定位：`OverlayManager.kt:34` `private val settings by lazy { ModConfig.readFromTargetProcess(appContext) }`。
  lazy 在 overlay 首次创建时读一次 JSON，之后永远用缓存值。配置页（模块进程）改 pedalMode 写 JSON，
  游戏进程的 OverlayManager 不会重读。toggle 复用 pedalView（非空不重建）加剧此问题。
- [V] `PedalOverlayView` 构造时传入的 `settings` 是 OverlayManager 缓存的旧值，curve/throttleCurve/brakeCurve
  全是旧值，所以拟真曲线不生效。即便修对 exponent 方向，运行时拿到的 curve 仍是旧 LINEAR。
- [V] ModConfig.read() 对 `enableAutoDrs` 强制读 false（功能未实现，无视旧 JSON 的 true）。
  副作用：未来实现自动 DRS 时必须移除此强制 false，改回 `json.optBoolean(key, Defaults.ENABLE_AUTO_DRS)`。

### 测试/build 输出 tail（本次交接的真实输出）

```
$ ./gradlew :app:assembleDebug
> Task :app:compileDebugKotlin
> Task :app:assembleDebug
BUILD SUCCESSFUL in 3s
$ adb install -r app-debug.apk
Performing Streamed Install
Success
```

## 3. 决策与理由

- **UI 重构方案** [V]——线性踏板下拉、死区/过渡点条件显示、曲线拆分、exponent 0.42、底栏 padding、
  行间距统一、自动 DRS 强制关。全部编译通过、UI 层验证。否决：无。
- **curve exponent 改 0.42 不是根因** [V]——用户最初说"三个曲线不生效"，我误判为函数方向反了
  （旧 exponent≥1 是 ease-in）。真机实测证明：exponent 改对了方向，但运行时根本没用到新值——
  OverlayManager 缓存旧 settings，curve 仍是旧的。**函数方向是次要 bug，配置不流动才是主因。**
- **自动 DRS 强制读 false** [V]——功能未实现，避免老用户升级后开关显示"开"但无效果。
  副作用：未来实现时必须改回读真实配置值。

## 4. 失败的尝试 — 不要再试

- **仅改 curve exponent 方向无法让拟真生效** [V]——旧 exponent≥1（ease-in）确实方向反了，但真机
  实测拟真仍线性，根因是 OverlayManager 缓存 settings、不重读 JSON。修对 exponent 但运行时
  拿不到新 curve 值，等于没修。**不要再只改数学方向而不解决配置流动。**
- **`PedalOverlayView` 默认参数漏新字段** [V]——给 `ModConfig.Settings` 加/改字段后必须同步
  `PedalOverlayView` 默认参数构造。本次改了 pedalMode/throttleCurve/brakeCurve/brakePosition。
  （搬运自旧 HANDOFF，仍有效）
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

- **OverlayManager `settings` by lazy 缓存是运行时不生效的根因** [V]——游戏进程首次创建 overlay
  后，配置页改的 pedalMode/curve 不会流到 OverlayManager，必须重启游戏或改代码让它重读。
  **下次必须解决这个，否则任何配置改动在运行时都不生效。**
- **加/改 `ModConfig.Settings` 字段必同步所有构造点** [V]——`ModConfig.defaultSettings()`、
  `ModConfig.read()`、`ConfigMainScreen.remember{}`、`ConfigMainScreen.saveNow`、`PedalOverlayView` 默认参数。
- **`PedalCurve` 精简后旧值兼容** [V]——`from()` 必须处理 `quadratic` 旧值→EXPONENTIAL。
- **`PedalMode` 迁移必须处理旧 `enable_control_replacement`** [V]——`migratePedalMode()` 先看新 key，
  没有再从旧 bool 派生（true→SINGLE, false→OFF）。
- **`applyCurve` exponent 方向** [V]——exponent < 1 是 ease-out（先快后慢），> 1 是 ease-in（先慢后快）。
  拟真要前者。但这是次要 bug，主因是配置不流动（见上）。
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
- **`KEYSTORE_PASSWORD`/`KEYSTORE_ALIAS` 走 Gradle 默认值** [V]——真实密码=默认值 `alamobiletool`。
- **workflow `prerelease: true` 硬编码** [V]——正式版发 Release 时必须改 `false`。

## 6. 下一步（有序）

1. **修复 OverlayManager 配置不流动**（最高优先）——让 `settings` 不再 by lazy 缓存，
   或在 toggle/addGamingOverlays 前重新读配置。方案候选：
   - A. `addGamingOverlays` 开头 `val settings = ModConfig.readFromTargetProcess(appContext)` 重新读，覆盖成员变量；
   - B. `toggleOverlays`/`toggleEditMode` 触发 removeExisting + 重建，强制重读；
   - C. 给 OverlayManager 加 `refreshSettings()`，配置变更时调用（需 IPC 通知游戏进程，复杂）。
   推荐先试 A（最简单），验证 toggle 一次能否让新配置生效。
2. 验证修复后真机：切关/双踏板 → toggle → 控件正确变化；切拟真 → 实际手感非线性。
3. 若步骤 2 仍不生效，排查 `readFromTargetProcess` 在游戏进程读到的 JSON 是否真是新值
   （可能是 ConfigActivity 写的路径与游戏进程读的路径不一致——M6 改过路径逻辑，复核）。
4. （可选）真机冒烟测试全部通过后 commit + 发 Beta 2。
5. （可选）后续实现"手动换挡""自动 DRS"。

## 7. 留给用户的开放问题

- 配置页改动是否需要重启游戏才生效？还是期望 toggle 即时生效？（影响修复方案选择）
- 拟真曲线 exponent=0.42（30%→60%）的手感是否合适？（待配置流动修复后才能真机验证）
- 双踏板默认刹车位置（pedal 左侧 0.55f）是否合适？
