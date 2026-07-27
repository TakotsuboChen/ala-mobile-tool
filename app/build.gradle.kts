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
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    implementation(libs.activity.compose)

    implementation(libs.shadowhook)
}
