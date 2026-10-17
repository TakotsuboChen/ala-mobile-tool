# NPatch 缓存问题导致的启动闪退 — 调查记录

> ⚠️ **本文件记录三个不同的 NPatch 版本问题，症状各异、根因不同，排查前先对号入座**：
>
> | | Bug A（#147，§1–§10） | Bug B（Android 16 W^X，§11） | Bug C（服务回落缺失，§12） |
> |---|---|---|---|
> | 症状 | **开屏白屏闪退** | **开屏白屏闪退** | **不崩溃，模块静默不加载** |
> | 崩溃栈 | `Unable to instantiate application` → `Resources.getAssets()` **NPE** | `Unable to instantiate application` → `SecurityException: Writable dex file ... is not allowed` | **无崩溃栈**（进程正常起，只是没 hook） |
> | 根因 | 缓存 **内容损坏**（无完整性校验/无 fsync/无锁） | 缓存**权限可写**（未设只读） | 管理器不在后台时**模块清单能加载、但服务未就绪**（`VectorServiceClient.service` = null ⇒ 模块不实例化） |
> | 触发条件 | 任意 Android 版本 | **Android 14+（本机 Android 16 实证）** | **NPatch 1.0.8+ 本地模式 + 管理器未运行** |
> | 用户侧解法 | **清「游戏」缓存**（不是 NPatch 数据） | **无效** → 只能升级 NPatch 到 **830+**（已修） | **先把 NPatch 管理器挂后台再开游戏**（治标）；根治等上游修服务层 |
> | 上游状态 | 1.0.8 已修（原子写 + 锁） | **830 测试版已修** | **836 存在**；**843 测试版已修**（上游提交 `bbd8897` "keep framework service binder local"，见 §12.8） |
>
> **一句话区分**：崩溃栈里出现 `Writable dex file ... is not allowed` = Bug B；出现 `Resources.getAssets()` null = Bug A；**不崩溃但模块零日志、logcat 里 NPatch 有 `Loaded cached module` 却无 `Module loaded in process`、且有 `VectorServiceClient service is not ready`** = Bug C。

> 结论一句话（Bug A）：**这是 NPatch（本地注入框架）框架层的 bug，与 `ala-mobile-tool` 模块无关。**
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

---

## 11. Bug B：Android 16 W^X「可写 dex」拒绝加载（2026-10-06 新增）

> **与 §1–§10 的 Bug A（#147）是两回事**：症状同（都崩在 `Unable to instantiate application`），
> 但 Bug A 是缓存**内容坏**、Bug B 是缓存**权限可写**。⚠️ **Bug B 清游戏缓存无效**（重建后权限仍是 600）。

### 11.1 现象与证据链 [V]

| 证据 | 值 |
|---|---|
| 崩溃栈 | `RuntimeException: Unable to instantiate application ... : SecurityException: Writable dex file '/data/user/0/<pkg>/cache/code_cache/<hash>.apk' is not allowed`（`Caused by` 在 `dalvik.system.DexFile.openDexFileNative`） |
| 设备 | MEIZU 20 / Flyme 12.6 / **Android 16（API 36）** |
| 缓存 APK 权限 | `-rw-------`（**600，owner 可写**） |
| 崩溃时刻 | 装新包后首次启动；**旧缓存（1.0.7 写的）启动正常** |
| 修复验证 | NPatch **830 测试版**：同一文件权限变 **`-r--------`（400，只读）** → 启动成功、模块正常加载 [V] |
| 上游确认 | 维护者 @nikobe918 确认 830 **修了"缓存未设只读"** [V] |

### 11.2 机制 [V]

Android 14+ 的 **W^X（Write xor Execute）** 规则：**dex 文件若位于应用可写目录（如 `cache/code_cache/`），就不能带写权限位**。NPatch 把 `origin.apk` 解包到游戏的 `cache/code_cache/` 时若保留可写权限，Android 16 的 `DexFile.openDexFileNative` 直接抛 `SecurityException` → Application 实例化失败 → 白屏闪退。

