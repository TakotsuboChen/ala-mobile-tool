package tools.alamobile.mod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    /** 安装周期标记 prefs（内部存储：升级保留、卸载随应用删除） */
    private const val INSTALL_PREFS = "paddock_install_state"
    private const val KEY_AUTH_WIPE_DONE = "auth_wipe_done_v1"
    private const val QUEUE_TTL_MS = 30L * 24 * 3600 * 1000
    private const val RETRY_RESTORE_INTERVAL_MS = 60_000L
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 10_000

    /** 待传队列上限——防刷圈异常时无限膨胀 */
    private const val QUEUE_MAX = 200

    /** 头像磁盘缓存总量上限（裁剪后 JPEG 普遍 20~80KB，5MB ≈ 百人级榜单全量） */
    private const val AVATAR_DISK_MAX_BYTES = 5L * 1024 * 1024

    @Volatile private var appContext: Context? = null
    @Volatile private var serverBase: String = DEFAULT_SERVER
    @Volatile private var authToken: String? = null
    @Volatile private var lastUploadAt: Long = 0
    @Volatile private var lastRestoreAttemptMs: Long = 0

    /** 队列补传等后台小任务的单线程池（retryRestoreAuth 从主线程投递用） */
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "paddock-drain") }

    /** 游戏版本号（versionCode），模块初始化时从 VersionGate 注入 */
    @Volatile var versionCode: Int = 0

    fun init(ctx: Context?, serverOverride: String?) {
        if (ctx == null) return
        appContext = ctx.applicationContext
        serverBase = serverOverride?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
        wipeAuthIfFreshInstall(ctx)
        loadAuth()
    }

    /**
     * 新安装（覆盖安装保留、卸载重装触发）时强制清空一次登录态。
     *
     * 判据：内部存储 SharedPreferences 标记 [KEY_AUTH_WIPE_DONE]——内部存储
     * 升级保留、卸载随应用删除；而 auth 文件在 **external** files（不随卸载删除，
     * 卸载重装后残留）。标记缺失 = 卸载后重装（或首次安装）→ 全渠道清登录态：
     * 本地 auth 文件删除 + 全部 daemon remote prefs 空串覆盖（clearAuth）。
     * 清完立即写标记，本安装周期内（含后续升级）不再重复清。
     */
    private fun wipeAuthIfFreshInstall(ctx: Context) {
        try {
            val prefs = ctx.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_AUTH_WIPE_DONE, false)) return
            // 无条件全渠道清（不只看本地文件）：残留 token 可能只在 daemon
            // remote prefs（本地文件已删/从未落文件），漏清 = 游戏进程 loadAuth
            // 回落捞出死 token → 上传 401。clearAuth 对不存在的存储是 no-op。
            Logger.log(Log.INFO, TAG, "fresh install detected: wiping persisted auth (external file survives uninstall)")
            clearAuth()
            prefs.edit().putBoolean(KEY_AUTH_WIPE_DONE, true).apply()
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "wipeAuthIfFreshInstall failed: ${e.message}")
        }
    }

    // ── 登录态 ──────────────────────────────────────────────

    private fun authFile(): File = File(getDir(), AUTH_FILE)

    /**
     * 登录态文件是否真实存在且含 token（模块 App 登录门控的冷启动快判用）。
     * 只读本地文件（模块进程写侧权威），不走 daemon/Provider 回落——冷启动
     * 时 service 未必已绑，且模块进程本地文件正是 saveAuth 的第一落点。
     * 结果为 false 即门控弹登录弹窗的场景；登录成功后 saveAuth 落文件，
     * hasToken/登录 UI 状态随之推进。
     */
    fun hasPersistedAuth(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, AUTH_FILE)
            if (!f.exists()) return false
            val o = JSONObject(f.readText())
            o.optString("token", "").isNotEmpty()
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "hasPersistedAuth: ${e.message}")
            false
        }
    }

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
        // 第三级回落（NPatch 本地模式专用）：Remote Preferences 在 NPatch 下是
        // 空壳——loader 的 requestRemotePreferences 返回 Bundle.EMPTY，模块 App
        // 经管理器 binder 写入的 NPatchRemoteStore 与游戏进程本地 fallback store
        // 是两个物理文件（用户 A/B 日志实证：41 次重试全 null）。config 有
        // ConfigProvider 兜底，token 此前没有——补上同款通道：游戏进程 call
        // content://tools.alamobile.mod.config 的 read_token，Provider 自动拉起
        // 模块进程读它 filesDir 里的 auth 文件（saveAuth 本地文件路径）。
        if (authToken.isNullOrBlank() && remoteTokenReader != null) {
            // remoteTokenReader 非空 = 游戏进程（模块进程自己直读本地文件无需此路）
            try {
                val uri = android.net.Uri.parse("content://${tools.alamobile.mod.config.ConfigProvider.AUTHORITY}")
                val result = appContext?.contentResolver?.call(uri, tools.alamobile.mod.config.ConfigProvider.READ_TOKEN_METHOD, null, null)
                val t = result?.getString(tools.alamobile.mod.config.ConfigProvider.KEY_TOKEN)?.takeIf { it.isNotEmpty() }
                if (t != null) {
                    authToken = t
                    Logger.log(Log.INFO, TAG, "auth restored via ConfigProvider (NPatch local mode)")
                } else {
                    Logger.log(Log.WARN, TAG, "loadAuth: ConfigProvider token null (not logged in on module app, or provider unreachable)")
                }
            } catch (e: Throwable) {
                Logger.log(Log.WARN, TAG, "ConfigProvider token read failed: ${e.message?.take(80)}")
            }
        }
    }

    /**
     * 游戏进程注入的 daemon token 读取器（与 ModConfig.remoteConfigReader 同模式）。
     * AlaMobileModule.onPackageReady 里赋值 = getRemotePreferences(PREF_GROUP).getString(KEY_PADDOCK_TOKEN)。
     * 模块进程不注入（null）——它本地文件直读。
     */
    @Volatile var remoteTokenReader: (() -> String?)? = null

    /** 登录成功后持久化 token（模块进程写：本地 + 全部 service 双写；游戏进程恢复用 daemon） */
    fun saveAuth(token: String) {
        authToken = token
        try {
            val o = JSONObject().put("token", token)
            authFile().writeText(o.toString())
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "saveAuth failed: ${e.message}")
        }
        // 写入**全部** service（LSPosed lspd + NPatch 管理器 store 是两个物理存储，
        // 游戏进程经 NPatch loader 读管理器 store——只写一个就会出现"登录了但
        // 游戏读到另一个 store 里的残留旧 token"（2026-09-10 实证：上传 401 根因）。
        // service 未绑定时主动尝试 NPatch 绑定兜底（NPatch 无 daemon 异步推送，
        // 登录页是 bindNpatchRemoteService 在模块进程里最晚的触发时机）。仍失败
        // 才降级"只写本地"（onServiceBind flush 兜底补写）。
        try {
            var ctx = appContext
            if (App.xposedService == null && ctx != null) {
                try { App.bindNpatchRemoteService(ctx) } catch (_: Throwable) {}
            }
            val services = App.allServices.toList()
            if (services.isEmpty()) {
                Logger.log(Log.WARN, TAG, "saveAuth: no service bound, token saved locally only (will flush on service bind)")
            }
            for (service in services) {
                try {
                    service.getRemotePreferences(App.PREF_GROUP)
                        .edit()
                        .putString(App.KEY_PADDOCK_TOKEN, token)
                        .apply()
                    val fw = try { service.frameworkName } catch (_: Throwable) { "?" }
                    Logger.log(Log.INFO, TAG, "token saved to remote prefs ($fw)")
                } catch (e: Throwable) {
                    Logger.log(Log.WARN, TAG, "remote token save failed: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "remote token save failed: ${e.message}")
        }
    }

    fun clearAuth() {
        authToken = null
        authFile().delete()
        // 同步清**全部** service：退出登录必须两侧（lspd + NPatch store）都清，
        // 否则游戏进程还能从没清的那个 store 读到残留 token。
        //
        // ⚠️ 用 putString("") 空串覆盖而非 remove()：实测（2026-09-12:11，双框架）
        // remove 的 delete diff 在 LSPosed daemon 和 NPatch 管理器两侧**都不生效**
        //（日志打成功、db 里行还在），putString 却一直可靠（登录写入均成功）。
        // 读侧 loadAuth / remoteTokenReader 全部 takeIf { isNotEmpty() }——空串
        // 天然等于"无 token"，与 remove 等效。
        val services = App.allServices.toList()
        if (services.isEmpty()) {
            Logger.log(Log.WARN, TAG, "clearAuth: no service bound, daemon stores NOT cleared (stale token may persist)!")
        }
        for (service in services) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .putString(App.KEY_PADDOCK_TOKEN, "")
                    .apply()
                val fw = try { service.frameworkName } catch (_: Throwable) { "?" }
                Logger.log(Log.INFO, TAG, "token blanked in remote prefs ($fw)")
            } catch (e: Throwable) {
                Logger.log(Log.WARN, TAG, "remote token blank failed: ${e.message}")
            }
        }
    }

    fun hasToken(): Boolean = !authToken.isNullOrBlank()

    /**
     * 登录态的**权威判定**（游戏进程门控用）：call ConfigProvider read_token，
     * 由模块进程读它自己 externalFilesDir 下的 auth 文件（saveAuth/clearAuth
     * 的第一落点，写删都可靠）。
     *
     * ⚠️ 不用 [hasToken]：其内存值可能来自 daemon remote prefs 回落——实测
     * （2026-09-10）LSPosed daemon 与 NPatch 管理器两侧的 remote prefs **写入
     * 和删除都不可靠**（put/remove 打成功日志但库里数据不变），退出登录后残留
     * token 会造成"模块已退出登录、游戏仍判已登录"的分裂。auth 本地文件是
     * 唯一读写闭环可靠的存储。
     *
     * 阻塞 IPC（Binder call，模块进程可能被冷拉起，首次 100ms~1s），只能
     * 工作线程调用。任何异常（模块进程被杀/Provider 不可达）→ false=未登录
     * （门控 fail-closed）。
     */
    fun queryLoginStateFromModule(): Boolean {
        val ctx = appContext ?: return false
        return try {
            val uri = android.net.Uri.parse("content://${tools.alamobile.mod.config.ConfigProvider.AUTHORITY}")
            val result = ctx.contentResolver.call(
                uri,
                tools.alamobile.mod.config.ConfigProvider.READ_TOKEN_METHOD, null, null
            )
            result?.getString(tools.alamobile.mod.config.ConfigProvider.KEY_TOKEN)?.isNotEmpty() == true
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "queryLoginStateFromModule: ${e.message}")
            false
        }
    }

    /**
     * 游戏进程侧 token 缺失时的周期性重试恢复（PaddockUploader 1Hz 轮询里调用）。
     *
     * 覆盖两个实证时序坑：
     * 1. 注册/登录发生在游戏启动之后——启动时 loadAuth 拿不到 token；
     * 2. NPatch 用户登录时 service 未绑定（"token saved locally only"），
     *    daemon Remote Preferences 的 key 晚到（甚至要等下次打开模块 App 才写入）。
     *
     * loadAuth 只读本地文件/daemon prefs，主线程安全（无网络）；恢复成功后的
     * 队列补传丢给 IO 线程（drainQueue 是 HTTPS 调用，严禁主线程——NetworkOnMainThreadException）。
     * 60s 限流：remote prefs 读取便宜，但也不必每秒做。
     */
    fun retryRestoreAuth() {
        val now = System.currentTimeMillis()
        if (now - lastRestoreAttemptMs < RETRY_RESTORE_INTERVAL_MS) return
        lastRestoreAttemptMs = now
        val hadToken = !authToken.isNullOrBlank()
        loadAuth()
        if (!hadToken && !authToken.isNullOrBlank()) {
            Logger.log(Log.INFO, TAG, "token restored on retry, draining pending queue (${pendingCount()})")
            val t = authToken ?: return
            ioExecutor.execute {
                try {
                    drainQueue(t, 10)
                } catch (e: Throwable) {
                    Logger.log(Log.WARN, TAG, "retry drainQueue: ${e.message}")
                }
            }
        }
    }

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
        /** 服务端下发的版本化头像 URL（?v=）；旧版服务端无此字段为空串 */
        val avatarUrl: String = "",
        val totalPoints: Long = 0,
    )

    fun fetchMe(): MeResult {
        val token = authToken ?: return MeResult(ok = false, needRelogin = true)
        return try {
            val (code, resp) = getJson("$serverBase/v1/me", token)
            when {
                code == 200 -> {
                    val j = JSONObject(resp)
                    // 版本化头像 URL 记录到内存：个人卡/磁盘缓存失效全靠它
                    val avatarUrl = j.optString("avatar_url")
                    setMyAvatarUrl(avatarUrl.takeIf { it.isNotEmpty() })
                    MeResult(
                        ok = true,
                        userId = j.optString("user_id"),
                        username = j.optString("username"),
                        regSeq = j.optLong("reg_seq"),
                        hasAvatar = j.optBoolean("has_avatar"),
                        avatarUrl = avatarUrl,
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
            Logger.log(
                Log.WARN, TAG,
                "uploadLap: no token, queued locally (gp=$gpIndex, ${pendingCount()} pending)"
            )
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

    /**
     * 围场页进入时的队列补传入口（模块进程，UI 已有 token 才调得到这里）。
     * 有待传条数时 drain 并打日志；0 条时静默。drainQueue 是 HTTPS 调用，
     * 统一丢 IO 线程池——ViewModel 协程在 withContext(IO) 之后已回到主线程，
     * 这里若同步调 drainQueue 会 NetworkOnMainThreadException（已分发版实证）。
     * 与游戏进程上传路径并发安全（不同进程各读各的队列文件，天然隔离）。
     */
    fun drainPendingQueueOnEntry() {
        try {
            val token = authToken ?: return
            if (pendingCount() == 0) return
            Logger.log(Log.INFO, TAG, "paddock page entry: draining ${pendingCount()} pending laps")
            ioExecutor.execute {
                try {
                    val ok = drainQueue(token, 20)
                    Logger.log(Log.INFO, TAG, "paddock page entry: drained $ok laps")
                } catch (e: Throwable) {
                    Logger.log(Log.WARN, TAG, "drainPendingQueueOnEntry drain: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "drainPendingQueueOnEntry: ${e.message}")
        }
    }

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
     * 下载头像（公开端点，带三级缓存）。avatarUrl 可以是绝对 URL 或服务端返回的相对路径
     * （/v1/avatar/{id}?v=…）——相对路径拼 serverBase。返回图片字节或 null。阻塞 IO。
     *
     * 缓存层级（2026-09-06 定案，VPS 流量出口优化）：
     * 1. 内存 LruCache（位图，UI 直接可用）
     * 2. 磁盘 cacheDir/paddock_avatars/（**降采样后的 JPEG**，冷启动免下载）
     * 3. 网络（仅缓存全 miss 时）
     * 磁盘存降采样位图而非原图字节：服务端存的是上传原图（实测单张最大 ~1MB），
     * 原字节缓存 5MB 上限在满编榜单下必触发 trim 自删 → 重启后大面积 miss →
     * 全量重下死循环（实机日志实证）。降采样后单张 ~20KB，67 人全量 <1.5MB，
     * trim 永不触发。缓存失效完全靠 URL 版本号（服务端 avatar_version，上传即变）。
     */
    fun fetchAvatar(avatarUrl: String, cacheKey: String = avatarUrl): Bitmap? {
        memAvatarCache.get(cacheKey)?.let { return it }
        val url = if (avatarUrl.startsWith("http")) avatarUrl else "$serverBase$avatarUrl"
        // 磁盘命中 → 回填内存（文件本身已是降采样 JPEG，直接解码）
        val diskFile = avatarDiskFile(cacheKey)
        if (diskFile.isFile) {
            try {
                BitmapFactory.decodeFile(diskFile.absolutePath)?.let {
                    Logger.log(Log.INFO, TAG, "fetchAvatar: disk HIT $cacheKey")
                    memAvatarCache.put(cacheKey, it)
                    return it
                }
                // 解码失败 = 文件损坏，删除后走网络
                diskFile.delete()
            } catch (_: Throwable) {
                // 读失败同理
            }
        }
        Logger.log(Log.INFO, TAG, "fetchAvatar: MISS, downloading $cacheKey")
        // 网络 → 解码降采样 → 压缩落盘 → 回填内存
        val bmp = try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
            }
            val decoded = try {
                if (conn.responseCode != 200) return null
                conn.inputStream.use { decodeAvatarScaled(it.readBytes()) }
            } finally {
                conn.disconnect()
            }
            decoded ?: return null
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchAvatar failed: ${e.message}")
            return null
        }
        try {
            val tmp = File(diskFile.parentFile, diskFile.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            tmp.renameTo(diskFile)  // 原子替换：并发下载同 URL 也不会出现半截文件
            trimAvatarDiskCache()
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "avatar disk cache write failed: ${e.message}")
        }
        memAvatarCache.put(cacheKey, bmp)
        return bmp
    }

    /** 头像磁盘缓存文件（URL 哈希做文件名，避开 / ? 等非法字符）。 */
    private fun avatarDiskFile(cacheKey: String): File {
        val ctx = appContext ?: error("PaddockClient not initialized")
        val dir = File(ctx.cacheDir, "paddock_avatars")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, Integer.toHexString(cacheKey.hashCode()) + ".img")
    }

    /**
     * 磁盘缓存总量上限（超限按 lastModified 删最旧）。不按 URL 集合精确失效——
     * 换头像后旧 ?v= 文件会残留至此自然淘汰，逻辑简单且不会误删其他榜单条件下
     * 仍有效的缓存（URL 集合随 tab/筛选变化，按集合清理必误删）。
     */
    private fun trimAvatarDiskCache() {
        try {
            val ctx = appContext ?: return
            val dir = File(ctx.cacheDir, "paddock_avatars")
            val files = dir.listFiles()?.filter { it.name.endsWith(".img") } ?: return
            var total = files.sumOf { it.length() }
            if (total <= AVATAR_DISK_MAX_BYTES) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= AVATAR_DISK_MAX_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (_: Throwable) {
        }
    }

    @Volatile private var myAvatarUrlField: String? = null

    /** fetchMe 成功后记录服务端下发的版本化 avatar_url（个人卡/磁盘缓存共用）。 */
    fun setMyAvatarUrl(url: String?) {
        myAvatarUrlField = url
    }

    private val memAvatarCache = object : android.util.LruCache<String, Bitmap>(256) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1  // 按张数计（≤256 张），解码已降采样
    }

    /** 仅查内存缓存（UI 首帧同步路径用，绝不阻塞）；miss 返回 null 走异步加载。 */
    fun fetchAvatarFromCache(cacheKey: String): Bitmap? = memAvatarCache.get(cacheKey)

    /** 头像解码降采样（36~56dp 显示尺寸；512px 全尺寸 ×N 张的内存/GC 压力不可接受）。 */
    private fun decodeAvatarScaled(bytes: ByteArray): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 144) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    } catch (e: Throwable) {
        null
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