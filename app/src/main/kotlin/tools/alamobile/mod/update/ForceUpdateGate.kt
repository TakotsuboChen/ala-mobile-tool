package tools.alamobile.mod.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tools.alamobile.mod.AlaMobileModule
import tools.alamobile.mod.util.isSupportedVersion
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动门控（2026-09-09 扩展）：两种失配任一命中时，游戏进程内所有 Java/Native
 * Hook 强制不安装 + 循环 Toast 提醒用户更新：
 * 1. **游戏版本不匹配**（同步本地判定）：目标游戏不是 8.0.6 (200150)。
 *    native hook 的 IL2CPP 偏移按 8.0.6 硬编码，错版本上安装 = 开屏闪退
 *   （1.0.3 开 8.0.4 / 1.0.2 开 8.0.6 实测），因此此判定**无 fail-open**——
 *    不匹配即终局，永不放行任何 hook。
 * 2. **模块不是最新版**（缓存秒判 + 后台网络检查）：模块 versionCode 落后
 *    最新 Release。沿用原有判定链，离线/检查失败 fail-open 不激活
 *   （宁可放过也不误伤离线的最新版用户）。
 *
 * ⚠️ 判定时机红线（用户定案 2026-09-09）：门控判定必须**先于任何 hook 安装**，
 * 不允许任何 hook「先生效零点几秒再停」。**确认游戏/模块版本之前完全零 Hook**：
 * [isVerdictDone] 只在「游戏版本已确认 + 模块判定已出结果」后置位——游戏版本
 * 判定需要 Context（读 PackageManager），context 为 null 时版本未确认，hook
 * 安装路径一律等待（调用方主线程 200ms 轮询重试，**绝不阻塞主线程**，阻塞会
 * ANR）。宁可不装 hook，也不在版本未确认时抢跑。
 *
 * Toast 循环：主线程 Handler 每 2.5s 弹一次 LENGTH_SHORT（约 2s），视觉上连续
 * 不断。文案按当前命中组合动态取：双失配 / 仅游戏版本 / 仅模块过期 三种。
 * 激活幂等（AtomicBoolean compareAndSet），重复调用 evaluate 不会重复起循环。
 *
 * ⚠️ 仅限游戏进程调用 [evaluate]/激活路径（日志走 AlaMobileModule.logX）；
 * 模块进程只准调用 [recordLatestVersionCode]（libxposed-api 是 compileOnly，
 * 模块进程引 Xposed 类即 NoClassDefFoundError）。
 */
object ForceUpdateGate {

    private const val TAG = "ForceUpdateGate"

    private const val TOAST_TEXT_MODULE =
        "模块已更新，该版本功能失效，请到模块 App、GitHub 或 QQ 群下载更新！"
    private const val TOAST_TEXT_GAME =
        "游戏版本不匹配，功能失效，请到 QQ 群下载最新版本游戏！"
    private const val TOAST_TEXT_BOTH =
        "游戏版本不匹配且模块已更新，功能失效，请到 QQ 群更新游戏和模块！"
    private const val TOAST_INTERVAL_MS = 2500L

    /** scope 内的两个游戏包名（与 VersionGate.SUPPORTED_PACKAGES 一致）。 */
    private const val GAME_PKG_OFFICIAL = "com.Vince.AlamobileFormula"
    private const val GAME_PKG_COEX = "com.Takotsubo.AlamobileFormula"

    /** 游戏版本不匹配（同步本地判定，终局无 fail-open）。 */
    private val gameMismatch = AtomicBoolean(false)

    /** 游戏版本已确认匹配（≠ 不匹配）：verdict 放行的前置条件之一。 */
    private val gameVersionVerified = AtomicBoolean(false)

    /** 模块落后最新 Release（缓存秒判或后台网络检查命中）。 */
    private val moduleOutdated = AtomicBoolean(false)

    private val toastLoopStarted = AtomicBoolean(false)
    private val bgCheckStarted = AtomicBoolean(false)

    /**
     * 判定完成信号：游戏版本已确认**且**模块判定已出结果（激活或确认无需激活、
     * 或后台检查超时/失败 fail-open 放行）后置位。hook 安装路径在 isActive==false
     * 时轮询此标志，杜绝「版本未确认/后台检查未出结果就装 hook」的抢跑窗口。
     */
    private val verdictDone = AtomicBoolean(false)

