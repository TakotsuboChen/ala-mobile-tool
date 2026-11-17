plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * ⚠️ **rootProject 的** buildDir 从默认的 `build/` 改为 `build-gradle/`（2026-11-17）
 *
 * ## 为什么（这是数据安全修复，不是提速）
 *
 * 本项目从早期就把 `build/` 当成**逆向工作目录**：里面躺着 60+ 项共约 31GB
 * 不可恢复的材料——`build/v8.0.6-official/`（11GB）、各版本 APK 与反编译输出、
 * 探针结果等。而 `build/` 同时又是 Gradle 的默认 `buildDir`。
 *
 * 后果：**`./gradlew clean` 的语义就是 `delete(buildDir)`**，会无差别删光这
 * 31GB。而 `clean` 是日常命令，且 `build/` 在 `.gitignore` 里，**删了无法从
 * git 恢复**。
 *
 * ## ⚠️ 只改 rootProject，**必须用 `rootProject {}` 而不是 `allprojects {}`**
 *
 * 2026-11-17 首次实现用了 `allprojects { ... }`，把 `:app` 的 buildDir 也一并
 * 改到了 `build-gradle/app`——本地因为残留着旧的 `app/build/outputs/apk/release/
 * app-release.apk` 而没暴露问题，**CI 立即失败**：
 *
 *     mv: cannot stat 'app/build/outputs/apk/release/app-release.apk': No such file or directory
 *
 * 本项目的 `build/` 危险只存在于**根目录**（逆向材料在 `build/v*`），
 * `app/build/` 是纯 Gradle 产物，**不需要也不应该改**——改了会破坏 CI 与所有
 * 文档里记录的产物路径。
 */
// 根项目自身（根 build script 的隐式 receiver 就是 rootProject）
layout.buildDirectory.set(file("$projectDir/build-gradle"))


