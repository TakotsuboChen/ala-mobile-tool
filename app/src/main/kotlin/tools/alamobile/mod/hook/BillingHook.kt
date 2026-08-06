package tools.alamobile.mod.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface

/**
 * BillingBridge Hook - 绕过 Google Play 验证
 *
 * 策略：
 * 1. Hook checkOwned() - 直接发送 OnAlreadyOwned，不查询 Google Play
 * 2. Hook checkOwnedInternal() - 阻止异步查询
 * 3. Hook OnOwnedNone() - 阻止错误弹窗
 */
object BillingHook {

    private const val TAG = "BillingHook"

    fun install(xposedInterface: XposedInterface, packageLoadedParam: XposedModuleInterface.PackageLoadedParam) {
        // 辅助：同时写 logcat 和 NPatch 日志文件（XposedBridge.log → XposedLogPrinter → file）
        fun log(priority: Int, msg: String) {
            android.util.Log.println(priority, TAG, msg)
            try { xposedInterface.log(priority, TAG, msg) } catch (_: Throwable) {}
        }
        fun logw(msg: String) { log(android.util.Log.WARN, msg) }
        fun loge(msg: String, e: Throwable? = null) {
            android.util.Log.e(TAG, msg, e)
            try { xposedInterface.log(android.util.Log.ERROR, TAG, if (e != null) "$msg\n${android.util.Log.getStackTraceString(e)}" else msg) } catch (_: Throwable) {}
        }

        try {
            val classLoader = packageLoadedParam.defaultClassLoader
            log(android.util.Log.INFO, "Installing BillingHook with classLoader=$classLoader")

            // 诊断：BillingBridge 类是否存在
            val billingBridgeClass = try {
                Class.forName("BillingBridge", true, classLoader).also {
                    log(android.util.Log.INFO, "BillingBridge class found: ${it.name} loader=${it.classLoader}")
                }
            } catch (e: ClassNotFoundException) {
                loge("BillingBridge class NOT FOUND in defaultClassLoader", e)
                // 尝试在系统 ClassLoader 找
                try {
                    val sysCl = ClassLoader.getSystemClassLoader()
                    val sysCls = Class.forName("BillingBridge", true, sysCl)
                    log(android.util.Log.INFO, "BillingBridge found via systemClassLoader: ${sysCls.name}")
                    sysCls
                } catch (e2: Throwable) {
                    loge("BillingBridge also NOT FOUND in systemClassLoader", e2)
                    throw e
                }
            }

            val sendUnityMessageMethod = billingBridgeClass.getDeclaredMethod(
                "sendUnityMessage",
                String::class.java,
                String::class.java
            ).apply {
                isAccessible = true
                log(android.util.Log.INFO, "sendUnityMessage method found: $this")
            }

            // Hook 1: checkOwned() - 主入口
            val checkOwnedMethod = billingBridgeClass.getDeclaredMethod("checkOwned")
            log(android.util.Log.INFO, "checkOwned() method found: $checkOwnedMethod")
            xposedInterface.hook(checkOwnedMethod)
                .intercept { chain ->
                    log(android.util.Log.INFO, "checkOwned() intercepted, sending OnAlreadyOwned")
                    try {
                        sendUnityMessageMethod.invoke(null, "OnAlreadyOwned", "unlock_alamobile")
                        log(android.util.Log.INFO, "checkOwned: OnAlreadyOwned sent successfully")
                    } catch (e: Throwable) {
                        loge("checkOwned: Failed to send", e)
                    }
                    null
                }
            log(android.util.Log.INFO, "Hooked checkOwned()")

            // Hook 2: checkOwnedInternal() - 阻止异步查询
            val checkOwnedInternalMethod = billingBridgeClass.getDeclaredMethod("checkOwnedInternal")
            checkOwnedInternalMethod.isAccessible = true
            log(android.util.Log.INFO, "checkOwnedInternal() method found: $checkOwnedInternalMethod")
            xposedInterface.hook(checkOwnedInternalMethod)
                .intercept { chain ->
                    log(android.util.Log.INFO, "checkOwnedInternal() intercepted, sending OnAlreadyOwned")
                    try {
                        sendUnityMessageMethod.invoke(null, "OnAlreadyOwned", "unlock_alamobile")
                        log(android.util.Log.INFO, "checkOwnedInternal: OnAlreadyOwned sent successfully")
                    } catch (e: Throwable) {
                        loge("checkOwnedInternal: Failed to send", e)
                    }
                    null
                }
            log(android.util.Log.INFO, "Hooked checkOwnedInternal()")

            // Hook 3 & 4: BillingManager 是 C# IL2CPP 类（不在 dex 里），
            // Class.forName 必然失败。这两个 hook 只是"挡弹窗"的辅助功能，
            // 不影响解锁主路径。用独立 try/catch 隔离，防止异常拖垮整个 install。
            try {
                val billingManagerClass = Class.forName("BillingManager", true, classLoader)
                log(android.util.Log.INFO, "BillingManager class found: ${billingManagerClass.name}")

                // Hook 3: OnOwnedNone() - 阻止错误弹窗
                val onOwnedNoneMethod = billingManagerClass.getDeclaredMethod("OnOwnedNone", String::class.java)
                onOwnedNoneMethod.isAccessible = true
                xposedInterface.hook(onOwnedNoneMethod)
                    .intercept { chain ->
                        log(android.util.Log.INFO, "OnOwnedNone() intercepted, blocking error dialog")
                        null
                    }
                log(android.util.Log.INFO, "Hooked OnOwnedNone()")

                // Hook 4: OnPurchaseFailed() - 阻止购买失败弹窗
                val onPurchaseFailedMethod = billingManagerClass.getDeclaredMethod("OnPurchaseFailed", String::class.java)
                onPurchaseFailedMethod.isAccessible = true
                xposedInterface.hook(onPurchaseFailedMethod)
                    .intercept { chain ->
                        log(android.util.Log.INFO, "OnPurchaseFailed() intercepted, blocking failure dialog")
                        null
                    }
                log(android.util.Log.INFO, "Hooked OnPurchaseFailed()")
            } catch (e: ClassNotFoundException) {
                logw("BillingManager class not found (C# IL2CPP, expected on Unity games) - skip OnOwnedNone/OnPurchaseFailed hooks")
            } catch (e: Throwable) {
                logw("BillingManager hooks failed: ${e.message} - skip (non-critical)")
            }

            log(android.util.Log.INFO, "All BillingHook hooks installed successfully!")

        } catch (e: Throwable) {
            loge("Failed to install BillingHook", e)
        }
    }
}
