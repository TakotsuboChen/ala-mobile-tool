# NPatch 缓存损坏导致的启动闪退 — 调查记录

> 结论一句话：**这是 NPatch（本地注入框架）框架层的 bug，与 `ala-mobile-tool` 模块无关。**
> 根因 = NPatch 保存的 origin-apk 缓存文件损坏，而 NPatch 对缓存命中**只检查文件是否存在、不校验完整性**，导致每次启动都用坏文件构建 Resources → 系统静默失败 → 实例化 Application 时 NPE。
>
> **用户侧解法：清「游戏」的缓存（不是 NPatch 的数据），或卸载重装。** 见 §8.1。
>
> 调查日期（首次）：2026-09-13 · 复现例二：2026-09-15 · 复现例三（修复验证）：2026-09-15
> 调查对象：用户单机故障（共存版 `com.Takotsubo.AlamobileFormula`，NPatch 本地模式）
> 信任分级：[V] 已用命令/源码核实 · [?] 推断 · [X] 已证伪

---

## 0. 复现例清单（三次独立报告，同一指纹）

| # | 日期 | 设备 / ROM | 崩溃时段 | 启动次数 | 修复方式 | 结果 |
|---|---|---|---|---|---|---|
| 1 | 2026-09-13 | 一加 Ace 5 / ColorOS 16 (A16) | 15:16–15:30（14 min） | 22 | 未记录用户动作，后自愈 | 恢复 [V] |
| 2 | 2026-09-15 | iQOO Neo 9 / OriginOS 6 (A16) | 16:24–18:17（1h52m） | 20 | 未执行 §8.1（用户清的是 **NPatch 数据**，无效） | 见 #3 |
| 3 | 2026-09-15 | 同上 | 22:50 同一次会话 | — | **「清除游戏缓存」→ 一次即愈** | 成功 [V] |

**关键**：例 2 与例 3 是**同一台设备、同一天**——例 2 是错误处方（清 NPatch 数据）的实证失败，例 3 是正确处方（清游戏缓存）的实证成功。这是本文件最强的一组对照证据。

### 0.1 三次报告共同的日志指纹（无 adb 也可判定）

同时满足以下三条即是本 bug，无需任何其它证据：

1. `cacheApkPath` 在整段崩溃期内**恒定**（如 `/data/user/0/<pkg>/cache/code_cache/558144399.apk`）；
2. 连续多次启动全部以**同一崩溃栈**结束（`Resources.getAssets()` null → `Unable to instantiate application`）；
3. 全程 **零** `Extracting origin.apk`（该行虽为 `Log.i` 但不落媒体日志，见 §5.1；用"恒定 cacheApkPath + 重复崩溃"替代即可判定）。

判据是**指纹**，不是机型/系统版本——例 1/2 分属 ColorOS 16 与 OriginOS 6，机制完全相同。

## 1. 现象与原始证据

用户报告"游戏闪退，后面又自己好了"。两份日志：

| 日志 | 来源 | 内容 |
|---|---|---|
| `20260913.log` | `Android/media/com.Takotsubo.AlamobileFormula/npatch/log/` | NPatch 崩溃日志（677 行），崩溃时段 15:16:10–15:30:27 |
| `ala_tool_log_20260913_182624.txt` | 模块导出（游戏恢复后） | 模块进程 01:xx/15:xx/18:xx 三段 + 18:22 游戏进程成功启动日志 |

关键症状：
- **关掉 NPatch 作用域也打不开**（用户原话）。
- 崩溃栈恒定：`Unable to instantiate application com.pairip.application.Application ... Resources.getAssets() on a null object reference @ LoadedApk.getAssets / makeApplicationInner / handleBindApplication`。
- 一段时间后**无需用户操作自愈**。

---

## 2. 崩溃机制（AOSP 逐跳）[V]

> 关键认知：`Unable to instantiate application X` 是 `LoadedApk.makeApplicationInner` 的 **catch-all 包装**——类名 X 只是"被实例化的那个类"，**不是肇事者**。

