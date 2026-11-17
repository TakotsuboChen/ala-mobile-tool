# 构建性能优化

> 状态：**活跃** · 最后编纂：2026-11-17
> 一句话结论：**一行 `System.currentTimeMillis()` 让零改动增量构建慢了 52 倍**（52s → 1s）；R8 的 50~73 秒是单线程全程序分析的物理下限，加堆/加线程/关 fullMode/THP 全部实测无效。

---

## 1. 结论（发现）

### 1.1 `buildConfigField` 里的时间戳摧毁整条增量链 [V]

**根因链条**（`--info` 逐条取证）：

```
build.gradle.kts 配置阶段 System.currentTimeMillis()
  → generateXxxBuildConfig 的输入属性 items 变化
     （Gradle 按**内容哈希**判定增量，不按 mtime）
  → BuildConfig.java 每次重新生成
  → 它在 Kotlin 源集内，且被 EnvInfo.kt:108 引用
     （'javaSources' file BuildConfig.java has changed）
  → compileXxxKotlin 全量重编
  → compileXxxJavaWithJavac → dexBuilder → mergeProjectDex → package
    连锁重跑
```

`--info` 铁证：
```
Task ':app:generateDebugBuildConfig' is not up-to-date because:
  Value of input property 'items' has changed
Build cache key ... is 5243bc4766e5472b3f0b3055180cb1c9   ← 上次
Build cache key ... is cb11c555d011cb18b6659eb4b9be715c   ← 这次
```

**每次必跑的 8 个任务里，6 个由这一个时间戳拖出来。**

**修复**：改用 `git log -1 --format=%ct`（HEAD 提交时间，对同一 commit 恒定）。诊断价值几乎无损——历史上那次「用户报 9/18 的 Alpha 1 而实际构建更早」，靠 `GIT_SHA` 一眼就能识破，不需要精确到秒。

### 1.2 实测收益 [V]

| 场景 | 改前 | 改后 | 倍数 |
|---|---|---|---|
| debug 零改动增量 | 52 s（8 tasks executed） | **1 s**（2 executed） | **52×** |
| debug 冷构建 | 152 s | 13 s | 11.7× |
| release 零改动增量 | 73 s | **3 s** | **24×** |

连续两次测量一致，非抖动。

### 1.3 R8 的 50~73 秒是物理下限 [V]

**采样实证**（R8 运行期间每 20s 采样 Gradle daemon）：

```
t=20s  threads=115  running=1  cpu%=259
t=40s  threads=114  running=1  cpu%=315
t=60s  threads=113  running=1  cpu%=350
```

**115 个线程里只有 1 个 RUNNABLE，CPU 260~350%（≈3.5/12 核）**。全程序调用图分析天然串行。

### 1.4 AGP 9.3.1 的 `android.*` 开关已无遗漏 [V]

javap 反编译 `BooleanOption` / `IntegerOption` 全量枚举 211 项，逐个确认默认值与 `FeatureStage`：

- **已默认开启**：`nonTransitiveRClass`、`nonFinalResIds`、`enableResourceOptimizations`、`enableAppCompileTimeRClass`、`builtInKotlin`、`newDsl`、`r8.optimizedResourceShrinking`、`enableR8.fullMode`
- **Enforced（写入即报错）**：`enableSourceSetPathsMap`、`cacheCompileLibResources`、`disableAutomaticComponentCreation`、`enableAapt2DaemonMode` 等 60 余项
- **已 Removed（网上仍在推荐但无效）**：`enableBuildCache`、`threadPoolSize`、`enableIntermediateArtifactsCache`
- **名字不存在**：`android.experimental.enableParallelResourceProcessing`

`defaultThreadPoolSize()` = `Runtime.availableProcessors()`（12 核 → 12 线程），所以 `android.r8.threadPoolSize` 无需配置。

### 1.5 `build/` 在本项目是逆向工作区，不是 Gradle 输出 [V]

