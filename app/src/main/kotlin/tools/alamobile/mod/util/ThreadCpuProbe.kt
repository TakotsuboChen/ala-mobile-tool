package tools.alamobile.mod.util

import android.os.Handler
import android.os.HandlerThread
import java.io.File

/**
 * 游戏进程线程级 CPU 采样探针（2026-09-09 卡顿排查）。
 *
 * 背景：反馈「开踏板卡顿」的用户 99% 是没电脑的小白，永远不可能要求用户跑
 * adb（top -H 等）——诊断数据必须经用户已有的通道（导出日志）自动带出。
 * 本探针在游戏进程内定时读 /proc/self/task 各线程的 stat 文件（utime+stime），
 * 算出采样窗口内各线程的真实 CPU 占用（占多核总量的百分比），写成单行
 * JSON 风格日志进 ala_tool.log，随「导出并分享日志」自然到达。
 *
 * 归因用法：对比 main（Java/UI 主线程，踏板触摸管线所在）与 UnityMain
 * （游戏渲染主循环）的占用——踏板开启时 main 异常偏高 = Java 侧问题；
 * UnityGfxDeviceW 偏高 = GPU 合成问题；两者都不高而用户仍感卡顿 =
 * 问题在别处（如设备热降频）。rss= 进程物理内存，逐窗口爬升 = 泄漏
 * 伴生（2026-09-09 二轮：用户设备主线程占用单调增长 4.8→38.3%，需
 * 内存曲线判别"泄漏伴生"还是"游戏自身负载随赛道阶段上升"）。
 *
 * 成本控制：
 * - 采样周期 10s，单次读 ~130 个线程的 stat 文件 ≈ 数百次小文件读，
 *   全部在专用后台线程执行，与游戏主线程/渲染线程零竞争。
 * - 只保留占用 ≥1% 的线程行，噪音线程不写日志。
 * - 常驻开销 < 0.1% 单核（10s 一次，每次 <10ms）。
 *
 * ⚠️ 仅限游戏进程调用（Logger.init 之后）；模块进程无此需求不启动。
 */
object ThreadCpuProbe {

    private const val TAG = "AlaMobileTool"
    private const val INTERVAL_MS = 10_000L
    private const val MIN_PERCENT = 1  // 低于 1% 的线程不记

    private var handler: Handler? = null
    @Volatile private var running = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                sample()
            } catch (_: Throwable) {
                // 探针失败不影响任何功能，静默跳过本轮
            }
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 启动周期采样。幂等；重复调用只起一次。 */
    fun start() {
        if (running) return
        running = true
        val thread = HandlerThread("ala-cpu-probe")
        thread.start()
        handler = Handler(thread.looper)
        handler?.post(sampleRunnable)
        Logger.i(TAG, "ThreadCpuProbe started (interval=${INTERVAL_MS / 1000}s)")
    }

    /** 首轮采样（启动即出一条基线数据，不等 10s）。 */
    private fun sample() {
        val clockTicks = readClockTicks()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val periodMs = INTERVAL_MS

        // 进程内存快照：statm 字段2 = resident（当前物理页），
        // 字段1 = total（VSZ，含映射）。页大小 4KB。
        // 卡顿伴生泄漏 / native 层增长可由此曲线（resident 随时间爬升）定案。
        var rssMb = -1f
        try {
            val st = File("/proc/self/statm").readText().split(' ')
            if (st.size > 1) rssMb = (st[1].toLong() * 4096L / 1024f / 1024f)
        } catch (_: Throwable) {
        }

        // 读全部线程 utime+stime：/proc/self/task/<tid>/stat 第 14/15 字段。
        // comm 可能含空格/括号，取最后一个 ')' 之后的字段才安全。
        val taskDir = File("/proc/self/task")
        val threads = taskDir.listFiles() ?: return
        val sb = StringBuilder("CPUprobe cores=$cores")
        var totalTicks = 0L
        val entries = ArrayList<Pair<String, Long>>(threads.size)
        for (t in threads) {
            try {
                val stat = File(t, "stat").readText()
                val closeParen = stat.lastIndexOf(')')
                if (closeParen < 0) continue
                val fields = stat.substring(closeParen + 2).split(' ')
                // fields[11] = utime(14th), fields[12] = stime(15th)（0-based 于 ')' 后）
                if (fields.size < 13) continue
                val utime = fields[11].toLongOrNull() ?: continue
                val stime = fields[12].toLongOrNull() ?: continue
                val name = stat.substring(stat.indexOf('(') + 1, closeParen)
                val ticks = utime + stime
                if (ticks > 0) {
                    entries.add(name to ticks)
                    totalTicks += ticks
                }
            } catch (_: Throwable) {
                // 线程已退出，跳过
            }
        }

        // 与上轮快照做差分 → 窗口内真实占用。ticks/ms → 百分比：
        // percent = Δticks / (clk_tck * Δs) / cores * 100
        val prev = lastSnapshot
        lastSnapshot = entries.toMap()
        if (prev == null) return  // 首轮只有基线累计值，无数分差

        val dtTicks = (periodMs / 1000.0) * clockTicks * cores
        if (dtTicks <= 0) return
        val sorted = entries.mapNotNull { (name, ticks) ->
            val delta = ticks - (prev[name] ?: 0L)
            if (delta <= 0) return@mapNotNull null
            val percent = (delta / dtTicks) * 100.0
            if (percent < MIN_PERCENT) null else name to percent
        }.sortedByDescending { it.second }

        if (rssMb > 0) sb.append(" rss=").append("%.0f".format(rssMb)).append("MB")
        for ((name, percent) in sorted) {
            sb.append(' ').append(name).append('=').append("%.1f".format(percent))
        }
        if (sorted.isEmpty()) sb.append(" (idle)")
        Logger.i(TAG, sb.toString())
    }

    private var lastSnapshot: Map<String, Long>? = null

    /** CLK_TCK：Android arm64 恒为 100，但运行时读 sysconf 兜底防变更。 */
    private fun readClockTicks(): Double {
        return try {
            // android.system.Os.sysconf("SC_CLK_TCK") API 21+；反射避免 lint 报新 API 引用。
            val osClass = Class.forName("android.system.Os")
            val method = osClass.getMethod("sysconf", String::class.java)
            (method.invoke(null, "SC_CLK_TCK") as? Int)?.toDouble() ?: 100.0
        } catch (_: Throwable) {
            100.0
        }
    }
}