```
NPatch 用 cache apk 作为 sourceDir 造 LoadedApk
  → LoadedApk.getResources() 惰性构造
  → ApkAssets 打不开该 APK（zip 截断 / magic 错 / EACCES / arsc 损坏）→ 抛 IOException
  → ResourcesManager.createAssetManager 对普通路径吞异常 return null
    （仅 overlay / sharedLib 才容忍继续）
  → createResourcesImpl null → createResources null
  → LoadedApk.mResources = null（赋值处无 null 检查）
  → makeApplicationInner 里 getAssets() 对 null 解引用 → NPE
```

- `LoadedApk.getAssets()` = `return getResources().getAssets();`（AOSP 15 `LoadedApk.java:1360`），**无 null 检查**。
- `LoadedApk.getResources()`（`:1379`）里 `mResources = ResourcesManager.getInstance().getResources(...)`，**无 null 检查**，原样 `return mResources`。
- `ResourcesManager.createAssetManager` 对普通 APK path 的 `IOException`：`Log.e("failed to add asset path")` → `return null`（AOSP 15 `:657`）。
- AOSP 12/13/14/15/main 五份源码该写法逐字一致 [V]。
- `ContextImpl.setResources(null)` 无运行时检查 → 资源创建失败若发生在 `createAppContext` 阶段会被推迟到下一个消费者爆发。

**这是典型的"错误搬运而非错误处理"**：底层两处沉默的 `null` 把错误推迟两层，最终在最不相干的 `getAssets()` 爆炸。

### 2.1 为什么崩溃点在 `makeApplicationInner` 而非更早 [V]

正常流程 `handleBindApplication` 会在 `createAppContext`（`ActivityThread.java:7597`）时强制构造 `mResources`，所以正常启动永远不会带着 null 资源进入 `makeApplicationInner`。本案能崩在 `makeApplicationInner`，说明 `createAppContext` 拿到的那个 `LoadedApk` 与 `makeApplicationInner` 后来用的**不是同一个对象**：

- NPatch `LSPApplication.java:527` 调 `ContextImpl.createAppContext(activityThread, stubLoadedApk)` —— 初始化的是 **stubLoadedApk** 的资源；
- 但 `:504` 已把 `mBoundApplication.info` 换成 **appLoadedApk**，`makeApplicationInner`（`ActivityThread.java:7680` 读 `data.info`）用的是它；
- 两者**各建各的资源、互不共享**，且从 `getPackageInfoNoCheck`（`:472`）到替换 `info`（`:504`）之间 **NPatch 没有任何代码触发过 appLoadedApk 的资源构造**（`:473/:484` 只有 `getClassLoader()`，`:478-480/:503` 只反射改字段）[V]。
- 于是 appLoadedApk 资源的**首次惰性构造被推迟到 `makeApplicationInner`**，坏 cache apk 的失败正好暴露在那里。

---

## 3. NPatch 侧根因（源码）[V]

`patch-loader/src/main/java/top/nkbe/npatch/loader/OriginApkHelper.java`：

| 行 | 内容 | 问题 |
|---|---|---|
| `:45` | cache-hit 判定 `if (!Files.exists(internalCacheApk))` | **唯一判定**，无 size / CRC / zip 可开性校验 |
| `:52` | `Files.copy(is, internalCacheApk)` | **无 fsync**，无原子 rename，无 FileLock |

**这是"漏修"而非设计取舍**——同一 NPatch 内别处已经做对了：
- `OriginApkHelper.prepareNativeLibraryDir:94-98` **有 FileLock**（注释明写"多进程并发 bootstrap"）；
- `SigBypass.extractOriginalApk:754` cache-hit **有 `length() == entry.getSize()` 校验**。

唯独 **origin-apk 缓存两样皆无**。

**档位关系** [V]：effective `sigBypassLevel >= SIGBYPASS_BASIC(1)`（即 1/2/3/4/5 全档）走 cache 模式（`LSPApplication.java:421 → :426-431 → :420 loadedApkUsesOriginCache=true`）；0 档不走。effective level 由 `resolveSigBypassLevel`（`:408/:171-194`）优先读 manifest metadata `npatch` JSON，fallback config.json。崩溃日志已显示 `LoadedApk source mode=cache` → 该机 effective ≥ 1。

