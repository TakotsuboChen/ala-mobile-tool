package tools.alamobile.mod.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 信息（仅取检查更新需要的字段）。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)

/**
 * 检查更新结果。
 *
 * @param latestVersionCode 最新版本的 6 位 versionCode；解析失败为 null。
 * @param latestVersionName 最新版本的 versionName（如 "1.0.0 Beta 5"）。
 * @param releaseNote Release Note 正文（Markdown 原文）。
 * @param apkDownloadUrl APK 下载 URL（优先选 CI 构建的 non-tag APK，再 fallback 第一个 asset）。
 * @param apkFileName APK 文件名。
 */
data class UpdateInfo(
    val latestVersionCode: Int?,
    val latestVersionName: String,
    val releaseNote: String,
    val apkDownloadUrl: String,
    val apkFileName: String
)

/**
 * 检查更新的三种结果。
 */
sealed class UpdateCheckResult {
    /** 有新版本信息。 */
    data class HasUpdate(val info: UpdateInfo) : UpdateCheckResult()

    /** 无可用更新（如稳定版通道无正式 Release）。 */
    object NoUpdate : UpdateCheckResult()

    /** 检查失败（网络错误等）。 */
    object Failed : UpdateCheckResult()
}

/**
 * GitHub Releases 检查更新。
 *
 * 同时请求 GitHub 官方 API 和镜像站，哪边先响应就用哪边——国内连通性差时
 * `api.github.com` 可能超时，镜像站（`kkgithub.com`）先到。
 *
 * versionCode 解析：从 `tag_name` 里提取 6 位数字。tag 格式如 `v1.0.0-Beta5`
 * 或 `1.0.0-Beta5`，从中提取 versionCode。如果 tag 里没有数字，fallback 到
 * assets 文件名里的 versionCode（CI 命名的 APK 含 versionCode）。
 *
 * 优先下载：从 assets 里找 CI 构建的 APK（文件名含 "CI"），没有就取第一个 APK。
 * CI 构建总是最新的，tag release 可能滞后。
 */
object UpdateChecker {

    private const val REPO = "TakotsuboChen/ala-mobile-tool"

