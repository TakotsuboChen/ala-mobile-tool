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
            AlaMobileModule.logX(Log.WARN, TAG, "loadAuth failed: ${e.message}")
        }
    }

    /** 登录成功后持久化 token（ConfigActivity 登录页与游戏进程共用这一份） */
    fun saveAuth(token: String) {
        authToken = token
        try {
            val o = JSONObject().put("token", token)
            authFile().writeText(o.toString())
        } catch (e: Throwable) {
            AlaMobileModule.logX(Log.WARN, TAG, "saveAuth failed: ${e.message}")
        }
    }

    fun clearAuth() {
        authToken = null
        authFile().delete()
    }

    fun hasToken(): Boolean = !authToken.isNullOrBlank()

    /**
     * 阻塞登录。成功返回 true 并保存 token；失败返回 false（错误文案从服务端原样带回）。
     * ConfigActivity/登录 UI 在工作线程调用。
     */
    fun login(username: String, password: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject().put("username", username).put("password", password)
            val (code, resp) = postJson("$serverBase/v1/auth/login", body, null)
            if (code == 200) {
                val token = JSONObject(resp).optString("token")
                if (token.isNotEmpty()) {
                    saveAuth(token)
                    Pair(true, "OK")
                } else {
                    Pair(false, "响应缺少 token")
                }
            } else {
                Pair(false, errText(code, resp))
            }
        } catch (e: Throwable) {
            Pair(false, "网络错误: ${e.message}")
        }
    }

    /**
     * 注册申请：返回 (成功?, reg_code 或错误文案)。
     * 成功时用户需去 CAMDA 群发送 "申请围场通行证#<code>"。
     */
    fun registerRequest(username: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject().put("username", username)
            val (code, resp) = postJson("$serverBase/v1/auth/register-request", body, null)
            if (code == 200) {
                val j = JSONObject(resp)
                Pair(true, j.optString("message_hint", j.optString("reg_code")))
            } else {
                Pair(false, errText(code, resp))
            }
        } catch (e: Throwable) {
            Pair(false, "网络错误: ${e.message}")
        }
    }

    /**
     * 注册校验（群内校验成功后调用）：返回 (成功?, 登录响应或错误文案)。
     * 成功时自动保存 token 完成登录。
     */
    fun registerVerify(regCode: String, username: String, password: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject()
                .put("reg_code", regCode)
                .put("username", username)
                .put("password", password)
            val (code, resp) = postJson("$serverBase/v1/auth/register-verify", body, null)
            if (code == 201) {
                val token = JSONObject(resp).optString("token", "")
                if (token.isNotEmpty()) saveAuth(token)
                Pair(true, "欢迎加入围场！")
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
                        AlaMobileModule.logX(Log.INFO, TAG, "queue drained")
                    }
                    toast
                }
                code == 401 -> { enqueue(gpIndex, lapMs); null }
                else -> { // 4xx 参数类错误：不重传（服务端明确拒绝）
                    AlaMobileModule.logX(Log.WARN, TAG, "upload rejected: $code ${errText(code, resp)}")
                    null
                }
            }
        } catch (e: IOException) {
            enqueue(gpIndex, lapMs)
            null
        } catch (e: Throwable) {
            AlaMobileModule.logX(Log.WARN, TAG, "uploadLap failed: ${e.message}")
            null
        }
    }

    /**
     * 服务端 Toast 文案组装。level 映射（服务端字段 toast.level + track 中文名）。
     * 四条件（去重后单条），模板按用户原文定案：
     * "您已刷新[赛道名]的历史/版本的个人/全服最佳成绩"
     */
    private fun parseToast(resp: String): String? {
        return try {
            val toast = JSONObject(resp).optJSONObject("toast") ?: return null
            val track = toast.optString("track", "赛道")
            when (toast.optString("level")) {
                "alltime_server" -> "您已刷新$track 的全服历史最佳成绩！"
                "version_server" -> "您已刷新$track 的全服版本最佳成绩！"
                "alltime_personal" -> "您已刷新$track 的个人历史最佳成绩！"
                "version_personal" -> "您已刷新$track 的个人版本最佳成绩！"
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
            AlaMobileModule.logX(Log.WARN, TAG, "enqueue failed: ${e.message}")
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
    data class PointsEntry(val username: String, val points: Int)

    data class TrackEntry(val rank: Int, val username: String, val lapDisplay: String)

    data class TrackBoard(
        val trackName: String,
        val entries: List<TrackEntry>,
    )

    /** GET /v1/leaderboard/points（version=null → 总榜）。阻塞 IO。 */
    fun fetchPointsBoard(version: Int?): List<PointsEntry> {
        return try {
            val q = if (version != null) "?version=$version" else ""
            val (code, resp) = getJson("$serverBase/v1/leaderboard/points$q")
            if (code != 200) return emptyList()
            val arr = org.json.JSONArray(JSONObject(resp).optJSONArray("entries")?.toString() ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                PointsEntry(e.optString("username"), e.optInt("points"))
            }
        } catch (e: Throwable) {
            AlaMobileModule.logX(Log.WARN, TAG, "fetchPointsBoard: ${e.message}")
            emptyList()
        }
    }

    /** GET /v1/leaderboard/track/{gp}（version=null → 该赛道总榜）。阻塞 IO。 */
    fun fetchTrackBoard(gpIndex: Int, version: Int?): TrackBoard? {
        return try {
            val q = if (version != null) "?version=$version" else ""
            val (code, resp) = getJson("$serverBase/v1/leaderboard/track/$gpIndex$q")
            if (code != 200) return null
            val j = JSONObject(resp)
            val arr = org.json.JSONArray(j.optJSONArray("entries")?.toString() ?: "[]")
            val entries = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                TrackEntry(e.optInt("rank"), e.optString("username"), e.optString("lap_display"))
            }
            TrackBoard(j.optString("track_name", "赛道"), entries)
        } catch (e: Throwable) {
            AlaMobileModule.logX(Log.WARN, TAG, "fetchTrackBoard: ${e.message}")
            null
        }
    }

    private fun getJson(url: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
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