**为什么是"修 A 引入 B"的回归**：NPatch 0.7.4 的更新日志明写「**自動將快取後的 APK 設為「唯讀」狀態**」——即**曾经设过只读**。1.0.8 为修 #147 重写了 origin.apk 写入路径（临时文件 + fsync + 原子 rename），**把 `setReadOnly()` 那步弄丢了** → Android 16 上必崩。**与打包版本、模块、PairIP、作用域均无关**（崩在模块加载之前）。

### 11.3 用户侧处置 [V]

- **唯一可行解法 = 升级 NPatch 到 830+**（清缓存/清数据**均无效**——权限由框架写入决定，重建后仍 600）。
- ⚠️ **不能靠"清游戏缓存"救**（那是 Bug A 的处方）——这正是两 bug 必须区分的原因：误用 Bug A 处方会让用户白折腾。
- 用户群 99% 无 root（见记忆 [[users-cannot-use-adb]]），**`chmod 400` 这个 workaround 对终端用户不可行**，只能等/用修复版 NPatch。
- 判定指纹（无 adb）：崩溃栈含 `Writable dex file ... is not allowed` 即本 bug，与 Android 版本强相关（14+，本机 16 实证）。

### 11.4 与 Bug A 的对照速查

| | Bug A（#147） | Bug B（本节） |
|---|---|---|
| 崩溃栈关键行 | `Resources.getAssets()` **NPE** | `SecurityException: Writable dex file` |
| 根因 | 缓存内容损坏 | 缓存权限可写 |
| 触发系统 | 任意 | **Android 14+** |
| 清游戏缓存 | **有效** | **无效** |
| 解法 | 清游戏缓存 / 重装 | **升级 NPatch 830+** |
| 上游状态 | 1.0.8 已修 | 830 测试版已修 |

---

## 12. Bug C：管理器不在后台时模块静默不加载（服务未就绪，2026-10-16 新增）

> ⚠️ **本节结论经两轮修正**：初判"836 删了 NeoLocal 回落链"是**错的**（被 logcat 实证推翻）。真实根因见 §12.3 —— **缓存被读了、模块清单也加载了，但模块服务未就绪 ⇒ 模块不实例化**。

### 12.1 现象与用户话术 [V]

用户（一加平板 2 Pro / OPD2413 / **ColorOS 16 / Android 16 / API 36**，NPatch **1.0.9 (836)** 本地模式，**KernelSU root**）报告：

> 「NPatch 不挂后台就失效，好像跟 1.0.8 区别不大？貌似以前 1.0.7 的时候不这样」

**症状**：NPatch 管理器进程不在后台时启动游戏 → **游戏正常启动、不崩溃**，但**模块完全没加载**（overlay / 解锁 / native hook 全无，游戏侧 `ala_tool.log` 零行）。把 NPatch 管理器挂到后台再启动游戏 → 模块正常。

### 12.2 诊断证据链 [V]

**受控实验（同设备/同包/同模块，唯一变量 = 启动游戏时管理器进程是否存活）**：

| 实验 | 管理器状态 | 结果 |
|---|---|---|
| A（复现 ×2） | **死**（缓存已写） | **失败 2/2** |
| B（对照 ×2） | **活** | **成功 2/2** |
| C | 死，但**刚成功启动过**（缓存必已写） | **失败** |

**进程内存实证（`/proc/<pid>/maps`，root）**：成功态游戏进程 maps 有 **2 行 `tools.alamobile.mod/base.apk`**（模块被 mmap）；失败态 **0 行** ⇒ 模块代码从未进进程。

**缓存文件实证（`/data/data/<游戏包>/shared_prefs/npatch.xml`，root）**：`modules` 字段**有完整内容**（模块路径），失败启动前后**未被覆写**（`last_fallback_reason = "bind_timeout"`）⇒ **排除"缓存为空/被覆写"**。

**logcat 实证（root 抓全缓冲区，ColorOS 下 NPatch 的 `android.util.Log` 只进 logcat 不落文件）—— 决定性对照**：

| 标记 | 失败态（管理器死） | 成功态（管理器活） |
|---|---|---|
| `Loading modules from local cache: [...]` | ✅ **有** | ✅ 有 |
| `Loaded cached module tools.alamobile.mod` | ✅ **有** | ✅ 有 |
| `Manager not ready in time; starting with local cached modules` | ✅ | — |
| `Manager binder connected and registered successfully` | ❌ | ✅ |
| **`VectorServiceClient service is not ready ...`** | **✅ ×2** | ❌ |
| **`AlaMobileTool: Module loaded in process`** | **❌** | **✅** |