    // GitHub 官方 API 和镜像站，同时请求取先到的
    // 稳定版通道用 /releases/latest（只返回非 pre-release）
    // 预览版通道用 /releases?per_page=1（含 pre-release）
    private fun apiUrl(channel: Int) = if (channel == UpdatePreferences.CHANNEL_STABLE) {
        "https://api.github.com/repos/$REPO/releases/latest"
    } else {
        "https://api.github.com/repos/$REPO/releases?per_page=1"
    }
    private fun mirrorUrl(channel: Int) = if (channel == UpdatePreferences.CHANNEL_STABLE) {
        "https://kkgithub.com/api/v3/repos/$REPO/releases/latest"
    } else {
        "https://kkgithub.com/api/v3/repos/$REPO/releases?per_page=1"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 检查最新 Release，GitHub 官方和镜像站竞速。
     *
     * 策略：同时请求两个源，等两个都完成（总超时 15s），取第一个成功响应
     * （有 Release 或 404 都算成功响应）。只有两个源都网络失败/超时才返回 Failed。
     *
     * @param channel 更新通道：0=稳定版（仅 Release），1=预览版（含 Pre-release）。
     * @return [UpdateCheckResult]：有更新 / 无更新 / 检查失败。
     */
    suspend fun checkLatest(channel: Int = UpdatePreferences.CHANNEL_STABLE): UpdateCheckResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val isList = channel != UpdatePreferences.CHANNEL_STABLE

            val official = async { fetchRelease(apiUrl(channel), isList) }
            val mirror = async { fetchRelease(mirrorUrl(channel), isList) }

            // 各自独立 await，超时 15s；超时的那个返回 (false, null)
            val offResult = withTimeoutOrNull(15_000) { official.await() } ?: (false to null)
            val mirResult = withTimeoutOrNull(15_000) { mirror.await() } ?: (false to null)

            // 优先取有 Release 的结果，其次取 404（无更新），最后才算失败
            val withRelease = listOf(offResult, mirResult).firstOrNull { it.second != null }
            if (withRelease != null) {
                val info = parseRelease(withRelease.second!!)
                if (info != null) UpdateCheckResult.HasUpdate(info)
                else UpdateCheckResult.Failed
            } else if (offResult.first || mirResult.first) {
                // 至少一个 404 = 无符合通道的 Release
                UpdateCheckResult.NoUpdate
            } else {
                // 两个都网络失败
                UpdateCheckResult.Failed
            }
        }
    }

    /**
     * @return Pair(notFound, release)：
     *   notFound=true 表示 HTTP 404（无符合通道的 Release）；
     *   release 非 null 表示成功拿到 Release；
     *   notFound=false + release=null 表示网络失败。
     */
    private fun fetchRelease(url: String, isList: Boolean): Pair<Boolean, GitHubRelease?> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "AlaMobileTool")
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> true to null
                    !response.isSuccessful -> false to null
                    else -> {
                        val body = response.body?.string() ?: return false to null
                        val release = if (isList) {
                            json.decodeFromString<List<GitHubRelease>>(body).firstOrNull()
                        } else {
                            json.decodeFromString<GitHubRelease>(body)
                        }
                        false to release
                    }
                }
            }
        } catch (e: Exception) {
            false to null
        }
    }

    /**
     * 从 GitHub Release 解析出 UpdateInfo。
     *
     * versionCode 提取优先级：
     * 1. tag_name 里的 6 位数字（如 v1.0.0-Beta5 → 无 6 位数，fallback）
     * 2. assets APK 文件名里的 6 位数字（如 `Ala Mobile Tool v1.0.0 Beta 5.apk` → 无 6 位数，
     *    CI 版 `Ala Mobile Tool v1.0.0 Beta 5 CI.apk` → 无 6 位数）
     * 3. 从 tag_name 的版本号反推 versionCode（1.0.0 Beta 5 → 100250）
     *
     * 实际上 CI 重命名 APK 文件名不含 versionCode，Release tag 也不含，
     * 所以用 tag_name 的版本号+阶段反推 versionCode。
     */
    private fun parseRelease(release: GitHubRelease): UpdateInfo? {
        val tagName = release.tagName
        val versionName = release.name ?: tagName
        val releaseNote = release.body ?: ""

        // 从 tag_name 提取 versionCode
        val versionCode = parseVersionCode(tagName)

        // 找 CI 构建的 APK（文件名含 "CI"），没有就找第一个 APK
        val ciAsset = release.assets.firstOrNull { it.name.contains("CI", ignoreCase = true) }
        val fallbackAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
        val apkAsset = ciAsset ?: fallbackAsset

        val apkUrl = apkAsset?.browserDownloadUrl
            ?: "https://github.com/$REPO/releases/latest"
        val apkFileName = apkAsset?.name ?: "AlaMobileTool-update.apk"

        return UpdateInfo(
            latestVersionCode = versionCode,
            latestVersionName = versionName,
            releaseNote = releaseNote,
            apkDownloadUrl = apkUrl,
            apkFileName = apkFileName
        )
    }

    /**
     * 从 tag_name / versionName 反推 6 位 versionCode。
     *
     * 支持的格式：
     * - `v1.0.0-Beta5` / `1.0.0 Beta 5` → 100250（1.0.0 Beta 5）
     * - `v1.5.9-Alpha3` / `1.5.9 Alpha 3` → 159130
     * - `v1.5.9` / `1.5.9` → 159300（stable）
     * - 纯 6 位数字 `100240` → 100240
     *
     * 版本号编码规则见 CLAUDE.md：
     * A.B.C + 阶段(1=Alpha,2=Beta,3=Stable) + D(序列) + 0
     */
    private fun parseVersionCode(tag: String): Int? {
        // 先试纯数字（6 位数）
        val pureDigits = tag.filter { it.isDigit() }
        if (pureDigits.length == 6) return pureDigits.toIntOrNull()

        // 提取版本号 A.B.C
        val versionRegex = Regex("""(\d+)\.(\d+)\.(\d+)""")
        val match = versionRegex.find(tag) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null

        // 提取阶段和序列号（支持 Alpha-4 / Alpha 4 / Alpha4 三种格式）
        val alphaMatch = Regex("""Alpha[-\s]*(\d+)""", RegexOption.IGNORE_CASE).find(tag)
        val betaMatch = Regex("""Beta[-\s]*(\d+)""", RegexOption.IGNORE_CASE).find(tag)
        val isStable = alphaMatch == null && betaMatch == null

        val stage: Int
        val sequence: Int
        when {
            alphaMatch != null -> {
                stage = 1
                sequence = alphaMatch.groupValues[1].toIntOrNull() ?: return null
            }
            betaMatch != null -> {
                stage = 2
                sequence = betaMatch.groupValues[1].toIntOrNull() ?: return null
            }
            else -> {
                stage = 3
                sequence = 0
            }
        }

        // versionCode = A.B.C 阶段 D 0 = A*100000 + B*10000 + C*1000 + 阶段*100 + D*10 + 0
        return major * 100000 + minor * 10000 + patch * 1000 + stage * 100 + sequence * 10
    }
}