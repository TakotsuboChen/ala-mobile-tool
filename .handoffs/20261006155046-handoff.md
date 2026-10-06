# HANDOFF — 读全文再开始干活

生成时间: 2026-10-06T15:40:48+08:00 · Git HEAD: `793ce9c`
信任规则: [V] = 交接时已用命令验证；[?] = 仅记忆未复核，当线索对待；[X] = 已证伪，别用。

## 0. 复核（下一会话先做）
- 锚点: 模块 main @ `793ce9c`（工作 + 持久文档已 push，与 origin/main 同步）
- 漂移检查: `git rev-parse HEAD~1` 是否仍 = `793ce9c`（HEAD 必是本次 handoff 提交，其 parent 才是文档记录的 SHA）
- 先读: `app/src/main/kotlin/tools/alamobile/mod/util/VersionGate.kt` 头注释（版本判据的单一事实源）

## 1. 当前目标
**共存版打包修订号机制（versionName `Fix N` 后缀）已交付并实机验证通过。无进行中目标。**
同版本期间对共存包做改动（NPatch 升级、补权限）时，versionCode 必须与官版一致（偏移安全键 + 围场榜单分区键），
改用 versionName 后缀承载打包修订号，模块按包名分支判：官版精确匹配、共存版要求 `Fix N >= MIN_COEX_FIX(1)`。

## 2. 已验证状态 — 工作实际停在哪
- [V] **五切片已提交并 push**：`cb666be`（版本判据）→ `b980015`（门控日志修复）→ `4ae00c0`（NPatch Bug B 文档）→ `3a65824`（skill 4.8/6.7）→ `793ce9c`（持久文档）→ 本 HANDOFF.md。
- [V] **门槛全绿**：`./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease` → **BUILD SUCCESSFUL**（exit 0），0 lint error（修了 2 处 `getLongVersionCode` API 28 NewApi → 改 `PackageInfoCompat`）。
- [V] **共存版 `8.0.6 Fix 1` 已打包并推手机**：`build/v8.0.6-official/Ala Mobile 8.0.6 Fix 1 Takotsubo 共存版.apk`（629,928,350 B，md5 `f557602f75078e4874b3138e27fed8c4`，已推 `/sdcard/Download/`）。versionCode=200150 **未动**、versionName=`8.0.6 Fix 1`、VIBRATE 权限有、签名 `CN=AlaMobileTool`（指纹 `e0bc20a5…` 与旧包一致）、裸包（无 `assets/npatch/`）。
- [V] **实机验证通过**（NPatch 830 测试版 + 该共存版）：日志 `installed=8.0.6 (200150), coexOutdated=true` 拦截旧包；换新包后 `ShadowHook initialized` / `Early unlock hooks installed` 正常放行，无 `ForceUpdateGate active`。
- [V] **门控日志修复生效**：`game version MISMATCH → zero hooks ... coexOutdated=true` 已落盘 `/sdcard/Android/media/<pkg>/ala_tool.log`（修复前该行只在 logcat）。
- **工作区**: clean。
- ⚠️ **未做**：概览页两胶囊（`OutdatedBuild` 黄色「需更新」）的**实机目视确认**未做；新共存版尚未给终端用户分发。

### 测试/build 输出（本次交接 run 的真实输出）
```
$ ./gradlew :app:lint :app:assembleRelease -x :app:lintVitalAnalyzeRelease -x :app:lintVitalRelease
  → BUILD SUCCESSFUL in 7s          EXIT=0
$ adb install -r app/build/outputs/apk/release/app-release.apk   （模块 APK，1.0.4 Alpha 1 / 104100，版本号未动）
  → Success
```

## 3. 决策与理由
- **打包修订号走 versionName 后缀，versionCode 保持 200150** [V]——versionCode 同时是偏移安全键（错版装 hook 开屏闪退）和围场榜单分区键（服务端 `best_laps` 按 version_code 分赛季，官版/共存版必须同赛季），不能拿它区分新旧共存包。**否决方案 B（versionCode=806301）**：服务端要么加归一化 `806301→200150`（榜单才不分裂），要么榜单分裂成两赛季；且偏移安全要靠"8063xx=8.0.6"人工约定解码，不如 versionName 后缀直接。
- **官版判据保持精确相等（不接受 Fix 后缀）** [V]——官版 versionName 恒为裸 `"8.0.6"`；放宽成前缀匹配会放行未来官版的误配。
- **新引擎版本（8.0.7 等）无需 Fix 后缀** [V]——升 `SUPPORTED_VERSION_NAME/CODE` 即可，Fix 从 0 重算。Fix 后缀只服务"同引擎内又改动打包"这一种场景。
- **`Logger.init` 提到所有门控 return 之前** [V]——门控激活时旧位置在 return 之后，导致"被拦截"场景零文件日志；三个插入点全部只做轻量 `getAppContext()+Logger.init`（不碰 Resources），不破坏 vivo/OriginOS 闪退修复、不违反"判定前零 Hook"。
- **概览页 `OutdatedBuild` 用黄色不用红色** [V]——"该更新了"而非"出错了"，红色留给真正的版本错配（引擎不对，偏移危险）。
- **共存版分发用 NPatch 830 测试版** [V]（用户定案）——830 修了 Android 16 缓存未设只读问题（见 §5），1.0.8(801) 必崩。

