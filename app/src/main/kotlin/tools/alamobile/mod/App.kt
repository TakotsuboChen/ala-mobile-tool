package tools.alamobile.mod

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import org.json.JSONObject

/**
 * 模块进程的 Application，负责连接 Xposed 框架的 XposedService。
 *
 * **两种框架路径**：
 * - **LSPosed（root 框架）**：[XposedServiceHelper.registerListener] 异步绑定 LSPosed
 *   daemon。daemon 常驻，[onServiceBind] 回调里拿到 [XposedService] 实例存静态变量，
 *   供 [tools.alamobile.mod.config.ModConfig.write] 写 daemon SQLite。daemon binder 由
 *   LSPosed Manager 经 `XposedProvider.call("SendBinder")` 推给模块进程。
 * - **NPatch（非 root 框架）**：NPatch 无 daemon，[XposedServiceHelper] 收不到 binder。
 *   但 NPatch 管理器进程暴露 `content://top.nkbe.npatch.remote` ContentProvider
 *   （[NPATCH_REMOTE_AUTHORITY]），`call("getRemoteService", modulePackageName=...)`
 *   返回一个可写 [IXposedService] binder，包装着 NPatch 管理器的 [NPatchRemoteStore]
 *   （SharedPreferences-backed）。[bindNpatchRemoteService] 主动调一次 ContentResolver
 *   拿这个 binder，构造 [XposedService] 存进 [xposedService] —— 对 [ModConfig.write]
 *   透明，它调 `getRemotePreferences().edit().putString(...).apply()` 时无感区分框架。
 *   游戏进程读配置时，[tools.alamobile.mod.AlaMobileModule] 的 [remoteConfigReader]
 *   调 `getRemotePreferences()`，NPatch loader 经 [ManagerRemoteServiceBridge]
 *   反向调同一个 ContentProvider 拿 read-only [ILSPInjectedModuleService] binder，
 *   读同一份 SharedPreferences。两端经 NPatch 管理器中转，不依赖任一进程常驻。
 *
 * **何时绑 NPatch service**：[onCreate] 调 [XposedServiceHelper.registerListener] 后，
 * LSPosed 路径会异步回调 [onServiceBind]；NPatch 路径不会。所以 [onCreate] 末尾
 * 立即调一次 [bindNpatchRemoteService] 兜底 —— 如果 NPatch 管理器装了 + 模块已注册
 * + 包可见性通过（manifest 的 `<queries>` 声明了 `top.nkbe.npatch`），就拿到 binder，
 * 构造 [XposedService] 存入 [xposedService]，[ModConfig.write] 直接可用；否则失败
 * 静默吞掉，[ModConfig.write] 走 filesDir + 广播兜底（现有行为）。
 *
 * **激活状态语义**：[LsposedStatus] 用 `xposedService != null` 判激活 —— LSPosed 真
 * 激活（daemon 绑定）和 NPatch 管理器模式（remote service 绑定）都判为已激活，
 * 不再走 Non-root 手动确认路径。这符合用户语义"模块当前被框架加载"。
 * NPatch embedded/local 模式（无管理器）仍走 Non-root 路径 —— 用户在弹窗里
 * 选"是"写 `nonroot_confirmed` 标记。
 *
 * 异步陷阱：[onCreate] 调 registerListener 后，[onServiceBind] 在未来某个时刻才回调。
 * ConfigActivity.onCreate 时 [.xposedService] 可能仍为 null —— [ModConfig.write] 必须
 * fallback：service 为 null 时仍写 filesDir + 发广播（旧方案兜底，零回归）。
 */
class App : Application(), XposedServiceHelper.OnServiceListener {

