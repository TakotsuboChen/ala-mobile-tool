package tools.alamobile.mod

import android.content.Context
import android.util.Log

/**
 * 模块激活状态判定。
 *
 * 判定基准：**`onModuleLoaded` 是否在本进程内执行**（仅对目标进程有效）
 * 或 **daemon 已绑定且 scope 非空**（对 ConfigActivity 进程有效）。
 *
 * `onModuleLoaded` 只会在模块被框架注入的目标进程（游戏进程）里调用，在模块
 * 自己的 ConfigActivity 进程里**从不调用**——所以 `System.getProperty(
 * MODULE_LOADED_FLAG)` 在 ConfigActivity 里**永远为 false**，不能作为单一判定。
 *
 * 对于 ConfigActivity 进程，可用的信号是：
 * 1. `App.xposedService != null` —— LSPosed daemon 已绑定到本进程。但 daemon
 *    在检测到已安装的 xposed 模块 APK 时，即使模块**未在 Manager 启用**，也会
 *    在模块进程首次创建时推 binder 过来。所以仅凭 service 绑定不能判定"已启用"。
 * 2. `getScope().isNotEmpty()` —— 模块在 Manager 里配置过的 scope 列表，存在
 *    LSPosed daemon 的 SQLite 数据库里。**scope 和模块的启用/禁用状态独立存储**：
 *    用户关掉模块开关**不会清 scope**，`pm clear` 也清不掉 daemon DB 里的 scope。
 *    所以"`service != null && getScope().isNotEmpty()`"在禁用后第一次打开仍可能
 *    误判为已激活（scope 残留）。
 * 3. 两者组合：`service != null && getScope().isNotEmpty()` 是当前可用的最佳
 *    近似信号。scope 残留的误判仅发生在"清除数据 + 关闭模块开关 + 第一次打开"
 *    的特定场景，第二次打开（进程重启后 daemon 不再推 binder，service 为 null）
 *    就不会再误判。
 *
 * 判定优先级：
 * 1. `hasModuleLoadedFlag()` —— 目标进程（游戏进程）路径，`onModuleLoaded` 执行过。
 * 2. `service != null && getScope().isNotEmpty()` —— ConfigActivity 进程路径。
 * 3. `readNonRootConfirmed()` —— 用户在弹窗里确认了 Non-root 框架。
 * 4. 默认 `INACTIVE`。
 *
 * 迁移规则：LSPosed 真激活覆盖并忘掉 Non-root 标记（清 nonroot_confirmed）；
 * 之后 LSPosed 关了 → 不保留 Non-root 已激活状态，必须重新点选。
 *
 * 异步时序：[App.xposedService] 在 ConfigActivity.onCreate 时可能仍为 null
 * （XposedServiceHelper 异步绑定）。`awaitService` 参数提供 3s 轮询兜底。
 */
object LsposedStatus {

    private const val TAG = "AlaMobileTool"

    /** 激活状态。UI 据此渲染卡片标题、描述、颜色与点击行为。 */
    enum class Status {
        /** 真被 LSPosed 加载（onModuleLoaded 在本进程执行过）。 */
        LSPOSED,
        /** 用户确认用 Non-root 框架（LSPatch/NPatch/FPA）加载。 */
        NONROOT,
        /** 未激活。 */
        INACTIVE
    }

    /**
     * 判定当前激活状态。
     *
     * **判定优先级**：
     * 1. `hasModuleLoadedFlag()` —— 目标进程（游戏进程）路径：`onModuleLoaded`
     *    执行过，markActivated 设了进程级 property。模块被真正启用时才有。
     * 2. `service != null && getScope().isNotEmpty()` —— ConfigActivity 进程路径：
     *    `onModuleLoaded` 在 ConfigActivity 里**从不调用**（模块进程不被注入），
     *    所以 property 永远为 false，必须用 daemon scope 判定。scope 存在 daemon
     *    SQLite DB，与启用状态独立存储，禁用后首次打开可能残留误判（见 [evaluate]
     *    的根因说明）。
     * 3. `readNonRootConfirmed()` —— 用户在弹窗里确认了 Non-root 框架。
     * 4. 默认 `INACTIVE`。
     */
    fun evaluate(context: Context, awaitService: Boolean = false): Status {
        // 1) 目标进程路径：onModuleLoaded 执行过（游戏进程被真正注入）。
        if (hasModuleLoadedFlag()) {
            clearNonRootConfirmed(context)
            return Status.LSPOSED
        }

        // 2) ConfigActivity 进程路径：daemon 已绑定 + scope 非空 = Manager 里启用。
        //    App.xposedService 由 App.onServiceBind 赋值，只在框架触发 binder 时调到。
        //    异步绑定可能晚于首次读检测——轮询等待最多 ~3s。
        val service = App.xposedService
        if (service != null) {
            if (hasEnabledScope(service)) {
                clearNonRootConfirmed(context)
                return Status.LSPOSED
            }
        } else if (awaitService) {
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                val s = App.xposedService
                if (s != null) {
                    if (hasEnabledScope(s)) {
                        clearNonRootConfirmed(context)
                        return Status.LSPOSED
                    }
                    break
                }
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }

        // 3) Non-root 用户确认标记 —— 用户在弹窗里选了"是"。
        if (readNonRootConfirmed(context)) {
            return Status.NONROOT
        }

        return Status.INACTIVE
    }

