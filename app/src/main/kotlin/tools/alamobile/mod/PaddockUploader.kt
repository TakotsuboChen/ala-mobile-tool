package tools.alamobile.mod

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 围场上传轮询器：1Hz 轮询 native 单槽（pollLapUpload），取走有效圈事件后在
 * 工作线程发 HTTPS。Toast 需在主线程弹，结果经主线程 Handler 回投。
 *
 * 为什么轮询而不是 JNI 回调：与 intro/TcAbs 指示灯信号链同模式（项目已验证
 * 的通道）；圈完成分钟级一遇，1Hz 轮询开销可忽略；native 反向调 Java 需
 * AttachCurrentThread + methodID 缓存，复杂度不成比例。
 *
 * 单槽消费语义：native 单槽保留事件直到 markLapUploadConsumed；本层 seq
 * 单调去重防双读重发。上传失败（网络）时圈已在 PaddockClient 内入待传队列，
 * 同样确认消费（数据不丢——在队列里，不在单槽里）。
 */
object PaddockUploader {

    private const val TAG = "PaddockClient"
    private const val POLL_INTERVAL_MS = 1000L

    /**
     * 本地待传队列的静默补传间隔(用户定案 2026-09-10):有队列就每 30s 补一次,
     * 没队列无操作、失败不弹 Toast。与 1Hz 圈速轮询共用同一个主线程 Handler,
     * 但判定与网络请求全部丢 IO 线程(主线程零 IO)。
     */
    private const val DRAIN_INTERVAL_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "paddock-up") }
    private val lastSeq = AtomicInteger(0)

    @Volatile private var started = false
    @Volatile private var appCtx: Context? = null
    @Volatile private var lastDrainMs: Long = 0L

    fun start(context: Context, serverOverride: String?, versionCode: Int) {
        if (started) return
        started = true
        appCtx = context.applicationContext
        PaddockClient.init(context, serverOverride)
        PaddockClient.versionCode = versionCode
        schedulePoll()
        AlaMobileModule.logX(
            Log.INFO, TAG,
            "PaddockUploader started (1Hz poll, server=${if (serverOverride.isNullOrBlank()) "default" else "custom"})"
        )
    }

    fun stop() {
        started = false
        io.shutdownNow()
    }

    private fun schedulePoll() {
        handler.postDelayed({
            try {
                pollOnce()
            } catch (e: Throwable) {
                AlaMobileModule.logX(Log.WARN, TAG, "pollOnce: ${e.message}")
            }
            try {
                maybeDrainPending()
            } catch (e: Throwable) {
                AlaMobileModule.logX(Log.WARN, TAG, "maybeDrainPending: ${e.message}")
            }
            if (started) schedulePoll()
        }, POLL_INTERVAL_MS)
    }

    /**
     * 静默周期补传:本地有队列时每 30s 补一批(用户定案 2026-09-10)。
     *
     * 主线程只做时间判定(廉价);pendingCount 读文件 + drainQueue 走 HTTPS 全部
     * 在 IO 线程——主线程绝不做 IO(NetworkOnMainThreadException 红线)。
     * 补传成功/失败都不弹 Toast,失败仅落日志。
     */
    private fun maybeDrainPending() {
        val now = System.currentTimeMillis()
        if (now - lastDrainMs < DRAIN_INTERVAL_MS) return
        lastDrainMs = now
        io.execute {
            try {
                PaddockClient.drainPendingQueueSilently()
            } catch (e: Throwable) {
                AlaMobileModule.logX(Log.WARN, TAG, "periodic drain: ${e.message}")
            }
        }
    }

    /** 主线程：读 native 单槽 → 有新事件则丢给 IO 线程上传（Toast 回主线程弹）。 */
    private fun pollOnce() {
        if (!NativeBridge.isAvailable) return
        // token 缺失时周期性重试恢复：注册可能发生在游戏启动之后（最常见时序），
        // 或 NPatch 用户登录时 service 未绑定、daemon 里 key 后到（日志实证
        // "remote token read: null (key missing)"）。loadAuth 只读本地/remote
        // prefs，分钟级频率开销可忽略；恢复成功即恢复上传+补传队列。
        if (!PaddockClient.hasToken()) {
            PaddockClient.retryRestoreAuth()
        }
        val seq = IntArray(1)
        val gp = IntArray(1)
        val ms = IntArray(1)
        val has = try {
            NativeBridge.pollLapUpload(seq, gp, ms)
        } catch (e: Throwable) {
            AlaMobileModule.logX(Log.WARN, TAG, "pollLapUpload failed: ${e.message}")
            return
        }
        if (!has) return
        if (seq[0] <= lastSeq.get()) return  // 已处理过的 seq（双读保护）
        lastSeq.set(seq[0])
        val gpIdx = gp[0]
        val lapMs = ms[0]
        val lapSeq = seq[0]
        if (!PaddockClient.hasToken()) {
            // token 仍缺失：入本地待传队列（30 天），日志可见——此前此路径
            // 完全静默，用户"跑了圈没记录"却无从排查（两份用户日志实证）。
            AlaMobileModule.logX(
                Log.WARN, TAG,
                "lap $lapMs ms (gp=$gpIdx): no token, queued locally (${PaddockClient.pendingCount()} pending)"
            )
        }
        io.execute {
            val toast = try {
                PaddockClient.uploadLap(gpIdx, lapMs)
            } catch (e: Throwable) {
                AlaMobileModule.logX(Log.WARN, TAG, "uploadLap: ${e.message}")
                null
            } finally {
                // 无论成败都消费单槽：失败路径圈已落入本地待传队列（uploadLap 内部保证），
                // 留槽只会让下一秒的轮询重复走一遍入队去重。
                try { NativeBridge.markLapUploadConsumed(lapSeq) } catch (_: Throwable) {}
            }
            if (toast != null) {
                handler.post {
                    try {
                        val ctx = appCtx ?: return@post
                        android.widget.Toast.makeText(ctx, toast, android.widget.Toast.LENGTH_LONG).show()
                        AlaMobileModule.logX(Log.INFO, TAG, "Toast: $toast")
                    } catch (e: Throwable) {
                        AlaMobileModule.logX(Log.WARN, TAG, "toast: ${e.message}")
                    }
                }
            }
        }
    }
}