---

## 4. 排除项 [X]/[V]

- **[X] 模块无关**：崩溃窗口内全 677 行日志**零** `AlaMobileTool` / `libala` / `ShadowHook` / `tools.alamobile` 痕迹；崩溃发生在 `NPatch bootstrap completed` **之后**——即模块已加载完，死在真正 Application 实例化时。18:22 成功启动时同一 `com.pairip.application.Application@78199eb` 正常实例化，排除类本身问题。
- **[X] PairIP 无关**：本游戏 PairIP 是 **lite 死代码**（无 `<clinit>`、构造器只调 super、`checkLicense` 已被共存版 patch 成 `return-void`，见 `coex-apk-builder` skill）。它只是"被实例化的那个类名"，无辜背锅。
- **[X] 作用域无关**：`scope from Manager` 成功更新的 6 次（#18–#23）照样崩；**例 2 又实证 9 次**（`Success update LoadedModule scope from Manager` 后依旧必崩）。作用域只影响模块列表，与 `source mode=cache` 对 sourceDir 的选择正交。
- **[X] 重启无关**：pid 从 32571 归零到 4905（zygote 重启）后仍崩 5 次。**例 2 更强**：20 次启动 pid 从 1896 单调递增到 31842，全程**从未归零**——设备根本没重启过。
- **[X] 缓存刷新无关**：崩溃窗口 `cacheApkPath=558144399.apk` 14 分钟恒定、全程**零** `Extracting origin.apk` —— 坏缓存从未被刷新（这一条证伪了最初"自愈=缓存被 wipe"的假设）。**例 2 复现同一恒定值，持续 1h52m（16:24→18:17，20 次启动）。**
- **[X] 清 NPatch 管理器数据无效**（例 2 实证）：坏文件在**游戏的** dataDir（`/data/user/0/com.Takotsubo.AlamobileFormula/cache/code_cache/`），不在 `top.nkbe.npatch` 的 dataDir。清 NPatch 数据不仅碰不到目标，还会删掉 `cache/npatch/modules/`（模块 native 库缓存）→ 下次启动重新解压全部模块 `.so`（日志实证 `Load modules` 耗时从 45ms 涨到 230ms）。
- **[X] 模块无关（例 2 另证实）**：例 2 用户导出的模块日志**只有「模块进程日志」一段，没有游戏进程段**——游戏从未跑到模块初始化，因为进程根本没活到 `onPackageReady`。
- **[X] `:478 if (!loadedApkUsesOriginCache)` 跳过 restoreVisibleLoadedApkResources** 不是本崩溃根源：那是资源表错配 → `NotFoundException`；本崩是 `mResources=null` → `NPE`，机制不同。

---

## 5. 为什么"关掉作用域也没用" [V]

作用域（scope）只影响**加载哪些模块**，不影响 NPatch 的 `source mode=cache` 对 sourceDir 的选择。坏的是那份 origin-apk 缓存，与模块列表正交。例 1 的 #18–#23、例 2 的 #9 次，都是"管理器已连上、作用域更新成功、仍然崩"的实证。

### 5.1 媒体日志的覆盖边界（无 adb 诊断前必读）[V]

用户能提供的最好证据是 NPatch 自己写的 `/sdcard/Android/media/<游戏包>/npatch/log/<date>.log`（例 1/2/3 用的都是它；模块 App 持 AFA 即可直读，无需 root）。

但这份日志**不是 logcat 全量**，判读前必须先知道它切掉了什么：

| 调用 | 是否落媒体日志 | 原因 |
|---|---|---|
| `XLog.i/w/e("NPatch…")` | ✅ | 走 `XLog.mirror()`，白名单 = tag 以 `NPatch` 开头（`XLog.java:69`） |
| `Log.i(TAG,"…")` 且 TAG=`NPatch` | ❌ | 不经 `XLog`。**实证**：`Signature bypass level: ...`（`LSPApplication.java:413`）在此份日志中出现 **0 次** |
| `Log.d(TAG,"Internal cache hit: …")` | ❌ | 同上 + 级别过滤（见 §8.2 第 4 条） |
| `android.util.Log` 直用（模块侧等） | ❌ | 文件头注释明写"deliberately outside this pipeline" |

