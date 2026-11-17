@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

/**
 * 构建指纹：注入到 BuildConfig，供日志环境块打印。
 *
 * ## 为什么需要（2026-10-18 实证教训）
 *
 * 用户报障时说"这是 9/18 的 Alpha 1"，但日志里出现了 `Pushed game logs via
 * chunked broadcast`——那句代码在 9/14 的 bee9554 就删了。**versionName /
 * versionCode 完全锁不住构建时间**：`1.0.4 Alpha 1` / `104100` 从 9/5 一直
 * 沿用到 10/6，横跨一个多月几十个提交。排查时只能靠"日志里有没有某个已删除
 * 的字符串"倒推，极其被动。
 *
 * ⇒ 直接注入构建时间 + git SHA，让每个包自带可读的出生证明。
 *
 * ## 为什么在配置阶段求值
 *
 * 这两个值必须对**每次构建**重新求值。若放进 `doLast`/`providers.provider`
 * 延迟求值，configuration cache 会缓存上一次结果。
 *
 * ⚠️ 但从 2026-11-17 起，**求值结果的稳定性比「求值时机」更重要**——
 * 配置阶段求出一个每次都变的值，会让 BuildConfig 成为"每次都变的任务输入"，
 * 从而摧毁整条增量编译链。详见下方第二段注释。
 *
 * ## git SHA 的边界
 *
 * CI 的 `actions/checkout@v7` 默认 `fetch-depth: 1`，但 `git rev-parse --short
 * HEAD` 只需要 HEAD 提交本身，不依赖历史深度，因此浅克隆也能取到。
 * 非 git 环境（如源码 tarball）取不到时回落 `"unknown"`，不能让构建失败。
 */
/*
 * ## ⚠️ 为什么这里用 commit 时间而不是 System.currentTimeMillis()（2026-11-17）
 *
 * 旧实现用 `System.currentTimeMillis()` 取「真实构建时刻」，代价极其昂贵：
 *
 *   build.gradle.kts 每次求值出不同的值
 *     → `generateXxxBuildConfig` 的输入属性 `items` 变化（Gradle 按**内容哈希**
 *       判定增量，不按 mtime）
 *     → BuildConfig.java 每次重新生成
 *     → 它在 Kotlin 源集里且被 EnvInfo.kt 引用（`'javaSources' file
 *       BuildConfig.java has changed`）
 *     → `compileXxxKotlin` 全量重编
 *     → `compileXxxJavaWithJavac` → `dexBuilder` → `mergeProjectDex` → `package`
 *       连锁重跑
 *
 * 实测量化（2026-11-17，12 核 WSL2）：
 *   - 零改动增量构建：**52s（8 tasks executed）→ 7s（2 tasks executed）**
 *   - 每次必跑的 6 个下游任务全部由这一个时间戳拖出来
 *   - `--info` 铁证：`Task ':app:generateDebugBuildConfig' is not up-to-date
 *     because: Value of input property 'items' has changed`
 *
 * 改用 commit 时间后：同一 commit 反复构建值恒定 → BuildConfig UP-TO-DATE
 * → Kotlin UP-TO-DATE。诊断价值几乎无损——历史上那次「用户报 9/18 的 Alpha 1
 * 而实际构建更早」的排查，靠 `GIT_SHA` 一眼就能识破，并不需要精确到秒。
 *
 * ⚠️ 副作用已知并接受：同一 commit 内多次构建的包，日志里的「构建时间」相同。
 * 需要区分时看 git SHA；若确实要精确构建时刻，用
 * `-PbuildTimeMillis=$(date +%s000)` 显式注入（不改文件、不影响他人）。
 */
val buildTimeMillis: Long = (findProperty("buildTimeMillis") as String?)
    ?.toLongOrNull()
    ?: try {
        // `git log -1 --format=%ct` = HEAD 提交的 unix 时间戳（秒），对同一 commit 恒定。
        val proc = providers.exec {
            commandLine("git", "log", "-1", "--format=%ct")
            isIgnoreExitValue = true
        }
        proc.standardOutput.asText.get().trim().toLongOrNull()?.times(1000L) ?: 0L
    } catch (_: Throwable) {
        0L
    }

val gitSha: String = try {
    val proc = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        // 非 git 环境不能让构建失败：忽略退出码，下面按空串处理
        isIgnoreExitValue = true
    }
    proc.standardOutput.asText.get().trim().ifEmpty { "unknown" }
} catch (_: Throwable) {
    "unknown"
}

