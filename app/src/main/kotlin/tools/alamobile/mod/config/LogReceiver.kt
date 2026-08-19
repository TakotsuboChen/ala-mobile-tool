package tools.alamobile.mod.config

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块进程接收游戏进程推送的日志内容（分片广播 → 接收端拼接完整日志）。
 *
 * 广播方向：游戏进程 → 模块进程（与 ConfigReceiver 反向）。
 * 游戏进程在 15s 延迟末尾或 ConfigReceiver 收到配置变更时，
 * 把自己的 ala_tool.log / ala_tool_native.log 完整内容通过分片广播发给模块进程。
 * 模块进程收到所有分片后拼接，写到 cacheDir 供 LogExporter 导出时读取。
 *
 * 为什么分片：Binder transaction buffer 进程级共享 ~1MB，单条广播 Intent extra
 * 实际安全上限 ~500KB。日志可能 2MB+（Logger 滚动上限），必须分片才能完整传输。
 * 每片 CHUNK_SIZE（256KB），接收端按 (sessionId, type) 累积，收齐 total 片后拼接写文件。
 *
 * 投递方式：setComponent 显式组件广播 + FLAG_RECEIVER_INCLUDE_BACKGROUND，
 * 不查包可见性、不查 intent-filter，绕过 Android 11+ 包可见性限制和
 * AOSP 隐式广播跳过后台静态 receiver 逻辑（与 ConfigReceiver 同理）。
 */
class LogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PUSH_GAME_LOG = "tools.alamobile.mod.PUSH_GAME_LOG"
        const val EXTRA_LOG_TYPE = "log_type"        // "java" | "native"
        const val EXTRA_CHUNK_INDEX = "chunk_index"
        const val EXTRA_CHUNK_TOTAL = "chunk_total"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CHUNK_DATA = "chunk_data"

        private const val GAME_JAVA_LOG_FILE = "game_java.log"
        private const val GAME_NATIVE_LOG_FILE = "game_native.log"
        private const val TAG = "AlaMobileTool"

        /**
         * 每片最大字符数。
         * Binder transaction buffer 进程级共享 ~1MB，单条广播 Intent parcel
         * 经 UTF-16 编码后约为字符数 ×2 + 开销。128K 字符 → ~256KB parcel，
         * 留足余量给 Intent 本身和并发的其它 Binder 调用。
         * 之前 256K 字符 → 527KB parcel，触发 TransactionTooLargeException。
         */
        private const val CHUNK_SIZE = 128 * 1024

        /** 累积器上限——超过则清理最老的未完成 session（防内存泄漏）。 */
        private const val MAX_PENDING_SESSIONS = 20

        /**
         * 分片累积器：key = "sessionId:type"。
         *
         * onReceive 在主线程串行执行，理论不需要并发容器；
         * 用 ConcurrentHashMap 仅作防御性编程。
         */
        private data class ChunkSession(
            val type: String,
            val total: Int,
            val chunks: Array<String?>,
            var received: Int = 0,
            val createdAt: Long = System.currentTimeMillis()
        )

        private val sessions = ConcurrentHashMap<String, ChunkSession>()

        /**
         * 游戏进程调用：把完整日志分片推到模块进程。
         *
         * Java 和 native 各自独立分链发送，互不影响。
         * 每段日志按 [CHUNK_SIZE] 分片，每片一条广播，携带 (type, index, total, sessionId, data)。
         *
         * @return true 所有分片广播发送成功
         */
        fun send(context: Context, javaLog: String, nativeLog: String): Boolean {
            val sessionId = System.currentTimeMillis().toString()
            var success = true
            if (javaLog.isNotEmpty()) {
                success = sendChunked(context, "java", javaLog, sessionId) && success
            }
            if (nativeLog.isNotEmpty()) {
                success = sendChunked(context, "native", nativeLog, sessionId) && success
            }
            return success
        }

        private fun sendChunked(context: Context, type: String, log: String, sessionId: String): Boolean {
            val chunks = chunkString(log, CHUNK_SIZE)
            var success = true
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    val intent = Intent(ACTION_PUSH_GAME_LOG)
                        .setComponent(ComponentName("tools.alamobile.mod", "tools.alamobile.mod.config.LogReceiver"))
                        .putExtra(EXTRA_LOG_TYPE, type)
                        .putExtra(EXTRA_CHUNK_INDEX, index)
                        .putExtra(EXTRA_CHUNK_TOTAL, chunks.size)
                        .putExtra(EXTRA_SESSION_ID, sessionId)
                        .putExtra(EXTRA_CHUNK_DATA, chunk)
                        .addFlags(0x0020) // FLAG_RECEIVER_INCLUDE_BACKGROUND — 强制投递给后台静态 receiver
                    context.sendBroadcast(intent)
                } catch (e: Throwable) {
                    Log.w(TAG, "LogReceiver.send: chunk $index/${chunks.size} ($type) failed: ${e.message}")
                    success = false
                }
            }
            Log.i(TAG, "LogReceiver.send: $type log ${log.length} bytes → ${chunks.size} chunks (session=$sessionId)")
            return success
        }

        private fun chunkString(s: String, chunkSize: Int): List<String> {
            if (s.isEmpty()) return emptyList()
            val chunks = ArrayList<String>((s.length + chunkSize - 1) / chunkSize)
            var i = 0
            while (i < s.length) {
                val end = minOf(i + chunkSize, s.length)
                chunks.add(s.substring(i, end))
                i = end
            }
            return chunks
        }

        /** 累积器超过上限时清理最老的未完成 session（防泄漏）。 */
        private fun maybeCleanupStale() {
            if (sessions.size <= MAX_PENDING_SESSIONS) return
            val now = System.currentTimeMillis()
            val stale = sessions.entries
                .sortedBy { it.value.createdAt }
                .take(sessions.size - MAX_PENDING_SESSIONS / 2)
            for (entry in stale) {
                Log.w(TAG, "LogReceiver: cleaning stale session ${entry.key} (received ${entry.value.received}/${entry.value.total})")
                sessions.remove(entry.key)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_GAME_LOG) return

        val type = intent.getStringExtra(EXTRA_LOG_TYPE) ?: return
        val index = intent.getIntExtra(EXTRA_CHUNK_INDEX, -1)
        val total = intent.getIntExtra(EXTRA_CHUNK_TOTAL, -1)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val data = intent.getStringExtra(EXTRA_CHUNK_DATA) ?: ""

        if (index < 0 || total <= 0 || index >= total) return

        val key = "$sessionId:$type"

        // 获取或创建累积 session；total 不一致时重建（防旧残留）
        var session = sessions[key]
        if (session == null || session.total != total) {
            maybeCleanupStale()
            session = ChunkSession(type, total, arrayOfNulls(total))
            sessions[key] = session
        }

        if (session.chunks[index] == null) {
            session.chunks[index] = data
            session.received++
        }

        Log.d(TAG, "LogReceiver: chunk $index/$total ($type) session=$sessionId (received ${session.received}/${session.total})")

        // 全部到齐 → 拼接写入文件
        if (session.received >= session.total) {
            val fullLog = session.chunks.joinToString("") { it ?: "" }
            val fileName = if (type == "java") GAME_JAVA_LOG_FILE else GAME_NATIVE_LOG_FILE
            try {
                File(context.cacheDir, fileName).writeText(fullLog)
                Log.i(TAG, "LogReceiver: assembled $type log ${fullLog.length} bytes → $fileName")
            } catch (e: Throwable) {
                Log.e(TAG, "LogReceiver: write $fileName failed: ${e.message}")
            }
            sessions.remove(key)
        }
    }
}