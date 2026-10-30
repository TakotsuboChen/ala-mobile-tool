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
import tools.alamobile.mod.config.CrossPkgMailbox
import tools.alamobile.mod.util.Logger

/**
 * 围场（Paddock）客户端：计时赛有效圈上报 + 登录态维护 + 本地待传队列。
 *
 * 定案映射（docs/design/PADDOCK_PLAN.md）：
 * - 每有效圈都上传（服务端去重留最佳）；Toast 判定权在服务端响应。
 * - 未登录/弱网时圈进本地待传队列（30 天时效），登录成功后补传。
 * - **任何上传失败都入本地队列,绝不丢圈**（2026-09-10 定案）;本地有队列时游戏
 *   进程每 30s 静默补传一批（失败不弹 Toast，只落日志）。
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

    /**
     * 游戏进程门控 token 缓存（内部 prefs：进程死亡保留、卸载随删）。
     * 写入方只有 [cacheGameToken]（游戏进程身份，任何通道成功恢复 token 时）；
     * 读取方是 [loadAuth] 第四级回落；清除方是 [clearAuth]（显式登出/新装清态）
     * 与 [queryLoginStateFromModule] 的权威否决路径。
     */
    private const val GATE_CACHE_PREFS = "paddock_gate_token"
    private const val GATE_CACHE_KEY = "token"
    private const val QUEUE_TTL_MS = 30L * 24 * 3600 * 1000
    private const val RETRY_RESTORE_INTERVAL_MS = 60_000L
    private const val CONNECT_TIMEOUT = 8000
    private const val READ_TIMEOUT = 10_000

    /** 待传队列上限——防刷圈异常时无限膨胀 */
    private const val QUEUE_MAX = 200

    /** 头像磁盘缓存总量上限（裁剪后 JPEG 普遍 20~80KB，5MB ≈ 百人级榜单全量） */
    private const val AVATAR_DISK_MAX_BYTES = 5L * 1024 * 1024

    /**
     * 圈速上传失败但**已进本地待传队列**时的提示文案(2026-09-10)。
     * 与 [uploadLap] 的入队分支一一对应:**凡入队的失败路径都返回它**,否则
     * 「已保存在本地」就是假话。200 以外全部入队(含 4xx/5xx/异常/无 token),
     * 是否继续重试由 [isRetryableStatus] 在补传时判定。补传时机:下一次上传
     * 成功时 / 进围场页时 / 每 30s 静默周期 / token 恢复重试。
     */
    private const val TOAST_LAP_QUEUED = "圈速上传失败,已保存在本地,待连接后自动补传!"

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
     * 新安装（覆盖安装保留、卸载重装触发）时强制清空一次登录态（**有意设计**：
     * 装新版 = 全体用户掉登录重新登录一次，用户定案 2026-09-11）。
     *
     * 判据：内部存储 SharedPreferences 标记 [KEY_AUTH_WIPE_DONE]——内部存储
     * 升级保留、卸载随应用删除；而 auth 文件在 **external** files（不随卸载删除，
     * 卸载重装后残留）。标记缺失 = 卸载后重装（或首次安装）→ 全渠道清登录态：
     * 本地 auth 文件删除 + 全部 daemon remote prefs 空串覆盖（clearAuth）。
     * 清完立即写标记，本安装周期内（含后续升级）不再重复清。
     */
    private fun wipeAuthIfFreshInstall(ctx: Context) {
        try {
            // ⚠️ **仅模块 App 进程执行**（2026-09-14 实机修复）。游戏进程没有独立
            // 安装周期语义，却持有**自己包**的 INSTALL_PREFS 标记与"自己包"的
            // wipe 判据；NPatch「清数据」会把两个进程的内部 prefs 一并清掉（"清
            // 数据"与"卸载重装"在物理上不可区分）→ 模块 App 登录写好信箱后，游戏
            // 进程首次冷启动也会判定"新安装"并 clearAuth，把刚建立的权威信箱
            // 清成空 token（实机 21:41:03：Mailbox write OK(60B) 之后游戏进程
            // 写 12B 空 token，信箱"存在即权威"→ 误判已登出，零 hook + 循环
            // Toast）。游戏进程只做 loadAuth 读侧，写侧统一归模块 App。
            if (remoteTokenReader != null) return
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
        // ── 第五级：跨包"信箱"（2026-09-12，游戏进程专用）──
        // 模块 App 持 AFA 写 `/sdcard/Android/media/<游戏包>/paddock_auth.json`；
        // 游戏进程同 uid 直读**自己包**該文件（零权限、不经 AMS、不依赖模块存活）。
        // 这是唯一在三重约束（模块不运行 / ColorOS 关关联启动 / 游戏 stopped state）
        // 下仍可达的登录态通道。
        //
        // ⚠️ **信箱文件"存在即权威"**：存在且有 token = 已登录；存在但无/空 token
        // = 已登出。模块 App 登出时**写空 token 而非删文件**——这样"登录过又登出"
        // （文件存在、token 空）与"从未登录"（文件不存在）可区分，前者必须否决
        // 门控缓存（防反向分裂：模块已登出、游戏凭陈旧缓存误放行——用户红线）。
        // 信箱不存在时回落到后续通道（含门控缓存）。
        val ctx0 = appContext
        var mailboxAuthoritative = false
        if (remoteTokenReader != null && ctx0 != null) {
            val raw = CrossPkgMailbox.read(ctx0.packageName, CrossPkgMailbox.AUTH_FILE)
            if (raw != null) {
                mailboxAuthoritative = true
                authToken = try {
                    JSONObject(raw).optString("token", "").takeIf { it.isNotEmpty() }
                } catch (_: Throwable) { null }
                if (authToken == null) {
                    clearGateCache()
                    Logger.log(Log.INFO, TAG, "auth: mailbox authoritative → logged OUT (gate cache cleared)")
                } else {
                    cacheGameToken(authToken!!)
                    Logger.log(Log.INFO, TAG, "auth restored from mailbox (authoritative)")
                }
            }
        }
        if (mailboxAuthoritative) return

        // 第四级回落（2026-09-11，登录门控强保证）：门控 token 缓存。游戏进程
        // 身份下读模块进程 auth 文件的所有通道都可能被包可见性拦死（"Unknown
        // authority"，实机两例——尤其模块 App 被 force-stop 后系统直接隐藏它），
        // 但游戏进程对**自己包名**的内部 prefs 读写 100% 可达——只要本进程曾
        // 成功恢复过 token（[cacheGameToken]），后续启动就以缓存兜底，绝不误锁。
        // 失效规则（防反向分裂）：模块进程显式登出/新装清态 → 信箱写空 token
        // 成为权威（上方已 return），缓存不会把已登出状态捞回来；token 服务端
        // 失效 → fetchMe needRelogin → clearAuth。
        if (authToken.isNullOrBlank() && remoteTokenReader != null) {
            val cached = try {
                appContext?.getSharedPreferences(GATE_CACHE_PREFS, Context.MODE_PRIVATE)
                    ?.getString(GATE_CACHE_KEY, null)?.takeIf { it.isNotEmpty() }
            } catch (_: Throwable) { null }
            if (cached != null) {
                authToken = cached
                Logger.log(Log.INFO, TAG, "auth restored from gate token cache (all channels blocked)")
            }
        }
        // 游戏进程任何通道恢复成功 → 写门控缓存（下次启动哪怕全通道被拦也能兜底）。
        val restored = authToken
        if (restored != null && remoteTokenReader != null) {
            cacheGameToken(restored)
        }
    }

    /**
     * 游戏进程专用：任何通道成功恢复 token 后调用（[loadAuth] 末尾统一调），
     * 写进门控缓存（游戏进程内部 prefs，读 100% 可达，与模块 App 死活无关）。
     * 模块进程不写（remoteTokenReader==null = 模块进程，走不到这）。
     */
    private fun cacheGameToken(token: String) {
        try {
            appContext?.getSharedPreferences(GATE_CACHE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(GATE_CACHE_KEY, token)?.apply()
        } catch (_: Throwable) {}
    }

    /** 门控缓存清除（clearAuth 与权威否决路径共用）。 */
    private fun clearGateCache() {
        try {
            appContext?.getSharedPreferences(GATE_CACHE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.remove(GATE_CACHE_KEY)?.apply()
        } catch (_: Throwable) {}
    }

    /**
     * 写登录态到跨包信箱（两个游戏包各一份）。模块 App 侧需 AFA 才能写他包
     * media 目录；游戏进程侧写自己目录无需权限。失败静默（返回 false 由调用方
     * 忽略）——原有通道照常兜底。
     */
    private fun writeAuthMailbox(content: String) {
        val ctx = appContext ?: return
        for (pkg in CrossPkgMailbox.GAME_PACKAGES) {
            // 游戏进程只写自己包那一份（避免游戏进程越权写另一个游戏包）。
            if (remoteTokenReader != null && pkg != ctx.packageName) continue
            CrossPkgMailbox.write(pkg, CrossPkgMailbox.AUTH_FILE, content)
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
        // 跨包信箱写入（2026-09-12）：模块 App 持 AFA 时把 token 写进游戏 media 目录，
        // 游戏进程冷启动同 uid 直读——唯一在"模块不运行 + ColorOS 关关联启动"下可达的
        // 登录态通道。未授 AFA 时静默失败（write 返回 false），后续通道照常兜底。
        // 写的是"有 token"版本，与 clearAuth 的"空 token"版本构成权威存在性信号。
        writeAuthMailbox(JSONObject().put("token", token).toString())
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
            // liveServices() 而非 allServices.toList()：后者可能含已死 binder，
            // 对死 binder 写会抛 ServiceException，日志噪音且无实际写入。
            val services = App.liveServices()
            if (services.isEmpty()) {
                Logger.log(Log.WARN, TAG, "saveAuth: no live service bound, token saved locally only (will flush on service bind)")
            }
            for (service in services) {
                try {
                    service.getRemotePreferences(App.PREF_GROUP)
                        .edit()
                        .putString(App.KEY_PADDOCK_TOKEN, token)
                        .apply()
                    val fw = try { service.frameworkName } catch (_: Throwable) { "dead-binder" }
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
        // 跨包信箱写**空 token**（非删文件）——2026-09-12 反向红线修复的关键：
        // "存在但 token 空" = 权威的"已登出"信号，游戏进程见之即否决门控缓存。
        // 若改成删文件，则与"从未登录"不可区分，游戏会回落门控缓存把旧 token
        // 捞回来 → 模块已登出、游戏仍放行（用户红线）。写空还额外规避了"模块
        // App 无 AFA 时删除失败但写入也可能失败"的不对称风险（两者同一路径）。
        writeAuthMailbox(JSONObject().put("token", "").toString())
        // 门控 token 缓存同清（**仅当调用方是游戏进程时有效**）：模块 App 登出时
        // clearGateCache 清的是模块进程自己的 prefs，够不到游戏进程的缓存——
        // 那一路由"信箱写空 token"在游戏启动时权威否决（见 loadAuth 第五级）。
        clearGateCache()
        // 同步清**全部** service：退出登录必须两侧（lspd + NPatch store）都清，
        // 否则游戏进程还能从没清的那个 store 读到残留 token。
        //
        // ⚠️ 用 putString("") 空串覆盖而非 remove()：实测（2026-09-12:11，双框架）
        // remove 的 delete diff 在 LSPosed daemon 和 NPatch 管理器两侧**都不生效**
        //（日志打成功、db 里行还在），putString 却一直可靠（登录写入均成功）。
        // 读侧 loadAuth / remoteTokenReader 全部 takeIf { isNotEmpty() }——空串
        // 天然等于"无 token"，与 remove 等效。
        val services = App.liveServices()
        if (services.isEmpty()) {
            Logger.log(Log.WARN, TAG, "clearAuth: no live service bound, daemon stores NOT cleared (stale token may persist)!")
        }
        for (service in services) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .putString(App.KEY_PADDOCK_TOKEN, "")
                    .apply()
                val fw = try { service.frameworkName } catch (_: Throwable) { "dead-binder" }
                Logger.log(Log.INFO, TAG, "token blanked in remote prefs ($fw)")
            } catch (e: Throwable) {
                Logger.log(Log.WARN, TAG, "remote token blank failed: ${e.message}")
            }
        }
    }

    fun hasToken(): Boolean = !authToken.isNullOrBlank()

    /**
     * 登录态的**权威判定**（游戏进程门控用）——四级回落：
     *
     * ① ConfigProvider read_token：模块进程读它自己 externalFilesDir 下的 auth
     *    文件（saveAuth/clearAuth 的第一落点，写删都可靠）。NPatch 本地模式下
     *    provider 与游戏同进程同 uid（模块类合并进共存版 APK），进程内 call
     *    必达；返回 false = auth 文件确实没 token = 未登录。
     *    ⚠️ 实机两例（2026-09-11 用户日志）证伪"NPatch 下 ① 必达"：共存版
     *    游戏 APK 同样改不了 manifest（无 <queries> 声明模块包），Android 11+
     *    包可见性下游戏进程跨包 resolve 模块 provider 也会 "Unknown authority"
     *    （Binder call 没发出去）——与 LSPosed 下游戏包对模块包无可见性是同一机制。
     * ② remote prefs 回落：provider 被拦时读 daemon remote prefs（LSPosed 下
     *    读侧权威；NPatch 下是空壳，读回 null 继续往下）。
     * ③④ 见 [loadAuth] 的第四级回落与内存 authToken——本函数兜底语义：
     *    只要任何一个渠道（含游戏进程本地门控缓存）捞到 token 即放行。
     *
     * ⚠️ ② reader 为 null 时**不是**"未登录"——reader 注入点在 deferred 块
     *    （ShadowHook init 之后），登录门控可能在其之前发起判定（2026-09-11
     *    用户日志实锤：reader==null 静默 return false → 误锁，且 CAS 占位后
     *    永无翻身）。null 一律走 [loadAuth] 全渠道重探（其含缓存兜底）。
     *
     * 阻塞 IPC（Binder call，模块进程可能被冷拉起，首次 100ms~1s），只能
     * 工作线程调用。全渠道确认无 token → false=未登录（门控 fail-closed）。
     */
    fun queryLoginStateFromModule(): Boolean {
        val ctx = appContext ?: return false
        // ① ConfigProvider：null=通道不可达（区别于"可达但未登录"=false）
        val viaProvider: Boolean? = try {
            val uri = android.net.Uri.parse("content://${tools.alamobile.mod.config.ConfigProvider.AUTHORITY}")
            val result = ctx.contentResolver.call(
                uri,
                tools.alamobile.mod.config.ConfigProvider.READ_TOKEN_METHOD, null, null
            )
            result?.getString(tools.alamobile.mod.config.ConfigProvider.KEY_TOKEN)?.isNotEmpty() == true
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "queryLoginStateFromModule: ${e.message}")
            null
        }
        if (viaProvider != null) {
            // ① 可达 = 权威判定（auth 文件是 saveAuth/clearAuth 的第一落点）。
            // 权威"未登录"（false）时顺手清门控缓存：模块进程可能刚被登出/
            // 新装清态，缓存若不清，下次 ① 再被拦时会把已登出状态捞回来
            //（反向分裂：模块未登录、游戏却放行——用户红线）。
            if (!viaProvider) clearGateCache()
            return viaProvider
        }
        // ② remote prefs 回落 + ③ 缓存/内存兜底：全部经 loadAuth 重探（它有
        //    remote reader、ConfigProvider、门控缓存三级，且对结果有日志）。
        //    仅在 ① null（通道不可达）时走到——通道不可达 ≠ 未登录，绝不
        //    静默判死。
        loadAuth()
        val t = hasToken()
        Logger.log(Log.INFO, TAG, "queryLoginStateFromModule: provider unreachable, full re-probe → $t")
        return t
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
     * 模块 App 侧：把已恢复的登录态补写回跨包信箱（[App.onServiceBind] 兜底调用）。
     *
     * 动机（2026-09-14 实机）：信箱是游戏进程登录态的权威通道，但它的唯一写点在
     * [saveAuth]/[clearAuth]。若信箱曾在旧版逻辑下被清空（如游戏进程误触发的
     * fresh-install 清理），此后模块 App 仅 daemon 仍有 token、信箱一直是空的——
     * 游戏进程"存在即权威"看到空 token 就否决 daemon，登录态被单方面压掉。
     * 模块 App 冷启动时（本地文件有 token）补一次信箱，与 daemon flush 同一时机，
     * 把两侧重新对齐。写的是"有 token"版本；token 为空时 no-op（不覆盖权威"已登出"）。
     */
    fun flushAuthToMailbox() {
        val t = authToken ?: return
        writeAuthMailbox(JSONObject().put("token", t).toString())
    }

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
     * 一圈的辅助配置上报载荷（原始枚举字符串，缺失 = null）。
     *
     * 服务端据这些**原始值**派生"关 TC / 低档 ABS / 线性踏板"并算加成
     * （见 paddock-api `score.rs`）——模块不报派生结论，规则调整无需发新版。
     */
    data class LapAssist(
        val pedalMode: String?,
        val tcMode: String?,
        val tcStrength: String?,
        val absMode: String?,
        val absStrength: String?,
    ) {
        /**
         * 五维全缺（native 未就绪 / 旧数据）→ 不上报字段。
         *
         * ⚠️ 2026-10-30 起「无效」不再折成缺失：整圈配置中途变过的维上报字面量
         *    `"invalid"`，与「缺失（null）」在服务端可区分。故这里只剩"真的什么
         *    都没拿到"一种情况。
         */
        val allMissing: Boolean
            get() = pedalMode == null && tcMode == null && tcStrength == null &&
                absMode == null && absStrength == null

        /** 塞进上报体（缺失维不发字段——服务端 `#[serde(flatten)]` + Option 天然接住）。 */
        fun putInto(o: JSONObject) {
            pedalMode?.let { o.put("pedal_mode", it) }
            tcMode?.let { o.put("tc_mode", it) }
            tcStrength?.let { o.put("tc_strength", it) }
            absMode?.let { o.put("abs_mode", it) }
            absStrength?.let { o.put("abs_strength", it) }
        }

        companion object {
            val MISSING = LapAssist(null, null, null, null, null)

            /**
             * native 单槽的 5 个配置码 → 枚举字符串 / `"invalid"` / null。
             *
             * 三态（2026-10-30 起，见 `native/src/lap_hook.h` 的 lap_upload_t）：
             *   - `0`  → null（缺失：根本没拿到配置，不发字段）
             *   - `-1` → `"invalid"`（无效：本圈中途变过，不可采信但**不是**缺失）
             *   - `>=1` → 枚举字符串（有效）
             *
             * ⚠️ 判据是 `code == INVALID` 而非 `code < 0` 之外的范围判断——0 与 -1
             *    必须分开，否则用户看不出"是我中途改了配置"还是"模块没读到"。
             */
            fun fromCodes(out: IntArray): LapAssist {
                val c = tools.alamobile.mod.config.ModConfig.LapAssistCodes
                // 解码函数返回 "" 表示无效码 → 转 null（null 维不发字段）
                fun str(code: Int, f: (Int) -> String): String? = when {
                    code == c.INVALID -> c.INVALID_TOKEN
                    code <= c.MISSING -> null
                    else -> f(code).takeIf { it.isNotEmpty() }
                }
                return LapAssist(
                    pedalMode = str(out[3], c::pedalName),
                    tcMode = str(out[4], c::modeName),
                    tcStrength = str(out[5], c::strengthValue),
                    absMode = str(out[6], c::modeName),
                    absStrength = str(out[7], c::strengthValue),
                )
            }

            /** 待传队列条目 → 载荷（队列里存的是紧凑键名）。 */
            fun fromQueue(o: JSONObject): LapAssist = LapAssist(
                pedalMode = o.optString("pm").takeIf { it.isNotEmpty() },
                tcMode = o.optString("tcm").takeIf { it.isNotEmpty() },
                tcStrength = o.optString("tcs").takeIf { it.isNotEmpty() },
                absMode = o.optString("absm").takeIf { it.isNotEmpty() },
                absStrength = o.optString("abss").takeIf { it.isNotEmpty() },
            )
        }

        /** 写进待传队列条目（紧凑键，省空间；空值不写）。 */
        fun putIntoQueue(o: JSONObject) {
            pedalMode?.let { o.put("pm", it) }
            tcMode?.let { o.put("tcm", it) }
            tcStrength?.let { o.put("tcs", it) }
            absMode?.let { o.put("absm", it) }
            absStrength?.let { o.put("abss", it) }
        }
    }

    /**
     * 一圈的**车辆调教**上报载荷（2026-10-26）。
     *
     * 全部是 `AlaCarSetup` 里的原始 float，**存的就是 UI 显示值本身**
     * （下压力 65.0 不是 0.65，外倾角 -3.0 度不是弧度）——实机对拍确认，
     * 服务端与模块都不做量纲换算，只在展示时格式化。
     *
     * ⚠️ `brakeBias` 只有**前**值，后值 = 100 − 前（游戏 UI 的 `60/40` 是派生量）。
     * ⚠️ 缺失用 [MISSING]（null）表达，**不是**用某个数值当哨兵——浮点值域连续，
     *    任何哨兵都可能撞上合法调教值。
     */
    data class LapSetup(
        val frontWingAero: Float,
        val rearWingAero: Float,
        val frontRideHeight: Float,
        val rearRideHeight: Float,
        val brakeBias: Float,
        val frontStiffness: Float,
        val rearStiffness: Float,
        val frontRollbar: Float,
        val rearRollbar: Float,
        val frontCamber: Float,
        val rearCamber: Float,
        val frontToe: Float,
        val rearToe: Float,
    ) {
        /** 塞进上报体（缺失时不发任何字段，服务端 `#[serde(flatten)]` 天然接住）。 */
        fun putInto(o: JSONObject) {
            o.put("setup_front_wing_aero", frontWingAero.toDouble())
            o.put("setup_rear_wing_aero", rearWingAero.toDouble())
            o.put("setup_front_ride_height", frontRideHeight.toDouble())
            o.put("setup_rear_ride_height", rearRideHeight.toDouble())
            o.put("setup_brake_bias", brakeBias.toDouble())
            o.put("setup_front_stiffness", frontStiffness.toDouble())
            o.put("setup_rear_stiffness", rearStiffness.toDouble())
            o.put("setup_front_rollbar", frontRollbar.toDouble())
            o.put("setup_rear_rollbar", rearRollbar.toDouble())
            o.put("setup_front_camber", frontCamber.toDouble())
            o.put("setup_rear_camber", rearCamber.toDouble())
            o.put("setup_front_toe", frontToe.toDouble())
            o.put("setup_rear_toe", rearToe.toDouble())
        }

        /** 写进待传队列条目（紧凑键，省空间）。 */
        fun putIntoQueue(o: JSONObject) {
            o.put("sfw", frontWingAero.toDouble())
            o.put("srw", rearWingAero.toDouble())
            o.put("sfr", frontRideHeight.toDouble())
            o.put("srr", rearRideHeight.toDouble())
            o.put("sbb", brakeBias.toDouble())
            o.put("sfs", frontStiffness.toDouble())
            o.put("srs", rearStiffness.toDouble())
            o.put("sfrb", frontRollbar.toDouble())
            o.put("srrb", rearRollbar.toDouble())
            o.put("sfc", frontCamber.toDouble())
            o.put("src", rearCamber.toDouble())
            o.put("sft", frontToe.toDouble())
            o.put("srt", rearToe.toDouble())
        }

        companion object {
            /** 缺失（该圈没读到调教 / 旧数据）→ 不上报任何 setup 字段。 */
            val MISSING: LapSetup? = null

            /**
             * native 的 13 个 float → 载荷。`hasSetup == 0` 时返回 null（缺失）。
             *
             * ⚠️ 顺序必须与 `NativeBridge.pollLapUpload` 的文档、`ala_core.c` 的
             *    `sbuf[]`、`lap_hook.c` 的 `US_*` 枚举三处一致——这是唯一的
             *    跨语言契约点，改一处必须三处同改。
             */
            fun fromNative(setupOut: FloatArray, hasSetup: Boolean): LapSetup? {
                if (!hasSetup || setupOut.size < 13) return null
                return LapSetup(
                    frontWingAero = setupOut[0],
                    rearWingAero = setupOut[1],
                    frontRideHeight = setupOut[2],
                    rearRideHeight = setupOut[3],
                    brakeBias = setupOut[4],
                    frontStiffness = setupOut[5],
                    rearStiffness = setupOut[6],
                    frontRollbar = setupOut[7],
                    rearRollbar = setupOut[8],
                    frontCamber = setupOut[9],
                    rearCamber = setupOut[10],
                    frontToe = setupOut[11],
                    rearToe = setupOut[12],
                )
            }

            /** 待传队列条目 → 载荷（13 个紧凑键全在才算有值，缺一个就整项缺失）。 */
            fun fromQueue(o: JSONObject): LapSetup? {
                val keys = arrayOf(
                    "sfw", "srw", "sfr", "srr", "sbb", "sfs", "srs",
                    "sfrb", "srrb", "sfc", "src", "sft", "srt",
                )
                if (keys.any { !o.has(it) }) return null
                fun f(k: String) = o.optDouble(k, 0.0).toFloat()
                return LapSetup(
                    frontWingAero = f("sfw"),
                    rearWingAero = f("srw"),
                    frontRideHeight = f("sfr"),
                    rearRideHeight = f("srr"),
                    brakeBias = f("sbb"),
                    frontStiffness = f("sfs"),
                    rearStiffness = f("srs"),
                    frontRollbar = f("sfrb"),
                    rearRollbar = f("srrb"),
                    frontCamber = f("sfc"),
                    rearCamber = f("src"),
                    frontToe = f("sft"),
                    rearToe = f("srt"),
                )
            }
        }
    }

    /**
     * 一圈的**车型**上报载荷（2026-10-26）。
     *
     * 只报 `carId` + `era` 两个整数，**不报展示名**——服务端存整数、由模块侧
     * [tools.alamobile.mod.util.CarCatalog] 反查渲染成「2026 梅赛德斯-AMG W17」。
     * 这样改车队译名只发模块，历史圈自动跟着变；服务端永远不需要认识车型字。
     */
    data class LapCar(val carId: Int, val era: Int) {
        fun putInto(o: JSONObject) {
            o.put("car_id", carId)
            o.put("car_era", era)
        }

        fun putIntoQueue(o: JSONObject) {
            o.put("cid", carId)
            o.put("cer", era)
        }

        companion object {
            val MISSING: LapCar? = null

            /** native 的 carId/carEra → 载荷。任一为负（= 探针未采到）即缺失。 */
            fun fromNative(carId: Int, carEra: Int): LapCar? =
                if (carId < 0 || carEra < 0) null else LapCar(carId, carEra)

            fun fromQueue(o: JSONObject): LapCar? {
                if (!o.has("cid") || !o.has("cer")) return null
                return fromNative(o.optInt("cid", -1), o.optInt("cer", -1))
            }
        }
    }

    /**
     * 上传一条有效圈。返回 Toast 文案(可为 null=无提示):成功=服务端四条件文案,
     * 失败(200 以外任何情况)=TOAST_LAP_QUEUED,且必定已入本地待传队列——
     * 入队与提示严格一一对应,绝不静默丢圈。阻塞 IO,工作线程调用。
     *
     * [assist] / [car] / [setup] 随圈走：**入队时也要带上**——"先跑圈后登录"的
     * 补传路径若不存这些，那一圈会白白变成"缺失"（0 分加成 / 弹窗全是缺失），
     * 正是用户最在意的丢分场景。
     */
    fun uploadLap(
        gpIndex: Int,
        lapMs: Int,
        assist: LapAssist = LapAssist.MISSING,
        car: LapCar? = LapCar.MISSING,
        setup: LapSetup? = LapSetup.MISSING,
    ): String? {
        lastUploadAt = System.currentTimeMillis()
        val token = authToken
        if (token == null) {
            enqueue(gpIndex, lapMs, assist, car, setup)
            Logger.log(
                Log.WARN, TAG,
                "uploadLap: no token, queued locally (gp=$gpIndex, ${pendingCount()} pending)"
            )
            return TOAST_LAP_QUEUED
        }
        return try {
            val body = JSONObject()
                .put("gp_index", gpIndex)
                .put("version_code", versionCode)
                .put("lap_ms", lapMs)
            assist.putInto(body)
            car?.putInto(body)
            setup?.putInto(body)
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
                else -> {
                    // 用户定案(2026-09-10):**只要有效圈上传失败就进本地队列**,绝不丢成绩。
                    // 含服务端 4xx——也可能是临时性误判(赛道映射未就绪/网关改写请求),
                    // 宁可先存后判;是否继续重试交给 [drainQueue] 的 [isRetryableStatus]
                    // (永久 4xx 会在下一轮补传中被丢弃,不会长期占队头挡后面的好圈)。
                    // 入队与 TOAST_LAP_QUEUED 严格一一对应——提示「已保存在本地」永不说假话。
                    enqueue(gpIndex, lapMs, assist, car, setup)
                    if (isRetryableStatus(code)) {
                        Logger.log(Log.WARN, TAG, "upload failed (retryable $code), queued: ${errText(code, resp)}")
                    } else {
                        Logger.log(Log.WARN, TAG, "upload rejected ($code), queued for one retry: ${errText(code, resp)}")
                    }
                    TOAST_LAP_QUEUED
                }
            }
        } catch (e: IOException) {
            enqueue(gpIndex, lapMs, assist, car, setup)
            TOAST_LAP_QUEUED
        } catch (e: Throwable) {
            // 兜底:非 IO 的意外异常(JSON 解析崩等)同样入队,绝不静默丢圈。
            // 这是「偶尔没见补传、成绩丢了」的另一处漏点——改前此处既不入队也不提示,
            // 圈直接蒸发(唯一的痕迹只有一行 WARN 日志)。
            enqueue(gpIndex, lapMs, assist, car, setup)
            Logger.log(Log.WARN, TAG, "uploadLap failed (unexpected), queued: ${e.message}")
            TOAST_LAP_QUEUED
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

    /**
     * 入队（去重：同 gp+lapMs+当日 不重复入队）。满了丢最旧。
     *
     * [assist] / [car] / [setup] 一并存进条目——补传时要用它们重建上报体。
     * **漏存 = 补传的圈丢配置/车型/调教**（用户最在意的"跑了圈没加成分"场景），
     * 故各载荷类的队列键名由它自己管。
     */
    @Synchronized
    private fun enqueue(
        gpIndex: Int,
        lapMs: Int,
        assist: LapAssist = LapAssist.MISSING,
        car: LapCar? = LapCar.MISSING,
        setup: LapSetup? = LapSetup.MISSING,
    ) {
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
            assist.putIntoQueue(item)
            car?.putIntoQueue(item)
            setup?.putIntoQueue(item)
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

    /**
     * 上传失败是否值得重试(决定队列条目保留还是丢弃)。**单一判据**:[uploadLap]
     * 与 [drainQueue] 共用——两处判据分裂曾是"5xx 在直传侧当明确拒绝丢圈、在补传
     * 侧当可重试"的根因。
     * - 401:登录态失效(token 过期/被清),重新登录后可补传
     * - 408/429:请求超时 / 网关限流(瞬时,重传通常即成功)
     * - 5xx:服务端故障(瞬时)
     * - -1:[drainQueue] 内部表示"未拿到响应"(网络异常/异常兜底)
     */
    private fun isRetryableStatus(code: Int): Boolean =
        code == -1 || code == 401 || code == 408 || code == 429 || code >= 500

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
                // 队列里存的辅助配置 / 车型 / 调教一并回传
                // （旧队列条目无这些键 → 全缺失，行为同旧版）
                LapAssist.fromQueue(it).putInto(body)
                LapCar.fromQueue(it)?.putInto(body)
                LapSetup.fromQueue(it)?.putInto(body)
                postJson("$serverBase/v1/laps", body, token).first
            } catch (e: Throwable) {
                -1  // 网络异常：保留待下次补传
            }
            // 可重试类：登录态问题（401）/网络（-1）/网关限流（408/429）/服务端故障（5xx）
            val kept = isRetryableStatus(code)
            // 其余 4xx：服务端明确拒绝，丢弃（不重试）
            if (kept) {
                remaining.put(it)   // 保留待下次补传
            } else if (code in 200..299) {
                ok++                // 真正上传成功（callsite 用返回值判"队列有进展"）
            }
            // 非重试的 4xx：明确拒绝，丢弃且不计入成功（避免日志谎报 drained）
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

    /**
     * 游戏进程侧的**静默周期补传**入口(PaddockUploader 每 30s 调一次,2026-09-10 用户定案)。
     * 本地无待传 或 无 token → 直接返回(无操作、无日志噪音);有则补传一批。
     *
     * **静默**:补传失败不弹任何 Toast——周期任务反复弹提示是打扰,且失败原因(断网)
     * 下一轮必然重试,用户无需感知;仅落日志备查。与 [uploadLap] 的入队提示分工明确:
     * 失败**当下**告知一次(用户刚跑完圈,需要知道成绩没丢),之后**静默**重试。
     *
     * 阻塞 IO(网络 + 读队列文件),必须工作线程调用(PaddockUploader 丢 io 单线程池)。
     */
    fun drainPendingQueueSilently() {
        try {
            val token = authToken ?: return
            val n = pendingCount()
            if (n == 0) return
            val ok = drainQueue(token, 20)
            Logger.log(Log.INFO, TAG, "periodic drain: $ok/$n pending laps uploaded")
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "periodic drain failed (silent): ${e.message}")
        }
    }

    // ── HTTP ────────────────────────────────────────────────

    /**
     * 榜单/圈速记录**每页条数**（2026-10-18 定案）。
     *
     * 为什么必须分页：榜单随车手数线性增长，一次性下发全表会让服务端
     * `fetch_all` 把整表搬进内存、移动网络下响应体也随人数膨胀。
     * 客户端滚到底部再拉下一页，服务端单页上限 100（`MAX_PAGE_LIMIT`）。
     *
     * ⚠️ 与服务端 `leaderboard::DEFAULT_PAGE_LIMIT` 同值——改必须两边同改。
     */
    const val PAGE_SIZE = 30

    /** 榜单条目（积分榜/赛道榜共用解析子集） */
    data class PointsEntry(
        /** 服务端 user_id（车手主页跳转键） */
        val userId: String = "",
        /** 车手号（"N 号车手"展示用） */
        val regSeq: Long = 0,
        val username: String,
        val points: Int,
        val avatarUrl: String?,
        /** 该用户在该榜维度内有成绩的赛道数（金标分母；本文具不展示，留给将来"x/y"提示） */
        val bestCount: Int = 0,
        /** 其中零辅助最快圈的条数（金标分子） */
        val zeroCount: Int = 0,
        /**
         * 是否显示金色流光。**服务端判定的唯一事实源**——客户端不重算，
         * 否则公式改一处两边就不一致（服务端已按"版本榜 2×零辅助≥总数、
         * 总榜另需至少 1 条零辅助"判好）。
         */
        val gold: Boolean = false,
    )

    /** 积分榜一页：`total` = 该榜总条数（判断是否还有下一页）。 */
    data class PointsPage(
        val entries: List<PointsEntry> = emptyList(),
        val total: Int = 0,
        /** 请求是否成功（失败时 entries 为空但 total=0，调用方据此不覆盖已有数据） */
        val ok: Boolean = false,
    )

    data class TrackEntry(
        /** 服务端 user_id（车手主页跳转键） */
        val userId: String = "",
        /** 车手号（"N 号车手"展示用） */
        val regSeq: Long = 0,
        val rank: Int,
        val username: String,
        val lapDisplay: String,
        val avatarUrl: String?,
        /** 该车手的个人最快圈是否零辅助（TC 与 ABS 均关）→ 整行金色流光 */
        val gold: Boolean = false,
    )

    data class TrackBoard(
        val trackName: String,
        val entries: List<TrackEntry>,
        /** 该赛道榜总人数（判断是否还有下一页） */
        val total: Int = 0,
    )

    /**
     * GET /v1/leaderboard/points（version=null → 总榜）。阻塞 IO。
     *
     * @param limit 本页条数（默认 [PAGE_SIZE]；服务端上限 100）
     * @param offset 跳过条数
     */
    fun fetchPointsBoard(version: Int?, limit: Int = PAGE_SIZE, offset: Int = 0): PointsPage {
        return try {
            val q = buildString {
                append("?limit=").append(limit).append("&offset=").append(offset)
                if (version != null) append("&version=").append(version)
            }
            Logger.log(Log.INFO, TAG, "fetchPointsBoard: GET $serverBase/v1/leaderboard/points$q")
            val (code, resp) = getJson("$serverBase/v1/leaderboard/points$q")
            Logger.log(Log.INFO, TAG, "fetchPointsBoard: HTTP $code, ${resp.length} bytes")
            if (code != 200) return PointsPage()
            val j = JSONObject(resp)
            val arr = org.json.JSONArray(j.optJSONArray("entries")?.toString() ?: "[]")
            val entries = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                PointsEntry(
                    userId = e.optString("user_id"),
                    regSeq = e.optLong("reg_seq"),
                    username = e.optString("username"),
                    points = e.optInt("points"),
                    avatarUrl = e.optString("avatar_url").takeIf { it.isNotEmpty() },
                    bestCount = e.optInt("best_count"),
                    zeroCount = e.optInt("zero_count"),
                    // optBoolean 在旧服务端（无该字段）下返回 false = 无金标，天然向后兼容
                    gold = e.optBoolean("gold"),
                )
            }
            PointsPage(entries = entries, total = j.optInt("total", entries.size), ok = true)
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchPointsBoard: ${e.message}")
            PointsPage()
        }
    }

    /**
     * GET /v1/leaderboard/track/{gp}（version=null → 该赛道总榜）。阻塞 IO。
     *
     * @param limit 本页条数（默认 [PAGE_SIZE]；服务端上限 100）
     * @param offset 跳过条数
     */
    fun fetchTrackBoard(gpIndex: Int, version: Int?, limit: Int = PAGE_SIZE, offset: Int = 0): TrackBoard? {
        return try {
            val q = buildString {
                append("?limit=").append(limit).append("&offset=").append(offset)
                if (version != null) append("&version=").append(version)
            }
            Logger.log(Log.INFO, TAG, "fetchTrackBoard: GET track/$gpIndex$q")
            val (code, resp) = getJson("$serverBase/v1/leaderboard/track/$gpIndex$q")
            Logger.log(Log.INFO, TAG, "fetchTrackBoard: HTTP $code, ${resp.length} bytes")
            if (code != 200) return null
            val j = JSONObject(resp)
            val arr = org.json.JSONArray(j.optJSONArray("entries")?.toString() ?: "[]")
            val entries = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                TrackEntry(
                    userId = e.optString("user_id"),
                    regSeq = e.optLong("reg_seq"),
                    rank = e.optInt("rank"),
                    username = e.optString("username"),
                    lapDisplay = e.optString("lap_display"),
                    avatarUrl = e.optString("avatar_url").takeIf { it.isNotEmpty() },
                    gold = e.optBoolean("gold"),
                )
            }
            TrackBoard(
                trackName = j.optString("track_name", "赛道"),
                entries = entries,
                total = j.optInt("total", entries.size),
            )
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchTrackBoard: ${e.message}")
            null
        }
    }

    // ── 车手主页（2026-10-18）────────────────────────────────

    /** 车手主页的圈速记录条目（= `laps` 全量留档的一行）。 */
    data class DriverLap(
        val lapId: String,
        val gpIndex: Int,
        val trackName: String,
        val versionCode: Int,
        val versionName: String,
        val lapDisplay: String,
        /** 该圈是否是该车手在该赛道该版本的个人最佳 */
        val personalBest: Boolean,
        /**
         * 该圈是否被本人「屏蔽缺失配置记录」移出了榜单（2026-10-20）。
         * 与 [personalBest] 互斥——屏蔽即从 best_laps 删行，不可能同时是最佳。
         */
        val hidden: Boolean = false,
        /** 辅助配置三维展示（踏板 / TC / ABS），缺失维 = "缺失" */
        val pedal: String,
        val tc: String,
        val abs: String,
        /** 零辅助（TC 与 ABS 均关） */
        val zeroAssist: Boolean,
        /** 上传时间（服务端格式化为北京时间 `YYYY-MM-DD HH:MM`） */
        val created: String,
        /**
         * 本圈车型（2026-10-26）。null = 缺失（旧记录 / 该圈未采到）。
         * 服务端只存 `carId`+`era` 整数，展示名由 [CarCatalog] 反查——
         * 改车队译名只发模块，历史圈自动跟着变。
         */
        val carId: Int? = null,
        val carEra: Int? = null,
        /**
         * 本圈调教（2026-10-26）。null = 整项缺失（该圈没读到 / 旧记录）。
         *
         * ⚠️ 只有**全 13 个字段都在**才算有值——服务端可能因为旧客户端只传了
         * 部分字段，半套调教比"缺失"更容易误导（看起来有数据但其实不全）。
         */
        val setup: LapSetup? = null,
    )

    /**
     * 车手资料卡（与围场主页信息卡同口径）。
     *
     * 2026-10-19 扩展：新增版本维度四值（版本积分/版本排名/纪录数/纪录排名）。
     * "最新版本"由服务端从数据里现取（`max(version_code)`），模块不硬编码——
     * 游戏升版后新圈入库即自动切换。
     */
    data class DriverProfile(
        val userId: String,
        val username: String,
        val regSeq: Long,
        val avatarUrl: String?,
        val totalPoints: Long,
        /** 总积分在总榜里的**行号**（与榜单展示顺序逐行对齐；null = 无成绩） */
        val totalRank: Long? = null,
        val bestCount: Int,
        val zeroCount: Int,
        val gold: Boolean,
        /** 最新版本口径的积分与名次（版本 = 独立赛季） */
        val versionPoints: Long = 0,
        val versionRank: Long? = null,
        val versionCode: Int? = null,
        val versionName: String = "",
        /** 该用户在最新版本下持有的赛道纪录数（版本 × 赛道，不跨版本混算） */
        val recordCount: Long = 0,
        val recordRank: Long? = null,
    )

    data class DriverPage(
        val profile: DriverProfile? = null,
        val entries: List<DriverLap> = emptyList(),
        /** 满足筛选条件的总条数（判断是否还有下一页） */
        val total: Int = 0,
        val ok: Boolean = false,
        /** 失败文案（404 = 车手不存在）；ok=true 时为空 */
        val error: String = "",
    )

    /**
     * `/v1/driver` 单条 entry → 调教载荷。
     *
     * ⚠️ **结构是嵌套对象**：服务端返回 `"setup": { "front_wing_aero": 65.0, ... }`
     * （键名**不带** `setup_` 前缀），整组缺失时是 `"setup": null`。
     * 早期实现错在顶层找 `setup_front_wing_aero`——`has()` 恒假 ⇒ 永远返回 null
     * ⇒ 弹窗 13 项全显示「缺失」（2026-10-26 实机报障的根因）。
     *
     * **13 个键必须全部存在且非 null** 才返回非 null：半套调教比"缺失"更误导
     * （看起来有数据，实际是旧客户端只传了几个字段）。
     */
    private fun parseSetup(e: JSONObject): LapSetup? {
        val o = e.optJSONObject("setup") ?: return null
        val keys = arrayOf(
            "front_wing_aero", "rear_wing_aero",
            "front_ride_height", "rear_ride_height",
            "brake_bias",
            "front_stiffness", "rear_stiffness",
            "front_rollbar", "rear_rollbar",
            "front_camber", "rear_camber",
            "front_toe", "rear_toe",
        )
        if (keys.any { !o.has(it) || o.isNull(it) }) return null
        fun f(k: String) = o.optDouble(k, 0.0).toFloat()
        return LapSetup(
            frontWingAero = f("front_wing_aero"),
            rearWingAero = f("rear_wing_aero"),
            frontRideHeight = f("front_ride_height"),
            rearRideHeight = f("rear_ride_height"),
            brakeBias = f("brake_bias"),
            frontStiffness = f("front_stiffness"),
            rearStiffness = f("rear_stiffness"),
            frontRollbar = f("front_rollbar"),
            rearRollbar = f("rear_rollbar"),
            frontCamber = f("front_camber"),
            rearCamber = f("rear_camber"),
            frontToe = f("front_toe"),
            rearToe = f("rear_toe"),
        )
    }

    /**
     * GET /v1/driver/{user_id} —— 车手主页（公开端点，无需 token）。阻塞 IO。
     *
     * @param gpIndex 0..15；null = 全部赛道
     * @param version 游戏 versionCode；null = 全部版本
     * @param zeroAssistOnly 只显示零辅助圈
     * @param bestOnly 只显示个人最佳圈
     */
    fun fetchDriverPage(
        userId: String,
        gpIndex: Int? = null,
        version: Int? = null,
        zeroAssistOnly: Boolean = false,
        bestOnly: Boolean = false,
        sort: String? = null,
        limit: Int = PAGE_SIZE,
        offset: Int = 0,
    ): DriverPage {
        return try {
            val q = buildString {
                append("?limit=").append(limit).append("&offset=").append(offset)
                if (gpIndex != null) append("&gp_index=").append(gpIndex)
                if (version != null) append("&version=").append(version)
                if (zeroAssistOnly) append("&assist=zero")
                if (bestOnly) append("&best=1")
                // 缺省不传 = 服务端默认 time_desc，与改动前行为逐字一致。
                if (!sort.isNullOrEmpty()) append("&sort=").append(sort)
            }
            val url = "$serverBase/v1/driver/$userId$q"
            Logger.log(Log.INFO, TAG, "fetchDriverPage: GET $url")
            val (code, resp) = getJson(url)
            Logger.log(Log.INFO, TAG, "fetchDriverPage: HTTP $code, ${resp.length} bytes")
            if (code == 404) return DriverPage(error = "车手不存在")
            if (code != 200) return DriverPage(error = errText(code, resp))
            val j = JSONObject(resp)
            val p = j.optJSONObject("profile")
            val profile = p?.let {
                // `optLong` 在字段缺失/为 JSON null 时返回 0——而排名 0 在语义上
                // 是"不存在的名次"。必须显式判 null，否则无成绩的车手会显示"第 0 名"。
                fun nullableLong(key: String): Long? =
                    if (it.isNull(key)) null else it.optLong(key)
                DriverProfile(
                    userId = it.optString("user_id"),
                    username = it.optString("username"),
                    regSeq = it.optLong("reg_seq"),
                    avatarUrl = it.optString("avatar_url").takeIf { s -> s.isNotEmpty() },
                    totalPoints = it.optLong("total_points"),
                    totalRank = nullableLong("total_rank"),
                    bestCount = it.optInt("best_count"),
                    zeroCount = it.optInt("zero_count"),
                    gold = it.optBoolean("gold"),
                    versionPoints = it.optLong("version_points"),
                    versionRank = nullableLong("version_rank"),
                    versionCode = if (it.isNull("version_code")) null else it.optInt("version_code"),
                    versionName = it.optString("version_name"),
                    recordCount = it.optLong("record_count"),
                    recordRank = nullableLong("record_rank"),
                )
            }
            val arr = j.optJSONArray("entries") ?: org.json.JSONArray()
            val entries = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                DriverLap(
                    lapId = e.optString("lap_id"),
                    gpIndex = e.optInt("gp_index"),
                    trackName = e.optString("track_name"),
                    versionCode = e.optInt("version_code"),
                    versionName = e.optString("version_name"),
                    lapDisplay = e.optString("lap_display"),
                    personalBest = e.optBoolean("personal_best"),
                    hidden = e.optBoolean("hidden"),
                    pedal = e.optString("pedal"),
                    tc = e.optString("tc"),
                    abs = e.optString("abs"),
                    zeroAssist = e.optBoolean("zero_assist"),
                    created = e.optString("created"),
                    // 车型：服务端对缺失返回 JSON null（不是 0）——必须显式判，
                    // 否则 carId=0（合法值，2023 梅赛德斯）会被当成"缺失"。
                    carId = if (e.isNull("car_id")) null else e.optInt("car_id"),
                    carEra = if (e.isNull("car_era")) null else e.optInt("car_era"),
                    // 调教：13 个键**全部存在**才算有值（半套调教比缺失更误导）。
                    setup = parseSetup(e),
                )
            }
            DriverPage(
                profile = profile,
                entries = entries,
                total = j.optInt("total", entries.size),
                ok = true,
            )
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchDriverPage: ${e.message}")
            DriverPage(error = "网络错误: ${e.message}")
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

    // ── 账号设置（2026-10-20）────────────────────────────────

    /**
     * 账号设置页状态（GET /v1/me/account，Bearer）。阻塞 IO。
     *
     * `ok=false` 时其余字段无意义，调用方按 [AccountState.error] 提示。
     */
    data class AccountState(
        val ok: Boolean = false,
        /** 距下次可换头像的剩余秒数（0 = 现在就能换） */
        val avatarCooldownSecs: Long = 0,
        /** 距下次可改用户名的剩余秒数 */
        val usernameCooldownSecs: Long = 0,
        /** 本人是否已执行过屏蔽（true → 直接弹「已屏蔽过」提示，不发请求） */
        val hiddenMissing: Boolean = false,
        /** 当前是否**存在**可屏蔽的缺失配置记录 */
        val hasHiddenCandidates: Boolean = false,
        val error: String = "",
    )

    fun fetchAccountState(): AccountState {
        val token = authToken ?: return AccountState(error = "未登录")
        return try {
            val (code, resp) = getJson("$serverBase/v1/me/account", token)
            when {
                code == 200 -> {
                    val j = JSONObject(resp)
                    AccountState(
                        ok = true,
                        avatarCooldownSecs = j.optLong("avatar_cooldown_secs"),
                        usernameCooldownSecs = j.optLong("username_cooldown_secs"),
                        hiddenMissing = j.optBoolean("hidden_missing"),
                        hasHiddenCandidates = j.optBoolean("has_hidden_candidates"),
                    )
                }
                code == 401 -> AccountState(error = "登录已过期，请重新登录")
                else -> AccountState(error = errText(code, resp))
            }
        } catch (e: Throwable) {
            Logger.log(Log.WARN, TAG, "fetchAccountState failed: ${e.message}")
            AccountState(error = "网络错误: ${e.message}")
        }
    }

    /**
     * 改用户名（PUT /v1/me/username，Bearer）。成功返回 null，失败返回错误文案。
     *
     * ⚠️ 用户名是**登录名**：成功后本机存的是 token 不是用户名，故无需改本地凭据，
     *    但用户下次手动登录要用新名字（服务端已把旧名字释放）。
     */
    fun changeUsername(newName: String): String? {
        val token = authToken ?: return "未登录"
        return try {
            val body = JSONObject().put("username", newName)
            val (code, resp) = putJson("$serverBase/v1/me/username", body, token)
            if (code == 200) null else errText(code, resp)
        } catch (e: Throwable) {
            "网络错误: ${e.message}"
        }
    }

    /**
     * 屏蔽缺失配置的记录（POST /v1/me/hide-missing，Bearer）。
     * 成功返回 null，失败返回错误文案。**不可撤销**，调用方必须先确认。
     */
    fun hideMissingRecords(): String? {
        val token = authToken ?: return "未登录"
        return try {
            val (code, resp) = postJson("$serverBase/v1/me/hide-missing", JSONObject(), token)
            if (code == 200) null else errText(code, resp)
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

    /**
     * 极简 HTTPS PUT（与 [postJson] 同构，只差 requestMethod）。
     *
     * 为什么不复用 postJson 加参数：两者语义不同（POST = 创建、PUT = 覆盖），
     * 混成一个带 `method` 参数的方法会让调用点看不出语义；复制这 20 行
     * 比引入一个"看参数才知道是什么请求"的入口更清楚。
     */
    private fun putJson(url: String, body: JSONObject, token: String?): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
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