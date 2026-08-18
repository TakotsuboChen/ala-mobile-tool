@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "tools.alamobile.mod"
    compileSdk = 37

    defaultConfig {
        applicationId = "tools.alamobile.mod"
        minSdk = 26
        targetSdk = 35
        versionCode = 100250
        versionName = "1.0.0 Beta 5"
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
    implementation(libs.androidx.compose.ui.tooling.preview)
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
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}