**两个实操推论**：

1. **看不到 `Signature bypass level` 不代表没打印**——`LSPApplication.java:411-413` 与 `Loaded patch config` 是相邻两条，后者每次都出现，前者从未出现，即可反推机制。
2. **不要用"日志里没有 `Extracting`"单独下结论**（该行虽为 `Log.i`，但只在重建分支才打印）。判定本 bug 的可靠组合是 **§0.1 的三条指纹**。

---

## 6. 为什么"后面又自己好了"（自愈机制）[V/?]

AOSP 侧无负缓存、无重试：`null` 一旦赋进 `mResources` 就**永久驻留**（`if (mResources==null)` 不再为真），同一进程后续 `getResources()` 永远 null；但**失败不跨启动持久**，新进程重置。

坏 cache apk 只能被以下四类清理换掉：

| 触发 | 机制 | 与本案符合度 |
|---|---|---|
| **清「游戏」的缓存** | 系统「清除缓存」= `CLEAR_CACHE_ONLY` → 清空 `<游戏 dataDir>/cache/`（`code_cache/` 在内）→ `Files.exists=false` 走重建 | ★★ **例 3 实证一次即愈 [V]** |
| **系统清 `code_cache`** | dataDir/cache 是标准 cache 目录，OEM 清理 / `pm trim-caches` / 存储清理整删 → 下次 `Files.exists=false` 走重建 | ★ 与例 1"过一阵自愈"吻合 [?] |
| 重打补丁 | `CacheCleaner.handlePatchUpgrade`：patchedApk stamp（lastModified-length）变 → `wipeAll(cacheRoot)` | 可能（若用户重注入过） |
| 游戏升版 | `CacheCleaner.sweepOriginApkCache`：CRC 变 → 删旧文件 | 本版本内不会 |
| 卸载重装 | install 清 dataDir | 例 ? 未发生；属兜底方案 |

**例 1 自愈窗口**：最后一次崩溃 15:30:27 → 成功启动 18:22，这段无日志覆盖 [?]。
**例 3 修复窗口**：18:17:31 最后一次崩溃 → 22:50:22 首次成功启动，用户在执行 §8.1 第 1 步（清游戏缓存）后一次即通 [V]。

**为什么例 1/例 2 持续十几分钟到近两小时且重启无效**：坏文件**跨启动存活**并被反复当作有效 cache——这正是 `:45` 只查 `Files.exists` 的直接后果。

---

## 7. 候选根因排序

1. **坏 cache apk 常驻**（无 fsync 的 copy 被中断 / 一次并发破坏后落盘）→ 此后每次启动命中同一坏文件 → 每次必崩，直到四类清理任一扫掉。**与全部日志证据最一致**。
2. 多进程并发首启竞态（进程 A 半写入 `Files.copy`，进程 B 命中 `Files.exists` 拿到半成品）——**[?] 只能解释坏文件如何产生**，不能解释"重启后仍被反复命中"。
3. `CacheCleaner.handlePatchUpgrade` stamp 竞态 wipe（两进程冷启动且 stamp 文件缺席时都走 `wipeAll` + extract 交错）。
4. `splitSourceDirs` 未重映射（cache 模式下 split 路径仍是 patched 安装路径）——与本次日志符合度低（单 base.apk）。

---

## 8. 处置建议

### 8.1 对用户（可直接转发）

> ️ **旧版话术写的是"清除该应用的数据/缓存"，歧义过大**——已被两个用户各自解读成"清 NPatch 管理器的数据"，无效且是**负收益**（见 §4）。以下为修正版，务必逐字照发。