    companion object {
        private const val TAG = "AlaMobileTool"
        const val PREF_GROUP = "ala_mobile_tool"
        const val KEY_CONFIG_JSON = "config_json"

        // 激活状态相关 key（存同一份 Remote Preferences）。
        // module_loaded：模块 onModuleLoaded 执行时写 "1"，作为"曾被框架加载"的持久信号。
        // nonroot_confirmed：用户在弹窗里确认用了 LSPatch/NPatch/FPA 等 Non-root 框架后写 "1"。
        const val KEY_MODULE_LOADED = "module_loaded_v1"
        const val KEY_NONROOT_CONFIRMED = "nonroot_confirmed_v1"

        /**
         * NPatch 管理器的 RemoteApiProvider authority。
         * 见 references/NPatch/manager/src/main/.../RemoteApiProvider.kt —
         * manifest 里 `authorities="${applicationId}.remote"`，applicationId
         * 默认 `top.nkbe.npatch`（见 Constants.MANAGER_PACKAGE_NAME）。
         */
        private const val NPATCH_REMOTE_AUTHORITY = "top.nkbe.npatch.remote"
        private const val NPATCH_METHOD_GET_REMOTE_SERVICE = "getRemoteService"
        private const val NPATCH_KEY_MODULE_PACKAGE = "modulePackageName"
        private const val NPATCH_KEY_BINDER = "binder"

        @Volatile
        var xposedService: XposedService? = null
            private set

        /**
         * 主动从 NPatch 管理器的 RemoteApiProvider 拿可写 IXposedService binder。
         *
         * 只在 [xposedService] 仍为 null（LSPosed daemon 没绑上）时调用。成功则
         * 构造 [XposedService] 存入 [xposedService]，对 [tools.alamobile.mod.config.ModConfig]
         * 透明 —— 它调 `getRemotePreferences().edit().putString(...).apply()` 时
         * 经此 binder Binder-IPC 到 NPatch 管理器的 NPatchRemoteStore，写管理器
         * 进程的 `npatch_remote_<modulePkg>_<group>.xml` SharedPreferences。
         * 游戏进程的 [tools.alamobile.mod.AlaMobileModule.remoteConfigReader] 调
         * `getRemotePreferences()` 时，NPatch loader 反向调同一 ContentProvider
         * 拿 read-only service binder，读同一份 SharedPreferences。
         *
         * 失败条件（任一即 fallback 到 filesDir + 广播）：
         * - NPatch 管理器没装（包不可见，`<queries>` 声明也 resolve 不到）。
         * - 模块没在 NPatch 管理器里注册（`isKnownModule` 校验失败，Provider 返回 null）。
         * - 调用 UID 不属于模块包（`modulePackageName !in callerPackages` 校验失败）。
         * - embedded 模式（无管理器，patched APK 自带模块）。
         *
         * 幂等：已绑上（[xposedService] 非空）时直接返回，不重复绑。
         */
        fun bindNpatchRemoteService(context: Context) {
            if (xposedService != null) return
            try {
                val resolver = context.contentResolver
                val extras = Bundle().apply {
                    putString(NPATCH_KEY_MODULE_PACKAGE, context.packageName)
                }
                val result = resolver.call(
                    Uri.parse("content://$NPATCH_REMOTE_AUTHORITY"),
                    NPATCH_METHOD_GET_REMOTE_SERVICE,
                    null,
                    extras
                )
                val binder: IBinder? = result?.getBinder(NPATCH_KEY_BINDER)
                if (binder == null) {
                    Log.i(TAG, "App: NPatch remote service returned null binder (manager not installed or module not registered)")
                    return
                }
                // XposedService 构造器包级私有，但 XposedServiceHelper.onBinderReceived
                // 同包可调 —— 复用它把 binder 包成 XposedService 并触发已注册的 listener。
                // 这条路径等价于"框架经 XposedProvider.call(SendBinder) 推 binder 进来"。
                XposedServiceHelper::class.java.getDeclaredMethod("onBinderReceived", IBinder::class.java)
                    .apply { isAccessible = true }
                    .invoke(null, binder)
                Log.i(TAG, "App: NPatch remote service bound via $NPATCH_REMOTE_AUTHORITY")
                // NPatch 绑上后同样 flush filesDir → remote（与 LSPosed 路径
                // onServiceBind 对称）。NPatch 无 daemon 异步推 binder，bindNpatchRemoteService
                // 是主动一次性调用；成功后 onServiceBind 会被 onBinderReceived 触发，
                // 那里会调 flushLocalConfigToRemote，所以这里不需要重复调。
            } catch (e: Throwable) {
                Log.i(TAG, "App: NPatch remote service bind failed (likely LSPosed or embedded mode): ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ⚠️⚠️ vivo/OriginOS/Android 16 闪退修复：App.onCreate 在游戏进程的
        // createOrUpdateClassLoaderLocked 内部同步调用（模块 Application 被实例化时）。
        // XposedServiceHelper.registerListener 和 bindNpatchRemoteService（ContentResolver.call）
        // 都是重操作，在 Resources 初始化之前执行会干扰 LoadedApk.getResources() →
        // makeApplicationInner 时 Resources.getAssets() NPE 闪退。
        // 修复：全部延迟到 next main loop。handleBindApplication 先完成（Resources 就绪），
        // 然后再绑 service。ConfigActivity（模块自己的进程）不受影响——它的
        // Application.onCreate 不在 createOrUpdateClassLoaderLocked 路径里。
        val isGameProcess = packageName != "tools.alamobile.mod"
        if (isGameProcess) {
            Log.i(TAG, "App: game process detected, deferring service binding to next main loop")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                doServiceBinding()
            }
        } else {
            doServiceBinding()
        }
    }

    private fun doServiceBinding() {
        try {
            XposedServiceHelper.registerListener(this)
            Log.i(TAG, "App: XposedServiceHelper listener registered")
        } catch (e: Throwable) {
            Log.w(TAG, "App: failed to register XposedServiceHelper listener", e)
        }
        // NPatch 路径兜底：LSPosed daemon 没异步回调时，主动从 NPatch 管理器
        // RemoteApiProvider 拿可写 service binder。LSPosed 路径下此调用失败
        // （Provider 不可见 / isKnownModule 校验不过），静默吞掉。
        bindNpatchRemoteService(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        Log.i(TAG, "App: XposedService bound (LSPosed daemon path)")
        // service 绑上时把 filesDir 里的最新配置 flush 到 remote prefs。
        //
        // 兜底场景：用户在 ConfigActivity 改配置时 xposedService 还没绑上
        //（XposedServiceHelper 异步绑定延迟，或 LSPosed daemon 推 binder 时机
        // 不确定），ModConfig.write 只写了 filesDir + 广播，remote 没写。等
        // service 异步绑上时，这里把 filesDir 的最新配置补写到 remote——
        // 游戏不运行时改的配置就不会丢失。
        //
        // filesDir 的 JSON 始终是最新值（ModConfig.write 每次都写 filesDir），
        // 重复 flush 无害：RemotePreferences.doCommit 只在有 diff 时推。
        flushLocalConfigToRemote(service)
    }

    /**
     * 把模块 filesDir 的 [FILE_NAME] 配置 JSON flush 到 remote prefs。
     *
     * 只在 [onServiceBind] 时调——service 刚绑上，是"之前 xposedService 为
     * null 时漏写的 remote 配置"的补写时机。filesDir 不存在（用户从没改过
     * 配置）时 no-op。
     */
    private fun flushLocalConfigToRemote(service: XposedService) {
        try {
            val file = java.io.File(filesDir, "ala_tool_config.json")
            if (!file.exists()) {
                Log.i(TAG, "App: flushLocalConfigToRemote — no local config, nothing to flush")
                return
            }
            val json = file.readText()
            service.getRemotePreferences(PREF_GROUP)
                .edit()
                .putString(KEY_CONFIG_JSON, json)
                .apply()
            Log.i(TAG, "App: flushed local config to remote prefs (${json.length} bytes)")
        } catch (e: Throwable) {
            Log.w(TAG, "App: flushLocalConfigToRemote failed", e)
        }
    }

    override fun onServiceDied(service: XposedService) {
        // 只清 LSPosed 路径绑上的 —— NPatch 路径的 service 也走同一变量，
        // 但 NPatch 管理器进程死亡时 service binder 也会 die，这里清掉合理。
        xposedService = null
        Log.w(TAG, "App: XposedService died")
    }
}
