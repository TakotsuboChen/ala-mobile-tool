package tools.alamobile.mod.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.ProxySelector
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

    /**
     * GitHub API 路径（稳定版通道 /latest 只返回非 pre-release；预览版通道
     * ?per_page=1 含 pre-release）。镜像站把完整 URL 作为路径段转发。
     */
    private fun apiPath(channel: Int) = if (channel == UpdatePreferences.CHANNEL_STABLE) {
        "repos/$REPO/releases/latest"
    } else {
        "repos/$REPO/releases?per_page=1"
    }

    /**
     * 全部候选源（2026-09-13 实测于目标设备网络，见 docs/UPDATE_CHECK_MIRRORS.md）：
     * 官方 API + 各镜像站。**全部并发请求，首个成功响应胜出，其余立即丢弃**
     * ——单源最快则总耗时等于该源耗时（实测官方 0.7~1.0s、gh-proxy 0.71s）。
     *
     * ⚠️ 只保留实测返回 200 的源：ghproxy.net/zwy.one/nxnow.top 返回 403
     * （releases API 被镜像站拒绝）、ghfast.top/dgithub.xyz 超时、moeyy/
     * gitmirror/99988866 等域名已失效——它们只会白占并发名额。
     */
    private val sources = listOf(
        "https://api.github.com",
        "https://gh.catmak.name/https://api.github.com",
        "https://ghproxy.monkeyray.net/https://api.github.com",
        "https://gh-proxy.com/https://api.github.com",
    )

    private fun candidateUrls(channel: Int): List<String> {
        val path = apiPath(channel)
        return sources.map { "$it/$path" }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        // 竞速场景下败者协程的阻塞调用不会被取消（OkHttp execute 不可中断），
        // 只能等超时自然结束——压短超时避免败者线程长时间占着 Dispatchers.IO。
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        // 显式走系统代理：OkHttp 默认不配 ProxySelector 时，在 Clash/Surge TUN
        // 模式下可能绕过系统代理，导致"挂全局梯子也下不动"。
        .proxySelector(ProxySelector.getDefault())
        .build()

    /**
     * 检查最新 Release：所有候选源并发竞速，**首个成功响应立即胜出，其余丢弃**。
     *
     * 2026-09-13 重写（双源版仍有 1.3~2.8s 延迟）：
     * - 旧实现用 `withTimeoutOrNull { off.await() } + { mir.await() }` **顺序**等待
     *   两个源，慢源决定总时长（注释写着 race 但代码不是）。
     * - 中间版用 Channel 取首个成功响应，但只有官方 + gh-proxy 两源，且官方
     *   在部分网络（如带 HK 代理的 PC 测试环境）慢 → 仍不够快。
     * - 本版：**4 源并发**（官方 + 3 个实测稳定返回 OK 的镜像），竞速协程跑在
     *   **独立 CoroutineScope**（不是 coroutineScope——后者 join 全部子协程，
     *   实测导致总耗时恒等于最慢源）。首个「成功响应」（拿到 Release 或明确
     *   404）胜出，`raceScope.cancel()` 立即丢弃其余，**总耗时 = 最快源耗时**。
     * - 15s 是总兜底（所有源都卡住时），正常路径远小于它。
     *
     * @param channel 更新通道：0=稳定版（仅 Release），1=预览版（含 Pre-release）。
     * @return [UpdateCheckResult]：有更新 / 无更新 / 检查失败。
     */
    suspend fun checkLatest(channel: Int = UpdatePreferences.CHANNEL_STABLE): UpdateCheckResult = withContext(Dispatchers.IO) {
        val isList = channel != UpdatePreferences.CHANNEL_STABLE
        val urls = candidateUrls(channel)
        // ⚠️ 关键：请求 launch 到独立 scope，**不用 coroutineScope**——后者会 join
        //    所有子协程才返回，导致总耗时 = 最慢源（实测 5 轮全部如此：总耗时
        //    永远等于最慢源的耗时，非最快）。独立 scope 才能「首个胜出立即返回」。
        val raceScope = CoroutineScope(Dispatchers.IO)
        val results = Channel<Pair<Boolean, GitHubRelease?>>(urls.size)
        urls.forEach { url ->
            raceScope.launch {
                val t = android.os.SystemClock.elapsedRealtime()
                val r = fetchRelease(url, isList)
                val el = android.os.SystemClock.elapsedRealtime() - t
                val host = url.substringAfter("://").substringBefore('/')
                val kind = when {
                    r.second != null -> "OK"
                    r.first -> "404"
                    else -> "FAIL"
                }
                tools.alamobile.mod.util.Logger.i("UpdateCheck", "  src $host → $kind in ${el}ms")
                results.send(r)
            }
        }

        var winner: Pair<Boolean, GitHubRelease?>? = null
        try {
            withTimeoutOrNull(15_000) {
                repeat(urls.size) {
                    val r = results.receive()
                    // 成功响应 = 拿到 Release (second!=null) 或明确 404 (first=true)
                    if (r.first || r.second != null) {
                        winner = r
                        return@withTimeoutOrNull
                    }
                    // 网络失败：继续等下一个源
                }
            }
        } finally {
            // 拿到赢家（或超时）后立即取消整个竞速 scope：败者协程不再被等待。
            // OkHttp execute() 不可中断，败者线程会在自身超时后自然消亡，但不阻塞返回。
            raceScope.cancel()
            results.close()
        }

        when {
            winner?.second != null -> {
                val info = parseRelease(winner!!.second!!)
                if (info != null) UpdateCheckResult.HasUpdate(info)
                else UpdateCheckResult.Failed
            }
            winner?.first == true -> UpdateCheckResult.NoUpdate
            else -> UpdateCheckResult.Failed  // 所有源都网络失败/超时
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