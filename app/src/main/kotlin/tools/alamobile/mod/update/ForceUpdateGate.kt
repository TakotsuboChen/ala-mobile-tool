package tools.alamobile.mod.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tools.alamobile.mod.AlaMobileModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 强制升级门控（2026-09-09）：模块版本落后于最新 Release 时，游戏进程内
 * 所有 Java/Native Hook 强制失效 + 循环 Toast 提醒用户更新。
 *
 * ⚠️ 判定时机红线（用户定案 2026-09-09）：门控判定必须**先于任何 hook 安装**，
 * 不允许任何 hook「先生效零点几秒再停」。两层保证：
 * 1. 缓存秒判同步执行于 onModuleLoaded（框架最早回调），命中则 isActive 恒 true，
 *    后续全部 hook 安装路径跳过。
 * 2. [isVerdictDone]：hook 安装路径在判定未完成时一律不执行——调用方在主线程
 *    200ms 轮询重试（**绝不阻塞主线程**，阻塞会 ANR），杜绝「网络检查在途、
 *    结果未出就装 hook」的抢跑窗口。
 *
 * 判定链：
 * 1. 缓存判定（同步秒判）：读 SharedPreferences 里记录的「已知最新 versionCode」，
 *    大于当前 versionCode 立即激活。缓存由模块 App（OverviewPager 检查更新）和
 *    游戏进程后台检查共同写入——用户只要打开过模块 App 就有缓存。
 * 2. 后台检查（兜底 + 缓存保鲜）：无缓存/缓存未命中时后台跑一次
 *    [UpdateChecker.checkLatest]，结果写缓存供下次启动秒判；确认落后则激活。
 *
 * fail-open 原则：离线/检查失败不激活——宁可放过也不误伤离线的最新版用户。
 *
 * 已知边界（fail-open 的代价）：完全离线 + 缓存为空的首次场景下，后台检查
 * 15s 超时判 fail-open 放行 hook 安装（宁可放过也不误伤）。在线用户判定 1~3s
 * 内完成；游戏 2s 早期 hook 的安装会推迟到判定完成后，无抢跑。
 *
 * Toast 循环：主线程 Handler 每 2.5s 弹一次 LENGTH_SHORT（约 2s），视觉上连续不断。
 * 激活幂等（AtomicBoolean compareAndSet），重复调用 evaluate 不会重复起循环。
 *
 * ⚠️ 仅限游戏进程调用 [evaluate]/[activate]（日志走 AlaMobileModule.logX）；
 * 模块进程只准调用 [recordLatestVersionCode]（libxposed-api 是 compileOnly，
 * 模块进程引 Xposed 类即 NoClassDefFoundError）。
 */
object ForceUpdateGate {

    private const val TAG = "ForceUpdateGate"

    private const val TOAST_TEXT =
        "模块已更新，该版本功能失效，请到模块 App、GitHub 或 QQ 群下载更新！"
    private const val TOAST_INTERVAL_MS = 2500L

    private val active = AtomicBoolean(false)
    private val bgCheckStarted = AtomicBoolean(false)

    /**
     * 判定完成信号：缓存秒判命中、后台检查返回（激活或确认无需激活）、或后台检查
     * 超时/失败（fail-open 放行）三者之一发生后置位。hook 安装路径在 isActive==false
     * 时轮询此标志，杜绝「后台检查未出结果就装 hook」的抢跑窗口。
     */
    private val verdictDone = AtomicBoolean(false)

    /** 门控是否已激活（激活后游戏进程所有 hook 安装路径必须跳过）。 */
    val isActive: Boolean get() = active.get()

    /**
     * 判定是否已完成（激活判定或 fail-open 放行）。
     * hook 安装路径的守门条件：verdictDone==false 时不装任何 hook。
     */
    val isVerdictDone: Boolean get() = verdictDone.get()

    /**
     * 记录「已知最新 versionCode」缓存。模块 App 与游戏进程检查更新成功后都调。
     * 任意调用方、任意进程（prefs 在模块包名下，两进程各自读写，值只会是最新 Release）。
     */
    fun recordLatestVersionCode(context: Context, code: Int) {
        if (code > 0) {
            UpdatePreferences.setLatestKnownVersionCode(context, code)
        }
    }

    /**
     * 游戏进程启动时评估门控：缓存命中立即激活并放行判定信号；否则起后台检查线程。
     * 幂等：重复调用只起一次后台检查、只激活一次。
     */
    fun evaluate(context: Context) {
        val current = tools.alamobile.mod.BuildConfig.VERSION_CODE
        if (!active.get()) {
            val cached = UpdatePreferences.getLatestKnownVersionCode(context)
            if (cached > current) {
                activate(context)
                verdictDone.set(true)  // 秒判完成，放行所有守门路径
                return
            }
        }
        if (active.get()) {
            verdictDone.set(true)
            return
        }
        if (!bgCheckStarted.compareAndSet(false, true)) return

        Thread {
            try {
                // runBlocking 在专用后台线程：包 15s 超时，网络检查失败/超时 fail-open 不激活。
                val result = runBlocking {
                    withTimeoutOrNull(15_000L) {
                        UpdateChecker.checkLatest(UpdatePreferences.getChannel(context))
                    }
                }
                if (result is UpdateCheckResult.HasUpdate) {
                    result.info.latestVersionCode?.let { latest ->
                        recordLatestVersionCode(context, latest)
                        if (latest > current && !active.get()) {
                            AlaMobileModule.logX(
                                android.util.Log.INFO, TAG,
                                "background check: latest=$latest > current=$current, activating"
                            )
                            activate(context)
                        }
                    }
                }
            } catch (e: Throwable) {
                AlaMobileModule.logX(
                    android.util.Log.WARN, TAG,
                    "background check failed (fail-open): ${e.message}"
                )
            } finally {
                // 无论激活与否、成功失败，判定已完成——放行所有守门路径
                verdictDone.set(true)
            }
        }.start()
    }

    /**
     * 激活门控：立标记 + 主线程 Toast 循环。幂等。
     */
    private fun activate(context: Context) {
        if (!active.compareAndSet(false, true)) return
        AlaMobileModule.logX(
            android.util.Log.WARN, TAG,
            "ACTIVATED: module outdated, all Java/Native hooks disabled, toast loop started"
        )
        val handler = Handler(Looper.getMainLooper())
        val toastLoop = object : Runnable {
            override fun run() {
                try {
                    Toast.makeText(context, TOAST_TEXT, Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
                handler.postDelayed(this, TOAST_INTERVAL_MS)
            }
        }
        handler.post(toastLoop)
    }
}
