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
        try {
            val classLoader = packageLoadedParam.defaultClassLoader
            val billingBridgeClass = Class.forName("BillingBridge", true, classLoader)

            val sendUnityMessageMethod = billingBridgeClass.getDeclaredMethod(
                "sendUnityMessage",
                String::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }

            // Hook 1: checkOwned() - 主入口
            val checkOwnedMethod = billingBridgeClass.getDeclaredMethod("checkOwned")
            xposedInterface.hook(checkOwnedMethod)
                .intercept { chain ->
                    Log.i(TAG, "checkOwned() intercepted, sending OnAlreadyOwned")
                    try {
                        sendUnityMessageMethod.invoke(null, "OnAlreadyOwned", "unlock_alamobile")
                        Log.i(TAG, "checkOwned: OnAlreadyOwned sent successfully")
                    } catch (e: Throwable) {
                        Log.e(TAG, "checkOwned: Failed to send: ${e.message}")
                    }
                    null
                }
            Log.i(TAG, "Hooked checkOwned()")

            // Hook 2: checkOwnedInternal() - 阻止异步查询
            val checkOwnedInternalMethod = billingBridgeClass.getDeclaredMethod("checkOwnedInternal")
            checkOwnedInternalMethod.isAccessible = true
            xposedInterface.hook(checkOwnedInternalMethod)
                .intercept { chain ->
                    Log.i(TAG, "checkOwnedInternal() intercepted, sending OnAlreadyOwned")
                    try {
                        sendUnityMessageMethod.invoke(null, "OnAlreadyOwned", "unlock_alamobile")
                        Log.i(TAG, "checkOwnedInternal: OnAlreadyOwned sent successfully")
                    } catch (e: Throwable) {
                        Log.e(TAG, "checkOwnedInternal: Failed to send: ${e.message}")
                    }
                    null
                }
            Log.i(TAG, "Hooked checkOwnedInternal()")

            // Hook 3: OnOwnedNone() - 阻止错误弹窗
            val billingManagerClass = Class.forName("BillingManager", true, classLoader)
            val onOwnedNoneMethod = billingManagerClass.getDeclaredMethod("OnOwnedNone", String::class.java)
            onOwnedNoneMethod.isAccessible = true
            xposedInterface.hook(onOwnedNoneMethod)
                .intercept { chain ->
                    Log.i(TAG, "OnOwnedNone() intercepted, blocking error dialog")
                    null
                }
            Log.i(TAG, "Hooked OnOwnedNone()")

            // Hook 4: OnPurchaseFailed() - 阻止购买失败弹窗
            val onPurchaseFailedMethod = billingManagerClass.getDeclaredMethod("OnPurchaseFailed", String::class.java)
            onPurchaseFailedMethod.isAccessible = true
            xposedInterface.hook(onPurchaseFailedMethod)
                .intercept { chain ->
                    Log.i(TAG, "OnPurchaseFailed() intercepted, blocking failure dialog")
                    null
                }
            Log.i(TAG, "Hooked OnPurchaseFailed()")

            Log.i(TAG, "All BillingHook hooks installed successfully!")

        } catch (e: Throwable) {
            Log.e(TAG, "Failed to install BillingHook: ${e.message}", e)
        }
    }
}