**⇒ 缓存清单加载成功，但模块类从未实例化** —— 断点在"服务未就绪"。

**另有一条 ColorOS 系统日志（解释了为何管理器连不上）**：
```
I OplusAppStartupManager: prevent start top.nkbe.npatch, cmp {...manager.ModuleService} by com.Takotsubo.AlamobileFormula ... Type bs
```
`Type bs` = bindService —— **ColorOS 关联启动管控拦截了游戏对管理器的 bindService**（这是 ColorOS 侧行为，非 NPatch 独有；Linux 下无此拦截）。

### 12.3 机制（真实根因）[V/源码]

**1.0.7 vs 1.0.8+ 的关键差异 = `RemoteApplicationService` 构造函数是否抛异常**（逐版本 diff 源码，`references/NPatch` 已 unshallow + fetch tag）：

| 版本 | 构造函数 | 管理器不可达时 | `LSPApplication` 的 `if (service == null)` 兜底 |
|---|---|---|---|
| **1.0.7** | `throws RemoteException` | **抛异常** → 上层 catch → `service = null` | ✅ **成立** → 走 `NeoLocalApplicationService`（离线可用）⇒ 清单**+服务**都有 ⇒ 模块加载 |
| **1.0.8/1.0.9/836** | **不抛**（架构重写） | 构造成功 → `service != null`（内部 binder 未连） | ❌ **永不成立** → 停在 `RemoteApplicationService` ⇒ 有清单**无服务** ⇒ 模块不实例化 |

**836 的确切失败链**：
1. `RemoteApplicationService` 构造**不抛异常**（架构重写后不再抛）⇒ `LSPApplication` 第 313 行 `if (service == null)` **不成立**
2. 构造内部：`bind_timeout` → `loadModulesFromCache()` **成功读到清单**（日志 `Loading modules from local cache` / `Loaded cached module`）→ `applyLocalCachedModules()` 把清单存进 `cachedModuleScope`
3. `Startup.initXposed(..., service)` 传的 `service` = `RemoteApplicationService`，其内部 binder 未连 ⇒ **`VectorServiceClient.INSTANCE.service` 为 null**
4. `LSPLoader.initModules()` → `registerModuleRuntimeAppInfos()` 反射取 `VectorServiceClient.service`，为 null → `Log.w("service is not ready ...")` → **return，模块注册/实例化全部跳过**
5. ⇒ 模块清单在，但模块类从未实例化 ⇒ 游戏进程无模块代码（maps 0 行）

**上游已两次尝试修复但均不彻底**（公开仓库 commit）：
- `a9dc4619`（v1.0.9 tag，10-06）："管理器被關閉時以本地快照啟動" —— 改 `RemoteApplicationService` 内部（800ms 超时 + 缓存预载），**但没改 `LSPApplication` 的 `if (service == null)` 判据** ⇒ 服务层面仍缺失
- `c712f4e3`（10-06）："綁定管理器逾時時向 ConfigProvider 取最新模組範圍" —— 仍只处理**清单**，未处理**服务**

⇒ **两次修复都在"清单"层面，未触及"服务未就绪"这个真正的断点**。

**版本对应（供上游对照）**：用户设备 verCode=836；公开仓库 HEAD `31301d7c`（TG release notes 明列 #158+#160 = 这两个 commit）代码内容 ≈ 836，仅 commit 计数环境不同（私有 dev 仓库 836 vs 公开镜像 831）。

### 12.4 影响面 [V]

- **仅影响 NPatch 本地模式**（`useManager=true`）——即**绝大多数终端用户**（非 root 走 NPatch，见记忆 [[users-cannot-use-adb]]）。
- **与模块、共存包、PairIP、作用域、Android 版本均无关**——纯 NPatch 框架层服务回落回归。
- **与 Bug A/B 的区别**：A/B 是**崩溃**（白屏闪退），C 是**静默不加载**（游戏能玩，只是没模块）——用户感知完全不同，容易误判为"模块 bug"。
- 版本相关性：**1.0.7 无**，**1.0.8 起有**（用户原话"跟 1.0.8 区别不大"），**836 确定存在**。