`build/` 下有 **72 项、31GB** 不可恢复材料（`build/v8.0.6-official/` 11GB、各版本 APK、反编译输出、探针结果），而它同时是 Gradle 默认 `buildDir`。`./gradlew clean` 会无差别删光，且 `.gitignore` 排除了它 ⇒ git 无法恢复。

## 2. 依据（研究）

### 2.1 耗时分布（`--profile`，release 冷构建）

```
Total Build Time:             1m 13.73s
minifyReleaseWithR8:          1m 0.58s   ← 82%
produceReleaseComposeMapping:     6.86s
compileReleaseKotlin:             6.60s
buildCMakeRelWithDebInfo:         0.033s
```

### 2.2 冷态 vs 稳态（重要教训）

同一份输入、同样 8 个 executed 任务，连续 5 次构建：

```
148s → 102s → 27s → 8s → 5s
```

三轮 `--profile` 里 `compileDebugKotlin` 分别是 **2m13.82s / 1m49.19s / 10.202s**。

⇒ **「compileDebugKotlin 占 88%」只在冷 daemon 下成立**。稳态下它重跑只需 1~10 秒。真正的损失是它**连带 6 个下游任务**必跑。

### 2.3 Keep 规则审计结果

```
seeds.txt（被 keep 强制保留）: 1491 个类 / 1651 行
usage.txt（被移除）:          68087 行
```

保留类按包分布：

| 包 | 保留类数 | 占 1491 的比例 |
|---|---|---|
| `androidx.appcompat` | 497 | 33% |
| `androidx.compose` | 382 | 26% |
| `androidx.core` | 199 | 13% |
| `tools.alamobile`（自己） | 156 | 10% |
| `com.canhub`（cropper） | 96 | 6% |

**但 appcompat 全家在 R8 classpath 里只占 5.8 MB / 652 MB = 0.9%。** 去掉 cropper 省不到 1 秒，代价是删掉头像裁剪功能。

### 2.4 变体分层的实测数据

| 变体 | 配置 | 真正重建 | APK |
|---|---|---|---|
| `dev` | `isMinifyEnabled = false`（纯 D8） | **15 s** | 25 MB |
| `staging` | `-dontoptimize -dontobfuscate`（保留 shrink） | **50 s** | 13 MB |
| `release` | 完整 R8 | **73 s** | 11 MB |

## 3. 实现（固化）

### 3.1 构建指纹（`app/build.gradle.kts`）

```kotlin
val buildTimeMillis: Long = (findProperty("buildTimeMillis") as String?)
    ?.toLongOrNull()
    ?: try {
        val proc = providers.exec {
            commandLine("git", "log", "-1", "--format=%ct")
            isIgnoreExitValue = true
        }
        proc.standardOutput.asText.get().trim().toLongOrNull()?.times(1000L) ?: 0L
    } catch (_: Throwable) { 0L }
```

需要精确构建时刻时：`./gradlew assembleDebug -PbuildTimeMillis=$(date +%s000)`

### 3.2 变体分层

```
dev      → 日常装机验证（15s，无 shrink，测不出 keep 规则问题）
staging  → keep 规则兼容性验证（50s，有 shrink）
release  → 正式发版（73s）
```

**两个变体不是冗余**：`dev` 因为不 shrink，所有类都在，**测不出「keep 规则误删反射类」这类问题**；只有 `staging` 会裁类。

### 3.3 buildDir 迁移

```kotlin
allprojects {
    layout.buildDirectory.set(file("${rootProject.projectDir}/build-gradle/${project.name}"))
}
```

`build/` = 逆向工作区（31GB），`build-gradle/` = Gradle 输出。`.gitignore` 补 `build-gradle/`。

**验证方法**：在 `build/` 下放标记文件 → 真跑 `./gradlew clean` → 标记幸存 [通过]。

## 4. 已否决的方案

### 4.1 实测无效的 R8 加速尝试 [X]

