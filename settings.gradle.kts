// 本地开发环境用 Aliyun 镜像绕过 Clash TUN 对 dl.google.com 的 TLS 干扰。
// CI 环境（GitHub Actions，CI=true）直连 Google Maven 更稳，Aliyun 偶发 502。
pluginManagement {
    repositories {
        if (System.getenv("CI") == null) {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == null) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ala-mobile-tool"
include(":app")
