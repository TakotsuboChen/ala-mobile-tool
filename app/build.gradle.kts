plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "tools.alamobile.mod"
    compileSdk = 37

    defaultConfig {
        applicationId = "tools.alamobile.mod"
        minSdk = 26
        targetSdk = 35
        versionCode = 100230
        versionName = "1.0.0 Beta 3"
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
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }

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
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.activity.compose)
    implementation(libs.navigationevent.compose)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.material3)

    implementation(libs.shadowhook)
}