| 尝试 | 实测结果 |
|---|---|
| `android.enableR8.fullMode=false` | 81s vs 81s |
| `-Xmx7g` 加大堆 | 80s vs 81s（RSS 稳定 3GB 未触顶） |
| `-XX:+UseTransparentHugePages` | 73s vs 73s（各 3 次测量） |
| `android.r8.gradual.support=true` | 53s vs 50s |
| `android.r8.threadPoolSize` | 默认已是 12 线程；但 115 线程里仅 1 个 RUNNABLE ⇒ 全程序分析天然串行 |
| `android.keepWorkerActionServicesBetweenBuilds=true` | aapt2 仅占 0.02s，零收益 + experimental 告警 |

### 4.2 换掉 `material-icons-extended` [X]

- 实测：11,105 个 class / 36MB classes.jar，项目只用 **46 个图标**
- **但**：这些 class 零 `@Composable` 注解（`javap` 逐个确认），Kotlin 编译按需加载，代价接近线性于"实际引用数"而非 jar 大小
- R8 A/B 实测：**+0.3~1.1s**
- 真正代价在 clean 构建的 `mergeExtDexDebug`（独立 d8 该 jar 26~29s），但有 Gradle 缓存，增量构建为 0
- 换掉需要自绘 **34 个图标**（core 里只有 12 个）⇒ 性价比不成立

**建议作为 APK 体积/依赖健康度改造做，不要当构建加速做。**

### 4.3 拆模块 [X]

- `org.gradle.parallel` 并行的是**任务/模块**，单模块内不并行
- 拆模块把增量编译的**文件粒度**换成**模块粒度**：改 library 模块 → 依赖方整模块重编
- 社区实测（freeCodeCamp）：3 模块项目改 library 反而比单模块慢 40 秒
- 本项目 `ModConfig` / `Logger` / `NativeBridge` 是跨层枢纽，日常改的正是它们

### 4.4 删除 `android-image-cropper` 以缩减 R8 输入 [X]

appcompat 全家（497 个保留类）100% 来自它，但只占 classpath 的 **0.9%**（5.8MB / 652MB）。去掉省不到 1s，代价是删掉头像裁剪功能。换 uCrop 同样依赖 AppCompat。

### 4.5 WSL2 上的系统级调优 [X]

| 尝试 | 结果 |
|---|---|
| `echo performance > scaling_governor` | **目录不存在**——WSL2 是 Hyper-V 虚拟机，cpufreq 属于宿主机 Windows |
| `echo madvise > transparent_hugepage/enabled` | 已经是 `[madvise]`，无需执行 |
| Windows Defender 排除 `ext4.vhdx` | 用户已排除过 |

### 4.6 其他被证伪的说法 [X]

- **「Gradle 的 UP-TO-DATE 谎报」** —— 实测 Gradle 按**内容哈希**判定（`touch` 不触发重跑），它没谎报；是 `buildConfigField` 污染了输入
- **「AGP 9 已移除 `proguard-android.txt`」** —— 实际存在 `proguard-android.txt-9.3.1`
- **「`isMinifyEnabled` 已被 `optimization { }` 废弃」** —— `optimization {}` DSL 确实存在（`BuildType` 有该方法），但 `isMinifyEnabled` **未标记 `@Deprecated`**，两者并存
- **「`multiDexKeepFile` 能修 16 个 dex」** —— 治错了病。minSdk 26 原生 multidex，16 个 dex 是 D8 按 64K 方法数正常切分；病因是图标 jar

## 5. 待验证

- [?] `gradle/actions/setup-gradle@v6` 的 CI 缓存效果 —— 本地无法验证，需等实际 CI 跑一次
- [?] `staging` / `dev` 变体在长时间使用中的维护成本 —— 新增两个变体意味着每次改 buildTypes 要同步三处
- [?] 后续 AGP 10 是否真的废弃 `isMinifyEnabled` —— 目前无 deprecated 标记，若废弃再迁移
- [?] 拆分 `ConfigurePagerMiuix.kt`（1843 行 / 13 个 @Composable）的实际收益 —— 未实测，社区建议但需本项目验证
