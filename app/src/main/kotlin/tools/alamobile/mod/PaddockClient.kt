package tools.alamobile.mod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import tools.alamobile.mod.util.Logger

/**
 * 围场（Paddock）客户端：计时赛有效圈上报 + 登录态维护 + 本地待传队列。
 *
 * 定案映射（docs/PADDOCK_PLAN.md）：
 * - 每有效圈都上传（服务端去重留最佳）；Toast 判定权在服务端响应。
 * - 未登录/弱网时圈进本地待传队列（30 天时效），登录成功后补传。
 * - 90 天滑动 token；登录态文件放 external files（与游戏侧 ala_tool.log 同区，
 *   module 进程 ConfigActivity 与游戏进程 AlaMobileModule 都可见）。
 * - Toast 四条件取最高：alltime_server > version_server > alltime_personal > version_personal。
 *
 * 线程模型：调用方（AlaMobileModule 的 1Hz 轮询 Handler）负责切到工作线程；
 * 本类所有公开方法均为阻塞 IO，禁止主线程调用。
 */
object PaddockClient {

    private const val TAG = "PaddockClient"

    /** 内置默认服务器（可被配置覆盖；线上部署 paddock.takotsubo.cloud） */
    private const val DEFAULT_SERVER = "https://paddock.takotsubo.cloud"

    private const val QUEUE_FILE = "paddock_pending_laps.json"
    private const val AUTH_FILE = "paddock_auth.json"
    private const val QUEUE_TTL_MS = 30L * 24 * 3600 * 1000
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 10_000

    /** 待传队列上限——防刷圈异常时无限膨胀 */
    private const val QUEUE_MAX = 200

    @Volatile private var appContext: Context? = null
    @Volatile private var serverBase: String = DEFAULT_SERVER
    @Volatile private var authToken: String? = null
    @Volatile private var lastUploadAt: Long = 0

    /** 游戏版本号（versionCode），模块初始化时从 VersionGate 注入 */
    @Volatile var versionCode: Int = 0

    fun init(ctx: Context, serverOverride: String?) {
        appContext = ctx.applicationContext
        serverBase = serverOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        loadAuth()
    }

    // ── 登录态 ──────────────────────────────────────────────

    private fun authFile(): File = File(getDir(), AUTH_FILE)

    private fun getDir(): File {
        val ctx = appContext ?: error("PaddockClient not initialized")
        return ctx.getExternalFilesDir(null) ?: ctx.filesDir
    }

    private fun loadAuth() {
        try {
            val f = authFile()
            if (f.exists()) {
                val o = JSONObject(f.readText())
                authToken = o.optString("token", "").takeIf { it.isNotEmpty() }
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "loadAuth failed: ${e.message}")
        }
        // 本地文件没有 token（或读取失败）→ 从 daemon Remote Preferences 恢复。
        // 根因修复：模块进程与游戏进程的 externalFilesDir 是两个目录（Android 11+
        // scoped storage 互不可见），本地文件只在写它的进程可见；daemon 常驻且
        // 两进程都能访问，是 token 的权威存储。游戏进程经 remoteTokenReader 读。
        if (authToken.isNullOrBlank()) {
            val reader = remoteTokenReader
            if (reader == null) {
                Logger.log(Log.WARN, TAG, "loadAuth: no remote reader injected (module process expected)")
            } else {
                try {
                    authToken = reader()?.takeIf { it.isNotEmpty() }
                    if (authToken != null) {
                        Logger.log(Log.INFO, TAG, "auth restored from remote prefs")
                    } else {
                        Logger.log(Log.WARN, TAG, "loadAuth: remote token empty/missing")
                    }
                } catch (e: Throwable) {
                    Logger.log(Log.WARN, TAG, "remote token read failed: ${e.message}")
                }
            }
        }
    }

    /**
     * 游戏进程注入的 daemon token 读取器（与 ModConfig.remoteConfigReader 同模式）。
     * AlaMobileModule.onPackageReady 里赋值 = getRemotePreferences(PREF_GROUP).getString(KEY_PADDOCK_TOKEN)。
     * 模块进程不注入（null）——它本地文件直读。
     */
    @Volatile var remoteTokenReader: (() -> String?)? = null

