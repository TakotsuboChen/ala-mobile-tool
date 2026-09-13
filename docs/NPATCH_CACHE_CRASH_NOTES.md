# NPatch 缓存损坏导致的启动闪退 — 调查记录

> 结论一句话：**这是 NPatch（本地注入框架）框架层的 bug，与 `ala-mobile-tool` 模块无关。**
> 根因 = NPatch 保存的 origin-apk 缓存文件损坏，而 NPatch 对缓存命中**只检查文件是否存在、不校验完整性**，导致每次启动都用坏文件构建 Resources → 系统静默失败 → 实例化 Application 时 NPE。
>
> 调查日期：2026-09-13 · 调查对象：用户单机故障（共存版 `com.Takotsubo.AlamobileFormula`，NPatch 本地模式）
> 信任分级：[V] 已用命令/源码核实 · [?] 推断 · [X] 已证伪

---

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
- **[X] 作用域无关**：`scope from Manager` 成功更新的 6 次（#18–#23）照样崩。作用域只影响模块列表，与 `source mode=cache` 对 sourceDir 的选择正交。
- **[X] 重启无关**：pid 从 32571 归零到 4905（zygote 重启）后仍崩 5 次。
- **[X] 缓存刷新无关**：崩溃窗口 `cacheApkPath=558144399.apk` 14 分钟恒定、全程**零** `Extracting origin.apk` —— 坏缓存从未被刷新（这一条证伪了最初"自愈=缓存被 wipe"的假设）。
- **[X] `:478 if (!loadedApkUsesOriginCache)` 跳过 restoreVisibleLoadedApkResources** 不是本崩溃根源：那是资源表错配 → `NotFoundException`；本崩是 `mResources=null` → `NPE`，机制不同。

---

## 5. 为什么"关掉作用域也没用" [V]

作用域（scope）只影响**加载哪些模块**，不影响 NPatch 的 `source mode=cache` 对 sourceDir 的选择。坏的是那份 origin-apk 缓存，与模块列表正交。日志 #18–#23 正是"管理器已连上、作用域更新成功、仍然崩"的实证。

---

## 6. 为什么"后面又自己好了"（自愈机制）[V/?]

AOSP 侧无负缓存、无重试：`null` 一旦赋进 `mResources` 就**永久驻留**（`if (mResources==null)` 不再为真），同一进程后续 `getResources()` 永远 null；但**失败不跨启动持久**，新进程重置。

坏 cache apk 只能被以下四类清理换掉：

| 触发 | 机制 | 与本案符合度 |
|---|---|---|
| **系统清 `code_cache`** | dataDir/cache 是标准 cache 目录，OEM 清理 / `pm trim-caches` / 存储清理整删 → 下次 `Files.exists=false` 走重建 | ★ 与"过一阵自愈"最吻合 [?] |
| 重打补丁 | `CacheCleaner.handlePatchUpgrade`：patchedApk stamp（lastModified-length）变 → `wipeAll(cacheRoot)` | 可能（若用户重注入过） |
| 游戏升版 | `CacheCleaner.sweepOriginApkCache`：CRC 变 → 删旧文件 | 本版本内不会 |
| 卸载重装 | install 清 dataDir | 未发生 |

**自愈窗口**：最后一次崩溃 15:30:27 → 成功启动 18:22，这段无日志覆盖 [?]。

**为什么持续 14 分钟且重启无效**：坏文件**跨启动存活**并被反复当作有效 cache——这正是 `:45` 只查 `Files.exists` 的直接后果。

---

## 7. 候选根因排序

1. **坏 cache apk 常驻**（无 fsync 的 copy 被中断 / 一次并发破坏后落盘）→ 此后每次启动命中同一坏文件 → 每次必崩，直到四类清理任一扫掉。**与全部日志证据最一致**。
2. 多进程并发首启竞态（进程 A 半写入 `Files.copy`，进程 B 命中 `Files.exists` 拿到半成品）——**[?] 只能解释坏文件如何产生**，不能解释"重启后仍被反复命中"。
3. `CacheCleaner.handlePatchUpgrade` stamp 竞态 wipe（两进程冷启动且 stamp 文件缺席时都走 `wipeAll` + extract 交错）。
4. `splitSourceDirs` 未重映射（cache 模式下 split 路径仍是 patched 安装路径）——与本次日志符合度低（单 base.apk）。

---

## 8. 处置建议

### 8.1 对用户（可直接转发）

> 闪退原因查清楚了：**是 NPatch（本地注入框架）的缓存文件损坏，不是 Ala Mobile Tool 模块的问题**。铁证——你关掉模块作用域也照样打不开，说明模块根本没参与，崩溃发生在模块加载之前。
>
> 修法（任选一种，做完即永久正常）：
> 1. 打开 NPatch 管理器 → 找到这个游戏 → **清除该应用的数据/缓存** → 重新注入一次；
> 2. 或**卸载共存版 → 重新安装 + 重新注入**。
>
> 成因是 NPatch 保存"原版 APK 备份"时偶尔会写坏，下次启动又只检查文件在不在、不检查完不完整，于是每次都用坏文件启动 → 闪退。等它自己好不可靠，按上面清一次即可。模块本身没问题，不用怀疑。

### 8.2 对 NPatch 上游（issue 修复方向）

> 已提交：**https://github.com/7723mod/NPatch/issues/147**（2026-09-13，账号 TakotsuboChen）

`prepareOriginApk` 三处补强：
1. cache-hit 分支增加完整性校验（`length() == entry.getSize()`，或试开 zip 尾目录 `End of Central Directory`）；
2. 写入改为临时文件 + `fsync` + 原子 `rename`；
3. 补 `FileLock`（与 `prepareNativeLibraryDir` 对齐）。

### 8.3 对本项目

**无可改动点**——崩溃发生在模块加载之前。仅需在用户报同类问题时按 §8.1 话术应答。

---

## 9. 待补项

- 若要 100% 坐实"是缓存文件损坏而非别的启动期异常"，需一份**完整 logcat（含 `AndroidRuntime`）**——但用户群 99% 无电脑（见记忆 [[users-cannot-use-adb]]），实操上难获取，不影响当前定论。
- NPatch 本地模式用户的典型 `sigBypassLevel` 具体值（取决于打包时写入的 manifest/config.json）[?]——不影响结论，崩溃日志已实证该次为 cache 模式（≥1）。

---

## 10. 一句话总结

**NPatch 的 origin-apk 缓存无完整性校验 + 无 fsync + 无锁（同仓库别处都有）→ 缓存一旦写坏就常驻 → 每次启动以坏文件建 Resources → AOSP 静默吞成 null → `getAssets()` NPE → 系统包成「无法实例化 Application」。与模块、PairIP、作用域、重启均无关；清 NPatch 数据重注入即可修。**