    /** 本进程是否执行过 onModuleLoaded（markActivated 设的进程级 property，仅目标进程）。 */
    private fun hasModuleLoadedFlag(): Boolean =
        System.getProperty(AlaMobileModule.MODULE_LOADED_FLAG) == "true"

    /**
     * 检查模块是否在框架 Manager 里被启用（scope 非空）。
     *
     * [XposedService.getScope] 返回模块配置过的目标 App 包名列表。scope 为空 =
     * 模块已安装/已被框架识别，但未配置任何目标 App。scope 非空通常意味着模块
     * 被启用过（scope 是启用模块的必要配置）。
     *
     * 异常处理：如果 getScope() 因 daemon 临时故障失败，保守返回 true——
     * 避免已启用模块因 daemon 抖动被误判为未激活（"已激活→未激活"比
     * "未激活→已激活"更让用户困惑）。
     */
    private fun hasEnabledScope(service: io.github.libxposed.service.XposedService): Boolean {
        return try {
            service.getScope().isNotEmpty()
        } catch (e: Throwable) {
            Log.w(TAG, "hasEnabledScope: getScope() failed, assuming enabled", e)
            true
        }
    }

    /** 用户在弹窗里选"是"（用了 Non-root 框架）→ 写持久标记。 */
    fun confirmNonRoot(context: Context) {
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .putString(App.KEY_NONROOT_CONFIRMED, "1")
                    .apply()
                Log.i(TAG, "confirmNonRoot: remote prefs updated")
                return
            } catch (e: Throwable) {
                Log.w(TAG, "confirmNonRoot: remote prefs failed, trying local", e)
            }
        }
        // 兜底：service 没绑上时写模块 filesDir（ConfigActivity 进程可写）。
        try {
            val file = java.io.File(context.filesDir, "nonroot_confirmed.flag")
            file.writeText("1")
        } catch (e: Throwable) {
            Log.w(TAG, "confirmNonRoot: local fallback failed", e)
        }
    }

    /** 用户在弹窗里选"否" → 清掉 Non-root 标记，保持未激活。 */
    fun clearNonRootConfirmed(context: Context) {
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_NONROOT_CONFIRMED)
                    .apply()
            } catch (_: Throwable) {}
        }
        try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").delete()
        } catch (_: Throwable) {}
    }

    /** "设置 → 清除激活标记"用：彻底清掉所有激活痕迹。 */
    fun clearAll(context: Context) {
        clearNonRootConfirmed(context)
        EulaManager.clear(context)
        // 旧版本（property/daemon module_loaded 路径）残留清理，向后兼容。
        // 新方案下这两个标记已不再写，但用户可能从旧版升级，清掉避免迷惑。
        val service = App.xposedService
        if (service != null) {
            try {
                service.getRemotePreferences(App.PREF_GROUP)
                    .edit()
                    .remove(App.KEY_MODULE_LOADED)
                    .apply()
            } catch (_: Throwable) {}
        }
        try {
            System.clearProperty(AlaMobileModule.MODULE_LOADED_FLAG)
        } catch (_: Throwable) {}
    }

    private fun readNonRootConfirmed(context: Context): Boolean {
        val service = App.xposedService
        if (service != null) {
            try {
                val v = service.getRemotePreferences(App.PREF_GROUP)
                    .getString(App.KEY_NONROOT_CONFIRMED, null)
                if (v == "1") return true
            } catch (e: Throwable) {
                Log.w(TAG, "readNonRootConfirmed: remote prefs failed", e)
            }
        }
        // 兜底读本地。
        return try {
            java.io.File(context.filesDir, "nonroot_confirmed.flag").exists() &&
                java.io.File(context.filesDir, "nonroot_confirmed.flag").readText() == "1"
        } catch (_: Throwable) { false }
    }

    /**
     * 旧 API 兼容：[OverviewPage] 原先直接拿 Boolean。
     * 保留以减少调用方改动，但语义已改为"非未激活"（LSPOSED 或 NONROOT）。
     */
    fun isActivated(context: Context): Boolean = evaluate(context) != Status.INACTIVE
}