    /** 登录成功后持久化 token（模块进程写：本地 + daemon 双写；游戏进程恢复用 daemon） */
    fun saveAuth(token: String) {
        authToken = token
        try {
            val o = JSONObject().put("token", token)
            authFile().writeText(o.toString())
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "saveAuth failed: ${e.message}")
        }
        // 双写 daemon（LSPosed Remote Preferences）：service 未绑定时静默跳过
        //（本地文件仍在，下次 ConfigActivity 启动时 flush 逻辑兜底——见 App.onServiceBind）
        try {
            val service = App.xposedService
            if (service != null) {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .putString(App.KEY_PADDOCK_TOKEN, token)
                    .apply()
                Logger.log(Log.INFO, TAG, "token saved to remote prefs")
            } else {
                Logger.log(Log.WARN, TAG, "xposedService not bound, token saved locally only")
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "remote token save failed: ${e.message}")
        }
    }

    fun clearAuth() {
        authToken = null
        authFile().delete()
        // 同步清 daemon：退出登录必须两侧都清，否则游戏进程还能用旧 token 传圈
        try {
            val service = App.xposedService
            if (service != null) {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_PADDOCK_TOKEN)
                    .apply()
            }
        } catch (_: Throwable) {
        }
    }

    fun hasToken(): Boolean = !authToken.isNullOrBlank()

    /**
     * 拉取个人资料（GET /v1/me，Bearer）。用途：模块进程重进后恢复登录态展示
     * （token 只证明身份，username/reg_seq/积分必须另拉）。
     * ok=true → profile 有效；ok=false 且 needRelogin=true → token 失效/账号被删，
     * 调用方应 clearAuth 登出；needRelogin=false → 网络问题，保留 token 下次再试。
     * 阻塞 IO，工作线程调用。
     */
    data class MeResult(
        val ok: Boolean,
        val needRelogin: Boolean = false,
        val userId: String = "",
        val username: String = "",
        val regSeq: Long = 0,
        val hasAvatar: Boolean = false,
        val totalPoints: Long = 0,
    )

    fun fetchMe(): MeResult {
        val token = authToken ?: return MeResult(ok = false, needRelogin = true)
        return try {
            val (code, resp) = getJson("$serverBase/v1/me", token)
            when {
                code == 200 -> {
                    val j = JSONObject(resp)
                    MeResult(
                        ok = true,
                        userId = j.optString("user_id"),
                        username = j.optString("username"),
                        regSeq = j.optLong("reg_seq"),
                        hasAvatar = j.optBoolean("has_avatar"),
                        totalPoints = j.optLong("total_points"),
                    )
                }
                code == 401 -> MeResult(ok = false, needRelogin = true)
                else -> {
                    Logger.log(Log.WARN, TAG, "fetchMe: HTTP $code ${errText(code, resp)}")
                    MeResult(ok = false)
                }
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchMe failed: ${e.message}")
            MeResult(ok = false)
        }
    }

    /** 当前内存 token（flush 兜底用），不读文件。 */
    fun peekAuthToken(): String? = authToken

    /**
     * 阻塞登录。成功返回 reg_seq/needs_avatar；失败返回错误文案（服务端原样带回）。
     * needs_avatar=true 表示注册后首次登录（无头像），UI 引导上传。
     * ConfigActivity/登录 UI 在工作线程调用。
     */
    data class LoginResult(
        val ok: Boolean,
        val message: String,
        val userId: String = "",
        val regSeq: Long = 0,
        val needsAvatar: Boolean = false,
    )

    fun login(username: String, password: String): LoginResult {
        return try {
            val body = JSONObject().put("username", username).put("password", password)
            val (code, resp) = postJson("$serverBase/v1/auth/login", body, null)
            if (code == 200) {
                val j = JSONObject(resp)
                val token = j.optString("token")
                if (token.isNotEmpty()) {
                    saveAuth(token)
                    LoginResult(
                        ok = true, message = "OK",
                        userId = j.optString("user_id"),
                        regSeq = j.optLong("reg_seq"),
                        needsAvatar = !j.optBoolean("has_avatar"),
                    )
                } else {
                    LoginResult(ok = false, message = "响应缺少 token")
                }
            } else {
                LoginResult(ok = false, message = errText(code, resp))
            }
        } catch (e: Throwable) {
            LoginResult(ok = false, message = "网络错误: ${e.message}")
        }
    }

    /**
     * 注册申请：用户名+密码 → 服务端生成 pending 会话（哈希密码+发车手 ID）。
     * 成功返回 (true, "申请围场通行证#<code>")——用户复制后发 CAMDA 群，
     * bot 校验成功即建号，之后回模块直接登录（同用户名+密码）。
     */
    fun registerRequest(username: String, password: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject().put("username", username).put("password", password)
            val (code, resp) = postJson("$serverBase/v1/auth/register-request", body, null)
            if (code == 200) {
                val j = JSONObject(resp)
                val code1 = j.optString("reg_code")
                if (code1.isNotEmpty()) Pair(true, "申请围场通行证#$code1")
                else Pair(false, "响应缺少 reg_code")
            } else {
                Pair(false, errText(code, resp))
            }
        } catch (e: Throwable) {
            Pair(false, "网络错误: ${e.message}")
        }
    }

    /**
     * 阻塞提交重置码换新密码（忘记密码流第二步；第一步在 CAMDA 群找 bot 要码）。
     * 成功返回 null；失败返回错误文案。不自动登录——让用户用新密码走登录。
     */
    fun resetByCode(resetCode: String, newPassword: String): String? {
        return try {
            val body = JSONObject()
                .put("reset_code", resetCode.trim().uppercase())
                .put("new_password", newPassword)
            val (code, resp) = postJson("$serverBase/v1/auth/reset-by-code", body, null)
            if (code == 204) null else errText(code, resp)
        } catch (e: Throwable) {
            "网络错误: ${e.message}"
        }
    }

    // ── 圈速上传 ────────────────────────────────────────────

    /**
     * 上传一条有效圈。返回服务端 Toast 文案（可为 null=无提示）。
     * 401/网络失败时圈进本地待传队列。阻塞 IO，工作线程调用。
     */
    fun uploadLap(gpIndex: Int, lapMs: Int): String? {
        lastUploadAt = System.currentTimeMillis()
        val token = authToken
        if (token == null) {
            enqueue(gpIndex, lapMs)
            return null
        }
        return try {
            val body = JSONObject()
                .put("gp_index", gpIndex)
                .put("version_code", versionCode)
                .put("lap_ms", lapMs)
            val (code, resp) = postJson("$serverBase/v1/laps", body, token)
            when {
                code == 200 -> {
                    val toast = parseToast(resp)
                    // 首次成功 → 补传队列里的旧圈（限流：每次成功上传最多带 10 条）
                    if (drainQueue(token, 10) > 0) {
                        Logger.log(Log.INFO, TAG, "queue drained")
                    }
                    toast
                }
                code == 401 -> { enqueue(gpIndex, lapMs); null }
                else -> { // 4xx 参数类错误：不重传（服务端明确拒绝）
                    Logger.log(Log.WARN, TAG, "upload rejected: $code ${errText(code, resp)}")
                    null
                }
            }
        } catch (e: IOException) {
            enqueue(gpIndex, lapMs)
            null
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "uploadLap failed: ${e.message}")
            null
        }
    }

    /**
     * 服务端 Toast 文案组装。level 映射（服务端字段 toast.level）。
     * 四条件（去重后单条），模板按用户定案（2026-09-04：不带赛道名）：
     * "您已刷新…的个人/全服最佳成绩"
     */
    private fun parseToast(resp: String): String? {
        return try {
            val toast = JSONObject(resp).optJSONObject("toast") ?: return null
            when (toast.optString("level")) {
                "alltime_server" -> "您已刷新全服历史最佳成绩！"
                "version_server" -> "您已刷新全服版本最佳成绩！"
                "alltime_personal" -> "您已刷新个人历史最佳成绩！"
                "version_personal" -> "您已刷新个人版本最佳成绩！"
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
    }

    // ── 本地待传队列 ────────────────────────────────────────

    private fun queueFile(): File = File(getDir(), QUEUE_FILE)

    /** 入队（去重：同 gp+lapMs+当日 不重复入队）。满了丢最旧。 */
    @Synchronized
    private fun enqueue(gpIndex: Int, lapMs: Int) {
        try {
            val arr = readQueue()
            // 简单去重：完全相同的 (gp, ms) 且 60s 内已入队 → 跳过
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optInt("gp") == gpIndex && e.optInt("ms") == lapMs && now - e.optLong("t") < 60_000) {
                    return
                }
            }
            val item = JSONObject().put("gp", gpIndex).put("ms", lapMs).put("t", now)
            arr.put(item)
            while (arr.length() > QUEUE_MAX) arr.remove(0)
            queueFile().writeText(arr.toString())
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "enqueue failed: ${e.message}")
        }
    }

    @Synchronized
    private fun readQueue(): org.json.JSONArray {
        return try {
            val f = queueFile()
            if (f.exists()) org.json.JSONArray(f.readText()) else org.json.JSONArray()
        } catch (e: Throwable) {
            org.json.JSONArray()
        }
    }

    /** 补传队列（过期丢弃）。返回成功条数。 */
    @Synchronized
    private fun drainQueue(token: String, max: Int): Int {
        val arr = readQueue()
        if (arr.length() == 0) return 0
        val now = System.currentTimeMillis()
        val remaining = org.json.JSONArray()
        var ok = 0
        var tried = 0
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (now - it.optLong("t", 0) > QUEUE_TTL_MS) continue  // 过期丢弃
            if (tried >= max) {
                remaining.put(it)
                continue
            }
            tried++
            val code = try {
                val body = JSONObject()
                    .put("gp_index", it.optInt("gp"))
                    .put("version_code", versionCode)
                    .put("lap_ms", it.optInt("ms"))
                postJson("$serverBase/v1/laps", body, token).first
            } catch (e: Throwable) {
                -1  // 网络异常：保留待下次补传
            }
            val kept = (code == 401 || code == -1 || code >= 500)  // 可重试类：登录态问题/网络/服务端故障
            if (kept) remaining.put(it) else if (code == 200) ok++
            // 其他 4xx：服务端明确拒绝，丢弃
        }
        queueFile().writeText(remaining.toString())
        return ok
    }

    /** 待传条数（诊断/调试用） */
    fun pendingCount(): Int = readQueue().length()

    // ── HTTP ────────────────────────────────────────────────

    /** 榜单条目（积分榜/赛道榜共用解析子集） */
    data class PointsEntry(val username: String, val points: Int, val avatarUrl: String?)

    data class TrackEntry(val rank: Int, val username: String, val lapDisplay: String, val avatarUrl: String?)

    data class TrackBoard(
        val trackName: String,
        val entries: List<TrackEntry>,
    )

    /** GET /v1/leaderboard/points（version=null → 总榜）。阻塞 IO。 */
    fun fetchPointsBoard(version: Int?): List<PointsEntry> {
        return try {
            val q = if (version != null) "?version=$version" else ""
            Logger.log(Log.INFO, TAG, "fetchPointsBoard: GET $serverBase/v1/leaderboard/points$q")
            val (code, resp) = getJson("$serverBase/v1/leaderboard/points$q")
            Logger.log(Log.INFO, TAG, "fetchPointsBoard: HTTP $code, ${resp.length} bytes")
            if (code != 200) return emptyList()
            val arr = org.json.JSONArray(JSONObject(resp).optJSONArray("entries")?.toString() ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                PointsEntry(e.optString("username"), e.optInt("points"), e.optString("avatar_url").takeIf { it.isNotEmpty() })
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchPointsBoard: ${e.message}")
            emptyList()
        }
    }

    /** GET /v1/leaderboard/track/{gp}（version=null → 该赛道总榜）。阻塞 IO。 */
    fun fetchTrackBoard(gpIndex: Int, version: Int?): TrackBoard? {
        return try {
            val q = if (version != null) "?version=$version" else ""
            Logger.log(Log.INFO, TAG, "fetchTrackBoard: GET track/$gpIndex$q")
            val (code, resp) = getJson("$serverBase/v1/leaderboard/track/$gpIndex$q")
            Logger.log(Log.INFO, TAG, "fetchTrackBoard: HTTP $code, ${resp.length} bytes")
            if (code != 200) return null
            val j = JSONObject(resp)
            val arr = org.json.JSONArray(j.optJSONArray("entries")?.toString() ?: "[]")
            val entries = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                TrackEntry(e.optInt("rank"), e.optString("username"), e.optString("lap_display"), e.optString("avatar_url").takeIf { it.isNotEmpty() })
            }
            TrackBoard(j.optString("track_name", "赛道"), entries)
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchTrackBoard: ${e.message}")
            null
        }
    }

    /**
     * 上传头像（裁剪后的图片字节，JPEG/PNG，≤2MB）。阻塞 IO。
     * 成功返回 null；失败返回错误文案。
     */
    fun uploadAvatar(bytes: ByteArray, contentType: String): String? {
        val token = authToken ?: return "未登录"
        return try {
            val conn = (URL("$serverBase/v1/me/avatar").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = 30_000   // 图片上传放宽
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Authorization", "Bearer $token")
                setFixedLengthStreamingMode(bytes.size)
            }
            try {
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                if (code == 200) null else errText(code, conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            "网络错误: ${e.message}"
        }
    }

    /**
     * 下载头像（公开端点）。avatarUrl 可以是绝对 URL 或服务端返回的相对路径
     * （/v1/avatar/{id}）——相对路径拼 serverBase。返回图片字节或 null。阻塞 IO。
     */
    fun fetchAvatar(avatarUrl: String): ByteArray? {
        val url = if (avatarUrl.startsWith("http")) avatarUrl else "$serverBase$avatarUrl"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            try {
                if (conn.responseCode != 200) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun getJson(url: String, token: String? = null): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            Pair(code, text)
        } finally {
            conn.disconnect()
        }
    }

    private fun errText(code: Int, resp: String): String =
        try { JSONObject(resp).optString("error", "HTTP $code") } catch (e: Throwable) { "HTTP $code" }

    /**
     * 极简 HTTPS POST（HttpURLConnection，避 OkHttp 依赖膨胀）。
     * 返回 (status, body)。任何 IO 异常向上抛（调用方决定入队/丢弃）。
     */
    private fun postJson(url: String, body: JSONObject, token: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { os ->
                os.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            return Pair(code, if (code in 200..299 && text.isEmpty()) "{}" else text)
        } finally {
            conn.disconnect()
        }
    }
}