android {
    namespace = "tools.alamobile.mod"
    compileSdk = 37

    defaultConfig {
        applicationId = "tools.alamobile.mod"
        minSdk = 26
        targetSdk = 35
        versionCode = 105110
        versionName = "1.0.5 Alpha 1"

        // 构建指纹（见文件头注释）。日志环境块据此打出"这个包是什么时候、
        // 从哪个提交构建的"——版本号复用期间这是唯一的构建时间判据。
        buildConfigField("long", "BUILD_TIME_MILLIS", "${buildTimeMillis}L")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    signingConfigs {
        create("release") {
            // 优先用环境变量（CI 上由 GitHub Secret KEYSTORE_BASE64 解码生成）
            // 用 ifEmpty 处理空字符串（CI 上 secret 未配时 env 是空串不是 null）
            val storeFilePath = System.getenv("KEYSTORE_PATH")?.ifEmpty { null }
                ?: "${rootDir}/ala-mobile-tool.keystore"
            storeFile = file(storeFilePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD")?.ifEmpty { null } ?: "alamobiletool"
            keyAlias = System.getenv("KEYSTORE_ALIAS")?.ifEmpty { null } ?: "alamobiletool"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")?.ifEmpty { null } ?: "alamobiletool"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 如果本地 keystore 不存在（如 CI 未配 secret），fallback 到 debug 签名
            // 保证 CI 永远能产出可安装的 APK；本地构建用 release keystore
            signingConfig = if (file("${rootDir}/ala-mobile-tool.keystore").exists()
                || System.getenv("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // ── dev：日常本地验证（2026-11-17）─────────────────────────────
        //
        // **不跑 R8**，走纯 D8。实测真正重建 **15 秒**（release 是 73 秒）。
        //
        // 为什么快：跳过整个全程序调用图分析 + 树摇。代价是 APK 25MB
        // （release 11MB），本地装机完全无所谓。
        //
        // ⚠️ dev 测不出的东西：keep 规则是否误删了反射类。因为没跑 shrink，
        // 所有类都在。要验证 keep 规则请改用 `staging` 变体。
        //
        // ⚠️ 产物不可发布：未混淆、未优化、未裁剪。
        //
        // 用法：`./gradlew :app:assembleDev`
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }

        // ── staging：keep 规则兼容性验证（2026-11-17）──────────────────
        //
        // 跑 shrink（能测出 keep 规则问题），但跳过 R8 最贵的两个阶段：
        //   -dontoptimize   跳过全局优化（内联/类合并/跨过程分析 —— 73s 的主体）
        //   -dontobfuscate  跳过重命名（省 mapping.txt 生成）
        // 实测真正重建 **50 秒**，APK 13MB。
        //
        // 什么时候用它：改了 proguard-rules.pro、加了新依赖、或怀疑 R8 把某个
        // 反射用的类裁掉了。日常装机验证用 `dev` 更快。
        //
        // ⚠️ 产物不可发布：未混淆、未优化。
        //
        // 用法：`./gradlew :app:assembleStaging`
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"   // 与正式包共存，不覆盖用户安装
            versionNameSuffix = "-staging"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-staging.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

    // AGP 9.3.1 默认要 build-tools 36.0.0，本地只有 36.1.0；显式指定避免自动下载。
    buildToolsVersion = "36.1.0"

    // 本地只有 NDK 26.1.10909125；KernelSU 用 29.0.14206865，但我们的 native 代码
    // (pedal_hook.c / drs_hook.c / ala_core.c) 是纯 C，NDK 26 完全够用。
    // Clash TUN TLS 干扰导致 NDK 29 无法自动下载，后续需要时再手动装。
    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("${project.rootDir}/native/CMakeLists.txt")
            version = "3.22.1+"
        }
    }

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/*"
        }
        jniLibs {
            pickFirsts += "lib/arm64-v8a/libshadowhook.so"
        }
    }

    // lint baseline：把存量问题锁在基线里，CI 只报新引入的错误。
    // 3 个 NewApi（BillingHook.defaultClassLoader / VersionGate.longVersionCode）
    // 影响范围窄（仅 Android 8.0-8.1），后续单独修。
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }
}

dependencies {
    // Xposed
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    // Compose (BOM 统一版本)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    // ⚠️ `ui-tooling-preview` 已于 2026-11-17 删除：全仓 `@Preview` 数量 = 0，
    // 该依赖是纯死重（21 个 class / 22.7KB，R8 本来也会剥掉）。
    // 将来若引入 @Preview，再把它加回 implementation 即可。
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Activity / Lifecycle / Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Navigation3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigationevent.compose)

    // Kotlinx
    implementation(libs.kotlinx.coroutines.core)

    // miuix
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)

    // Native hooks
    implementation(libs.shadowhook)

    // Network
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // 头像裁剪（围场注册后首登上传）
    implementation(libs.image.cropper)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}
