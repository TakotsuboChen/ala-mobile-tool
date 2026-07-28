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
        versionCode = 100210
        versionName = "1.0.0-Beta-1"
    }

    signingConfigs {
        create("release") {
            // 优先用环境变量（CI 上由 GitHub Secret KEYSTORE_BASE64 解码生成）
            val storeFilePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/ala-mobile-tool.keystore"
            storeFile = file(storeFilePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "alamobiletool"
            keyAlias = System.getenv("KEYSTORE_ALIAS") ?: "alamobiletool"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "alamobiletool"
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
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.activity.compose)
    implementation(libs.navigationevent.compose)
    implementation(libs.material.icons.core)
    implementation(libs.material.icons.extended)
    implementation(libs.material3)

    implementation(libs.shadowhook)
}