## 4. 失败的尝试 — 不要再试
- [X] **versionCode 方案（806301）** [V]——见 §3；服务端要么归一化（806301 进不了库）要么榜单分裂。
- [X] **依赖"清游戏缓存"救 Android 16 白屏闪退** [V]——那是 NPatch Bug A（#147）的处方；Bug B 是权限位问题，清缓存重建后仍是 600，无效。只能升 NPatch 830+。
- [X] **`chmod 400` 缓存文件作终端用户 workaround** [V]——用户群 99% 无 root（NPatch 的存在意义就是免 root），不可行。
- [X] **在 AndroidManifest.xml 里改 versionName** [V]——apktool 2.7.0 把 versionName 存在 `apktool.yml` 的 `versionInfo`，manifest 里 grep `android:versionName` 零命中（skill 阶段 4.8 已修正为改 yml）。
- [X] 继承死路（详 `.handoffs/20261006132058-handoff.md` §4 及更早）：四个二级页各自 `viewModel<ConfigViewModel>()` / hook `OnLapChange` / 压缩 `Fallback` 叩击间隔 / 换更短 haptic 常量 / `HapticGenerator` / `pm grant VIBRATE` / 通道②`|Fy|/(μ·Fn)` / 通道④门控用 `carSpeed>2` / hook `HybridComponent.EnableOTK` / `viewBox` 非零原点照搬 / hook `drsToggle` / 直写 `_currentDRSState` / 整段 `md.disasm(detail=True)` / 编辑层尺寸=控件尺寸 / 变暗层插 index 0 / 踏板"单向补送" / `coroutineScope{}` 内多源竞速 / Remote Preferences `remove()` / 磁盘缓存原图字节 / 全局挂 `tnum` / `FontFamily.Monospace` / 用构造快照 `position` 做触摸基准 / `kerbTapWave` 垫底。

## 5. 已知坑
- ⚠️ **NPatch 缓存有两个症状极像、根因不同的 bug** [V]——**Bug A（#147）**= 缓存内容损坏（`Resources.getAssets()` NPE，任意 Android 版本，清游戏缓存即修，1.0.8 已修）；**Bug B**= 缓存权限可写（`SecurityException: Writable dex file`，Android 14+，清缓存**无效**，**NPatch 830+ 已修**）。用户报白屏闪退先读 `docs/NPATCH_CACHE_CRASH_NOTES.md` 顶部对照表。
- ⚠️ **共存版 versionCode 绝不可与官版脱钩** [V]——服务端榜单按 version_code 分赛季，脱钩会分裂；偏移安全也依赖它。
- ⚠️ **门控拦截路径的诊断日志** [V]——`Logger.init` 已在三个门控 return 之前，任何**新增**的门控 return 都要确保在其之后（否则又回到"拦截时零文件日志"）。
- ⚠️ **`crash_hook_register` 注册表容量 64** [V]（`HOOK_REG_MAX`）——当前约 40 余项，接近但仍有余量。
- ⚠️ **官版振感是平台级限制** [V]——`VIBRATE` 是 install-time 权限、`performHapticFeedback` 只接受 int 常量，任何机型/调参都改变不了（继承，未在本会话重验）。
- ⚠️ **禁止读设备私有的调试/寄存器节点** [V]（继承）——读动作型 sysfs 节点曾致本机 kernel panic。
- ⚠️ **存量用户配置不会被新默认值覆盖** [?]（继承）——`optString(KEY, default)` 只在键不存在时生效。
- ⚠️ **继承未验收项** [?]：概览页胶囊实机观感 / 金标播报实机触发 / 自动 DRS `playcar`(0x9C) 回落分支 / 自锁超车"抬起被吞"直接证据 / 崩溃自捕新增能力 / 路肩振感在其他品牌的手感 / 跨品牌适配实机验证。
- ⚠️ **对话纪律** [V]（继承）：1 逐条消化 mid-turn 消息 2 旧快照不当现在时 3 装机前验设备 APK 版本 4 调查日志先对齐"几次"计数 5 修 UI 时序先拉触摸时间线 6 **日志判不了的结论不要用日志反驳用户体感** 7 别在用户锁屏时操作设备 8 **动用户设备状态前先说明** 9 **不要主动提版本号** 10 **跑长耗时/带 timeout 的命令前必须先说明**。

## 6. 下一步（有序）
1. **（用户发话才做）** 把 `8.0.6 Fix 1` 共存版分发给终端用户；分发时附 **NPatch 830+** 的获取方式（否则用户在新系统上必白屏闪退）。
2. 概览页胶囊实机目视确认（需装新共存版后打开模块 App 看「共存版：8.0.6 Fix 1 已适配」绿色 / 旧包黄色「需更新」）。
3. 跨品牌适配实机验证（至少一台非魅族机）——官版 `Fallback` 与共存版 `Envelope` 的手感差异。
4. （可选）正式发版时**先问用户版本号**，再做 module.prop 对齐 + tag。

## 7. 留给用户的开放问题
- NPatch 830 是测试版——是等 1.0.9 正式版再分发，还是现在就用 830？（已定：**先用 830**）
- 要不要给 NPatch 上游报 Bug B？（若 830 是官方修复则不必；维护者 @nikobe918 已确认修了缓存未设只读）
- 概览页胶囊的「需更新」黄色观感是否合适？（`OutdatedBuild` 新增态，未目视确认）