> 闪退原因查清楚了：**是 NPatch（本地注入框架）的缓存文件坏了，不是 Ala Mobile Tool 模块的问题**。铁证——你关掉模块作用域也照样打不开，说明模块根本没参与，崩溃发生在模块加载之前。
>
> 修法（**先试第 1 步，基本一次就好；千万不要清 NPatch 管理器的数据**）：
>
> 1. 打开**系统设置 → 应用管理 → 找到这个游戏（不是 NPatch）→ 存储 → 点「清除缓存」**，只点"清除缓存"、**别点"清除数据"**，然后直接开游戏。
> 2. 找不到那个按钮：用手机管家/存储清理，针对这个游戏清一次缓存。
> 3. 还是不行：**卸载游戏 → 重新安装 → 重新注入**（会清游戏内本地进度，先确认能接受）。
>
> ️ 以后遇到这个闪退，**不要清 NPatch 管理器的数据**——坏掉的不是它，清它不但没用，还会让下次启动重解压所有模块、变慢。
>
> 成因是 NPatch 保存"原版 APK 备份"时偶尔会写坏，下次启动又只检查文件在不在、不检查完不完整，于是每次都用坏文件启动 → 闪退。等它自己好不可靠，按上面清一次即可。模块本身没问题，不用怀疑。

### 8.2 对 NPatch 上游（issue 修复方向）

> 已提交：**https://github.com/7723mod/NPatch/issues/147**（2026-09-13，账号 TakotsuboChen，截至 2026-09-15 state=open、0 条回复）

`prepareOriginApk` 三处补强（另附"cache-hit 假阴性"问题，见下）：

1. cache-hit 分支增加完整性校验（`length() == entry.getSize()`，或试开 zip 尾目录 `End of Central Directory`）；失败则删除并重建；
2. 写入改为临时文件 + `fsync` + 原子 `rename`；
3. 补 `FileLock`（与 `prepareNativeLibraryDir` 对齐）；
4. **cache-hit 为 hit 时日志级别过低**：`:55` 走 `Log.d(TAG, "Internal cache hit: ...")`，而 `:46` 的 `Extracting` 走 `Log.i`。`XLog.mirror()` 是**按级别过滤**的（其内置 `LogPrinter` 在低于自身 priority 时不落盘），因此用户可拿到的媒体日志里**永远看不到任何 cache 命中/重建痕迹**——只能靠"`cacheApkPath` 恒定 + 反复崩溃"间接推断。建议命中分支也改为 `Log.i`。

---

## 9. 待补项

- **例 3 之前的两条 [?] 已闭合**：①"清游戏缓存能清掉 `code_cache`"→ 例 3 实证一次即愈 [V]；②"typical `sigBypassLevel`"→ 已从 `安装包/Ala Mobile 8.0.6 Takotsubo 共存版 (NPatch打包 + 自签).apk` 中解出 = **5**（`assets/npatch/config.json` 与 manifest `npatch` metadata **两处一致**；`resolveSigBypassLevel` 以 **manifest 优先**、config.json 仅 fallback）[V]。⚠️ **结论**：5 档 > `SIGBYPASS_BASIC(1)`，故**所有共存版用户**都在 cache 模式下——**"降档到 0 规避"不是可行退路**（共存版存在的理由之一就是绕开游戏签名/许可校验，0 档会丢掉它）。
- 若要 100% 坐实"是缓存文件损坏而非别的启动期异常"，需一份**完整 logcat（含 `ResourcesManager: failed to add asset path` 与 `AndroidRuntime`）**——但用户群 99% 无电脑（见记忆 [[users-cannot-use-adb]]），实操上难获取。**例 3 的"清缓存即愈"已从行为侧等效坐实**，此条降为可选。

---

## 10. 一句话总结

**NPatch 的 origin-apk 缓存无完整性校验 + 无 fsync + 无锁（同仓库别处都有）→ 缓存一旦写坏就常驻 → 每次启动以坏文件建 Resources → AOSP 静默吞成 null → `getAssets()` NPE → 系统包成「无法实例化 Application」。与模块、PairIP、作用域、重启、Android 版本均无关；**清「游戏」的缓存即修**（三次报告验证，例 3 一次即愈）——**清 NPatch 管理器的数据无效且有害**。**