### 12.5 用户侧处置 [V]

- **治标（立刻可用）**：**每次开游戏前，先把 NPatch 管理器 App 打开一次**（挂后台即可，不必停留）。管理器进程存活 ⇒ binder 连上 ⇒ 服务就绪 ⇒ 模块加载。
- **治本**：等上游修复（见 §12.6）。用户当前 836 无法通过设置绕过。
- ⚠️ **不要建议用户"清缓存/清数据"**——那是 Bug A 的处方，对 C 无效且有害（会清掉模块 native 缓存）。

### 12.6 上游 issue 草稿（Bug C）

> **标题**：Local mode: module not instantiated when manager not running (regression since 1.0.8) — `VectorServiceClient.service` null in the `bind_timeout` fallback path
>
> **环境**：NPatch 1.0.9 (836) local manager mode, Android 16 (API 36), ColorOS 16, OnePlus Pad 2 Pro (OPD2413)
>
> **复现**：
> 1. 杀掉管理器进程（`am force-stop top.nkbe.npatch`）
> 2. 启动已 patch 的目标 App
> 3. → 目标 App 正常启动、**不崩溃**，但模块不注入（无 hook、无模块日志）
> 4. 反之先启动管理器（保持后台）再启动 → 模块正常注入
>
> **关键日志（logcat，失败态）**：
> ```
> I NPatch: Loading modules from local cache: [{"path":".../tools.alamobile.mod.../base.apk","packageName":"tools.alamobile.mod"}]   ← 缓存读到了
> I NPatch: Loaded cached module tools.alamobile.mod                                                                                  ← 清单加载了
> W NPatch: Manager not ready in time; starting with local cached modules
> W NPatch-Loader: VectorServiceClient service is not ready for native LoadedModule proxy        ← 断点
> W NPatch-Loader: VectorServiceClient service is not ready for LoadedModule path compatibility  ← 断点
> ```
> （**无** `AlaMobileTool: Module loaded in process`；成功态有，且**无** "service is not ready"）
>
> **根因**：`LSPApplication` 的 `if (service == null)` 兜底（→ `NeoLocalApplicationService`）在 1.0.8 起**永不触发** —— 因为 `RemoteApplicationService` 构造函数从 `throws RemoteException` 改成了不抛异常，`bind_timeout` 时 `service` 仍非 null，但其内部 binder 未连 ⇒ `Startup.initXposed(..., service)` 使 `VectorServiceClient.service` 为 null ⇒ `LSPLoader.initModules()` 里 `registerModuleRuntimeAppInfos()` 提前 return，**模块清单有、服务没有 ⇒ 模块不实例化**。
>
> **期望**：`bind_timeout` 分支下，`service` 应能在服务层提供离线实现（如 1.0.7 的 `NeoLocalApplicationService`），或让 `VectorServiceClient` 接受从缓存加载的模块清单 —— 否则清单加载无意义。

### 12.7 一句话总结

**Bug C = NPatch 1.0.8+ 本地模式下"缓存兜底只兜了模块清单、没兜模块服务"**：管理器不在后台时，清单能从缓存加载，但 `VectorServiceClient.service` 为 null ⇒ 模块**永不实例化**（1.0.7 因构造函数抛异常而走 `NeoLocalApplicationService`，清单+服务齐全故正常）。**用户治标 = 开游戏前先开一次管理器；治本 = 等上游修服务层兜底。**

### 12.8 上游修复（843 测试版，2026-10-17）[V]

作者（`HSSkyBoy` / Neko Beillc）在 #154 回复中确认根因成立，并说明修法：

> 修法上我不打算照建議換成 NeoLocalApplicationService，因為那樣會丟掉 bind_timeout 之後的 Provider 對帳和背景重連升級。我會讓 RemoteApplicationService 對外提供一個穩定的本地 binder，這樣管理器晚點連上或中途被殺，都不會讓核心的 service 變成 null。

**对应提交**（私有 dev 仓库，公开仓库尚未同步）：