    /** 门控是否已激活（任一失配命中；激活后游戏进程所有 hook 安装路径必须跳过）。 */
    val isActive: Boolean get() = gameMismatch.get() || moduleOutdated.get()

    /**
     * 判定是否已完成（游戏版本确认 + 模块判定出结果，或 fail-open 放行）。
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
     * 游戏进程启动时评估门控。context 为 null 时直接返回（版本未确认 → verdict
     * 不放行，hook 路径继续等待），由调用方拿到 context 后重推。
     * 幂等：重复调用只起一次后台检查、只激活一次。
     */
    fun evaluate(context: Context?) {
        if (context == null) return

        // ⓪ scope 里有非游戏包（GMS/WebView 等进程也被注入）。门控只对两个
        //    游戏包名生效：其他进程 isSupportedVersion 必然 false，若放行会误判
        //    "游戏版本不匹配" 并在 GMS 进程里起 Toast 循环（游戏没开也弹 Toast）。
        //    非游戏进程本来就没有游戏 hook 可装，verdict 直接放行即可。
        val pkg = context.packageName
        if (pkg != GAME_PKG_OFFICIAL && pkg != GAME_PKG_COEX) {
            gameVersionVerified.set(true)
            verdictDone.set(true)
            return
        }

        // ① 游戏版本门控：同步本地判定，一次定案。不匹配 → 终局激活（零 hook +
        //    toast），无需网络判定，verdict 立即放行（放行的意义只是让 hook 路径
        //    走到 isActive 检查然后 return，不会装任何 hook）。
        if (!gameVersionVerified.get() && !gameMismatch.get()) {
            if (isSupportedVersion(context)) {
                gameVersionVerified.set(true)
                AlaMobileModule.logX(
                    android.util.Log.INFO, TAG,
                    "game version verified ok (8.0.6/200150)"
                )
            } else {
                gameMismatch.set(true)
                AlaMobileModule.logX(
                    android.util.Log.WARN, TAG,
                    "game version MISMATCH → zero hooks (offset crash guard), toast loop started"
                )
                startToastLoop(context)
                verdictDone.set(true)
                return
            }
        }
        if (gameMismatch.get()) {
            verdictDone.set(true)
            return
        }

        // ② 模块落后门控：缓存秒判 + 后台网络检查（fail-open）。
        val current = tools.alamobile.mod.BuildConfig.VERSION_CODE
        if (!moduleOutdated.get()) {
            val cached = UpdatePreferences.getLatestKnownVersionCode(context)
            if (cached > current) {
                activateModuleOutdated(context)
                verdictDone.set(true)  // 秒判完成，放行所有守门路径
                return
            }
        }
        if (moduleOutdated.get()) {
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
                        if (latest > current && !moduleOutdated.get()) {
                            AlaMobileModule.logX(
                                android.util.Log.INFO, TAG,
                                "background check: latest=$latest > current=$current, activating"
                            )
                            activateModuleOutdated(context)
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

    /** 模块过期激活：立标记 + 起 toast 循环。幂等。 */
    private fun activateModuleOutdated(context: Context) {
        if (!moduleOutdated.compareAndSet(false, true)) return
        AlaMobileModule.logX(
            android.util.Log.WARN, TAG,
            "ACTIVATED (module outdated): all Java/Native hooks disabled"
        )
        startToastLoop(context)
    }

    /** 起 Toast 循环（单实例）：每轮按当前命中组合动态取文案——模块过期可能
     *  晚于游戏版本失判定案（后台网络检查回来才命中），文案随之升级为双失配。 */
    private fun startToastLoop(context: Context) {
        if (!toastLoopStarted.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        val toastLoop = object : Runnable {
            override fun run() {
                try {
                    Toast.makeText(context, currentToastText(), Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
                handler.postDelayed(this, TOAST_INTERVAL_MS)
            }
        }
        handler.post(toastLoop)
    }

    private fun currentToastText(): String = when {
        gameMismatch.get() && moduleOutdated.get() -> TOAST_TEXT_BOTH
        gameMismatch.get() -> TOAST_TEXT_GAME
        else -> TOAST_TEXT_MODULE
    }
}
