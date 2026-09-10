package tools.alamobile.mod.update

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import tools.alamobile.mod.AlaMobileModule
import tools.alamobile.mod.PaddockClient
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
 * 不断。文案按当前命中组合动态取：双失配 / 仅游戏版本 / 仅模块过期 / 仅未登录
 * 四种（更新失配优先于登录——用户定案 2026-09-10：有更新提示就提示更新）。
 * 激活幂等（AtomicBoolean compareAndSet），重复调用 evaluate 不会重复起循环。
 *
 * 3. **未登录围场**（2026-09-10 扩展，见 [evaluateLoginGate]）：游戏进程本地
 *    三级回落读不到围场 token = 未登录 → 零 hook + 循环 Toast。**判定全程无
 *    网络**（本地文件 / daemon Remote Preferences / ConfigProvider 都是本地读）
 *   ——断网不误伤已有登录态。优先级低于前两种失配：任一更新失配激活时本判定
 *    直接跳过（hook 反正全禁，Toast 只显示更新文案）。判定失败按未登录处理
 *   （fail-closed，门控语义 = 无本地登录态不许用）。
 *   反之放行时分两态（2026-09-10，见 [announcePaddockConnection]）：fetchMe 通
 *   → 问候 Toast（欢迎 x 号车手 xxx）；连不上但本地有登录态 → 上传风险提示 Toast。
 *   两态都放行模块功能，仅提示文案不同。
 *
 * ⚠️ 仅限游戏进程调用 [evaluate]/[evaluateLoginGate]/激活路径（日志走
 * AlaMobileModule.logX）；模块进程只准调用 [recordLatestVersionCode]
 * （libxposed-api 是 compileOnly，模块进程引 Xposed 类即 NoClassDefFoundError）。
 */
object ForceUpdateGate {

    private const val TAG = "ForceUpdateGate"

    private const val TOAST_TEXT_MODULE =
        "模块已更新，该版本功能失效，请到模块 App、GitHub 或 QQ 群下载更新！"
    private const val TOAST_TEXT_GAME =
        "游戏版本不匹配，功能失效，请到 QQ 群下载最新版本游戏！"
    private const val TOAST_TEXT_BOTH =
        "游戏版本不匹配且模块已更新，功能失效，请到 QQ 群更新游戏和模块！"
    private const val TOAST_TEXT_NOT_LOGIN =
        "您尚未登录围场，模块功能将不会生效，请先返回模块 App 登录！"
    /** 已登录但服务端不可达/凭据失效:放行模块,仅提示成绩上传风险。 */
    private const val TOAST_TEXT_OFFLINE =
        "网络连接异常,成绩上传可能会失败,未上传的成绩将会保存在本地,连接成功后自动补传"
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

    /**
     * 未登录围场（2026-09-10 扩展）。激活前提：前两种更新失配都不在
     * （更新优先于登录），且三级回落读不到本地 token。判定无网络。
     */
    private val notLoggedIn = AtomicBoolean(false)

    private val toastLoopStarted = AtomicBoolean(false)
    private val bgCheckStarted = AtomicBoolean(false)
    private val loginEvalStarted = AtomicBoolean(false)

    /** 登录门控判定完成（Provider 查询出结果，无论登录与否）。 */
    private val loginVerdictDone = AtomicBoolean(true)

    /**
     * 判定完成信号：游戏版本已确认**且**模块判定已出结果（激活或确认无需激活、
     * 或后台检查超时/失败 fail-open 放行）后置位。hook 安装路径在 isActive==false
     * 时轮询此标志，杜绝「版本未确认/后台检查未出结果就装 hook」的抢跑窗口。
     */
    private val verdictDone = AtomicBoolean(false)

    /**
     * 门控是否已激活（任一失配命中；激活后游戏进程所有 hook 安装路径必须跳过）。
     * 未登录激活路径把 verdictDone 一并置位——对 hook 安装路径而言「已判定」即可，
     * 两条路径都不该装 hook。
     */
    val isActive: Boolean get() = gameMismatch.get() || moduleOutdated.get() || notLoggedIn.get()

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
            notLoggedIn.set(true)  // 登录门控同样只在游戏进程评估
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
                // 更新优先于登录：版本失配命中后登录门控退出竞争。
                notLoggedIn.set(true)
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