| 提交 | 内容 |
|---|---|
| `bbd8897` | `fix(loader): keep framework service binder local so modules load without the manager` ← **Bug C 主修** |
| `6872a71` | `fix(manager): warn when diagnostic export cannot see patched app logs`（诊断导出加告警） |
| `cc60416` | `build(deps): 更新 Vector 核心基座至最新版本 (core-837) 並升級 Gradle Wrapper 到 9.8.0` |
| `d097b31` | `fix(manager): 修復 A10 可能獲取不到存儲權限的問題`（= 公开仓库 `2a3a57e2`） |

**发布形态**：**843 测试版**（TG 频道分发，非 GitHub Release；公开仓库最新 Release 仍是 `v1.0.9`）。

**为什么这个修法对**（我核过源码可达性）：`VectorServiceClient.init()` 只要求 `asBinder() != null` 就收下服务；一旦收下，`XposedInit` 调 `getLegacyModules()` 会进入 `RemoteApplicationService.getLegacyModules()` 的 `current == null || !managerAvailable.get()` 分支 → `return getCachedModuleScope(true)` → **返回 `applyLocalCachedModules()` 已填好的缓存清单**（`RemoteApplicationService.java:475-498`）。清单本来就在，只被 `asBinder()` 挡在门外 ⇒ 「稳定本地 binder」正好补上缺的那一环，同时保留 Provider 对帐与后台重连升级（这是 `NeoLocalApplicationService` 给不了的）。

**✅ 实测验证（2026-10-17 22:25–22:27，一加平板 2 Pro / ColorOS 16 / Android 16）**：Bug C **已修复**。

| 场景 | 管理器状态 | `service is not ready` | `Installed native LoadedModule service proxy` | `Module loaded in process` | 进程 maps | 功能初始化日志 |
|---|---|---|---|---|---|---|
| **843 失败态** | 死 | ❌ **消失** | ✅ | ✅ | **6 段** | **16 条** |
| **843 成功态** | 活 | ❌ | ✅ | ✅ | 6 段 | 16 条 |
| （836 失败态对照） | 死 | ✅ ×2 | ❌ | ❌ | **0 段** | 0 条 |

失败态完整链路（`Manager not ready in time` 之后**直接**接上模块加载）：

```log
I NPatch  : Loading modules from local cache: [...]
I NPatch  : Loaded cached module tools.alamobile.mod
W NPatch  : Manager not ready in time; starting with local cached modules
I NPatch-Loader: Installed native LoadedModule service proxy      ← 836 在此处是两条 service is not ready
I AlaMobileTool: Module loaded in process com.Takotsubo.AlamobileFormula
```

模块功能侧同样完整（两态各 16 条，逐条一致）：`Native hooks installed (isAvailable=true)`、`lap hooks initialized`、`MusicPlayer initialized`、`PaddockUploader started`、`initUnlock: enable_unlock=1`、配置仲裁 `winner=remote prefs ... pedalMode=DUAL`。

**⚠️ 一个未被修复的边界（ColorOS 特有，非回归）**：管理器**晚于游戏启动**时，游戏进程**不会自动重连**。原因 = ColorOS 的 `OplusAppStartupManager` 让 `bindService` **同步返回 false**（`bindInitiated = false`）⇒ `scheduleGiveUp()` 的重连机制**根本没注册**。这与作者在 #154 的自述一致：「如果你的機型連 Android 標準的自動重啟服務進程都跳過，還是需要某個東西（打開任一 scope app 或 manager 本身）觸發一次重連」。**但这已不影响可用性**——模块在管理器缺席时就已经完整加载，重连只关系到「热重载/作用域实时更新」这类后续能力。

**另一条给上游的观察（bindService 同步返回 false）**：`OplusAppStartupManager` 在 `00:38:47.078` 拦截 bindService，而 `Manager not ready in time` 出现在 `00:38:47.098`——**仅隔 20ms**，远小于代码里的 `CONNECTION_TIMEOUT_SEC = 2`。说明 ColorOS 是**同步返回 false**（`bindServiceCompat` 返回 false ⇒ `bindInitiated = false` ⇒ `latch.await()` 根本不执行），而非"绑上了但超时"。
