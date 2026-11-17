plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * ⚠️ rootProject 的 buildDir 从默认的 `build/` 改为 `build-gradle/`（2026-11-17）
 *
 * ## 为什么（这是数据安全修复，不是提速）
 *
 * 本项目从早期就把 `build/` 当成**逆向工作目录**：里面躺着 60+ 项共约 31GB
 * 不可恢复的材料——`build/v8.0.6-official/`（11GB）、各版本 APK 与反编译输出、
 * 探针结果等。而 `build/` 同时又是 Gradle 的默认 `buildDir`。
 *
 * 后果：**`./gradlew clean` 的语义就是 `delete(buildDir)`**，会无差别删光这
 * 31GB。而 `clean` 是日常命令，且 `build/` 在 `.gitignore` 里（第 16/17 行），
 * **删了无法从 git 恢复**。
 *
 * 修复方式选择「改 Gradle 指向」而非「搬 31GB 数据」：
 *   - 逆向材料原地不动，零搬迁风险
 *   - 只需在 `.gitignore` 补一行 `build-gradle/`
 *   - 两个目录名从此语义清晰：`build/` = 逆向工作区，`build-gradle/` = 构建输出
 *
 * ⚠️ 若将来要再动这个配置，先确认 `build/` 下没有新产生的 Gradle 产物
 * （`app/` 子项目的 buildDir 不受影响，仍是 `app/build/`）。
 */
allprojects {
    layout.buildDirectory.set(file("${rootProject.projectDir}/build-gradle/${project.name}"))
}