        // ② 模块落后门控：缓存秒判 + 后台网络检查（fail-open）。缓存未命中时
        //    丢后台线程跑网络检查；登录门控（③）由 15s 主路径经 [evaluateLoginGate]
        //    在后台检查出结果后评估——不在此处串联，避免网络在途时抢跑激活登录门控。
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
        startBackgroundCheck(context)
    }

    /**
     * 未登录围场门控公共入口（2026-09-10）：由调用方（AlaMobileModule 的
     * remoteTokenReader 注入点之后、15s 主路径之前）显式调用一次。不挂在
     * [evaluate] 内部——② 的后台网络检查在途时（verdictDone 未置位）登录门控
     * 若已激活，会跟模块过期 Toast 打架（更新优先于登录）；等检查出结果后再
     * 评估，两种失配都不在才轮到登录门控。
     *
     * 未登录门控判定：读不到本地登录态 = 未登录 → 零 hook + 循环 Toast。
     *
     * ⚠️ 判定走 [PaddockClient.queryLoginStateFromModule]（ConfigProvider →
     * 模块进程 auth 文件），**不走 hasToken()**——后者可能拿到 daemon remote
     * prefs 的残留值（实测 put/remove 都不落盘，退出登录清不掉），残留 token
     * 会造成"模块已退出登录、游戏仍放行"的分裂（2026-09-10 两轮实机实证）。
     *
     * ConfigProvider 是阻塞 IPC，判定丢后台线程跑（不阻塞主线程——ANR 红线）。
     * ⚠️ 抢跑教训（2026-09-10 12:28 实测）：查询在途时 verdictDone 未置位——
     * hook 安装路径（BillingHook 轮询 / doPackageReadyDeferred / 15s 主路径）
     * 全部依赖 verdictDone/isActive 守门，自然等到查询出结果，**无抢跑窗口**。
     * 但 early unlock 路径在 doPackageReadyDeferred 里位于 evaluateLoginGate
     * 调用**之后**、同一主线程消息内同步执行——它检查 isActive 时查询还在途
     * （notLoggedIn 未置位）→ 抢跑装上 hook（实测 Successfully hooked 出现
     * 在 ACTIVATED 之前 24ms）。修复：发起查询时**先置 verdictDone=false 并
     * 由 early unlock 路径自查 loginVerdict**——见 [isLoginVerdictDone]，
     * 未出结果时 early unlock 路径必须 return 等待重推。
     */
    fun evaluateLoginGate(context: Context?) {
        if (context == null) return
        if (gameMismatch.get() || moduleOutdated.get()) return  // 更新优先于登录
        if (notLoggedIn.get()) {
            verdictDone.set(true)
            return
        }
        // ⚠️ 短路教训（2026-09-10 12:34 实测）：此处**禁止**用 loginVerdictDone
        // 初始值 true 做 early return——那会让本函数整个变 no-op，查询永不发起、
        // 门控永不激活，而 early unlock 的 isLoginVerdictDone 检查看到初始 true
        // 直接放行（hook 抢跑）。判定"未出结果"的唯一权威是 loginEvalStarted：
        // CAS 成功 = 第一次进入 = 发起查询；CAS 失败 = 查询已在途/已完成，重入无害。
        if (loginEvalStarted.compareAndSet(false, true)) {
            // 发起查询前把两个 verdict 标志拉回 false：本函数在 ② 的 fail-open
            // 置 verdictDone 后才调用，early unlock 路径靠 isLoginVerdictDone
            // 守门——查询在途时两个标志都是 false，守门路径统一等待重推。
            verdictDone.set(false)
            loginVerdictDone.set(false)
            Thread {
                val loggedIn = try {
                    PaddockClient.queryLoginStateFromModule()
                } catch (e: Throwable) {
                    AlaMobileModule.logX(
                        android.util.Log.WARN, TAG,
                        "login gate query failed (fail-closed): ${e.message}"
                    )
                    false
                }
                Handler(Looper.getMainLooper()).post {
                    loginVerdictDone.set(true)
                    if (loggedIn) {
                        AlaMobileModule.logX(
                            android.util.Log.INFO, TAG,
                            "paddock login verified via ConfigProvider (local auth file) → gate pass"
                        )
                        verdictDone.set(true)
                        // 放行后独立跑一次连通性问候(fetchMe 带网络,最长 ~18s)。
                        // ⚠️ 必须在 verdictDone 置位**之后**异步发起:early unlock
                        // 路径等 loginVerdictDone,BillingManager.Awake(≈2s)窗口
                        // 若被 fetchMe 的 connect/read 超时拖住就会错过 → 解锁失败。
                        announcePaddockConnection(context)
                    } else {
                        evaluateLogin(context)
                    }
                }
            }.start()
        }
    }

    /**
     * 登录门控判定是否出结果（early unlock 路径的额外守门条件）。
     * verdictDone 只反映 ①② 更新失配判定；登录判定（Provider IPC）更慢，
     * early unlock 在两者都完成前不得装 hook。
     */
    val isLoginVerdictDone: Boolean get() = loginVerdictDone.get()

    /**
     * 未登录激活（[evaluateLoginGate] 的 Provider 查询返回 false 后走到这里）：
     * 立标记 + 起 toast 循环。仅主线程调用。
     */
    private fun evaluateLogin(context: Context) {
        if (notLoggedIn.get()) {
            verdictDone.set(true)
            return
        }
        if (!notLoggedIn.compareAndSet(false, true)) {
            verdictDone.set(true)
            return
        }
        AlaMobileModule.logX(
            android.util.Log.WARN, TAG,
            "ACTIVATED (not logged in to paddock): all Java/Native hooks disabled, toast loop started"
        )
        startToastLoop(context)
        verdictDone.set(true)
    }

    /**
     * 登录门控**放行后**的一次性连通性问候 Toast(2026-09-10):
     * - [PaddockClient.fetchMe] 成功 → 已成功连接到围场,欢迎 x 号车手 xxx!
     * - 失败(断网/弱网/HTTP 非 200/token 失效)→ [TOAST_TEXT_OFFLINE],提示成绩
     *   上传风险 + 本地暂存 + 自动补传
     * 两种情况都**不影响放行**:本地登录态已由 [evaluateLoginGate] 确认,模块功能
     * 照常生效。纯提示,任何异常静默吞掉。
     *
     * ⚠️ 独立后台线程跑,绝不阻塞 [verdictDone](见调用点注释);Toast 回主线程弹。
     * 每进程仅调用一次(evaluateLoginGate 由 loginEvalStarted CAS 保证唯一)。
     */
    private fun announcePaddockConnection(context: Context) {
        Thread {
            val msg = try {
                val me = PaddockClient.fetchMe()
                if (me.ok) {
                    if (me.username.isNotBlank()) {
                        "已成功连接到围场,欢迎 ${me.regSeq} 号车手 ${me.username}!"
                    } else {
                        "已成功连接到围场!"
                    }
                } else {
                    TOAST_TEXT_OFFLINE
                }
            } catch (e: Throwable) {
                AlaMobileModule.logX(
                    android.util.Log.WARN, TAG,
                    "startup greeting fetchMe failed: ${e.message}"
                )
                TOAST_TEXT_OFFLINE
            }
            AlaMobileModule.logX(android.util.Log.INFO, TAG, "paddock startup greeting: $msg")
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                }
            }
        }.start()
    }

    /**
     * 模块过期后台网络检查（fail-open）：缓存未命中时由 [evaluate] 调用。
     * 检查回来激活 → 登录门控已随 [activateModuleOutdated] 退出竞争（更新优先于登录）。
     */
    private fun startBackgroundCheck(context: Context) {
        val current = tools.alamobile.mod.BuildConfig.VERSION_CODE
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
        // 更新优先于登录：模块过期命中后登录门控退出竞争（isActive 已含
        // moduleOutdated，hook 反正全禁，Toast 只显示更新文案不叠加）。
        notLoggedIn.set(true)
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
        moduleOutdated.get() -> TOAST_TEXT_MODULE
        notLoggedIn.get() -> TOAST_TEXT_NOT_LOGIN
        else -> TOAST_TEXT_MODULE
    }
}
