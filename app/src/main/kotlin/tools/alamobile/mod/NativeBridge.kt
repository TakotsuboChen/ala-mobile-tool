package tools.alamobile.mod

import android.content.Context
import android.util.Log
import tools.alamobile.mod.offsets.OffsetTable
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object NativeBridge {

    private const val TAG = "AlaMobileTool"
    private const val LIB_NAME = "ala-core"
    private const val MODULE_PKG = "tools.alamobile.mod"

    /**
     * Whether the native library is available for JNI calls in the current ClassLoader.
     *
     * In coexistence builds, LSPosed may use isolated ClassLoaders that each
     * load NativeBridge independently. The Android linker rejects loading the
     * same .so path twice. We work around this by extracting the .so to a
     * temp file and loading it with System.load(absolutePath).
     */
    @JvmStatic
    var isAvailable: Boolean = false
        private set

    init {
        // Try standard load first
        try {
            System.loadLibrary(LIB_NAME)
            isAvailable = true
            Log.i(TAG, "libala-core.so loaded via standard System.loadLibrary")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Standard loadLibrary failed (ClassLoader conflict): ${e.message?.take(80)}")
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error loading libala-core.so: ${e.message}")
        }
    }

    /**
     * Force-loads the native library by extracting it to a temp file.
     *
     * Call this from BOTH AlaMobileModule.onPackageReady (LspModuleClassLoader)
     * AND OverlayManager (VectorModuleClassLoader) to ensure the JNI methods
     * are bound in every ClassLoader that uses NativeBridge.
     */
    @JvmStatic
    fun forceLoad(context: Context) {
        if (isAvailable) return

        try {
            Log.i(TAG, "forceLoad: extracting libala-core.so for classloader: ${NativeBridge::class.java.classLoader}")

            val apkPath = context.packageManager.getApplicationInfo(MODULE_PKG, 0).sourceDir
            val tempLib = File(context.cacheDir, "libala-core-${System.currentTimeMillis()}.so")

            ZipFile(apkPath).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/lib${LIB_NAME}.so")
                    ?: throw IllegalStateException("lib/arm64-v8a/lib${LIB_NAME}.so not found in APK")

                zip.getInputStream(entry).use { input ->
                    FileOutputStream(tempLib).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Load the temp file - linker treats it as a new library
            System.load(tempLib.absolutePath)
            isAvailable = true
            Log.i(TAG, "forceLoad successful: ${tempLib.absolutePath}")
        } catch (e: Throwable) {
            Log.e(TAG, "forceLoad failed: ${e.message}", e)
        }
    }

    @JvmStatic
    external fun init(
        setThrottleInput: Long,
        setBrakeInput: Long,
        setClutchInput: Long,
        shiftUpOffset: Long,
        shiftDownOffset: Long,
        setGearOffset: Long,
        fixedUpdateOffset: Long,
        throttleField: Long,
        brakeField: Long,
        actualThrottleField: Long,
        actualBrakeField: Long,
        clutchField: Long,
        drivetrainGearField: Long,
        drivetrainFixedUpdateOffset: Long,
        drivetrainAutomaticField: Long,
        playerControlsUpdateOffset: Long,
        drsToggle: Long,
        billingManagerAwake: Long,
        billingManagerInitializeBilling: Long,
        billingManagerOnOwnedNone: Long,
        billingManagerOnPurchaseFailed: Long,
        billingManagerSetUnlocked: Long,
        billingManagerIsUnlockedField: Long,
        billingManagerHasStoreConnectionField: Long,
        billingManagerHasCompletedOwnershipCheckField: Long,
        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean,
        disableAutoGear: Boolean,
        enableUnlock: Boolean
    )

    @JvmStatic
    external fun setThrottle(value: Float)

    @JvmStatic
    external fun setBrake(value: Float)

    @JvmStatic
    external fun setClutch(value: Float)

    @JvmStatic
    external fun shiftUp()

    @JvmStatic
    external fun shiftDown()

    @JvmStatic
    external fun setGear(gear: Int)

    @JvmStatic
    external fun setDRSActive(active: Boolean)

    @JvmStatic
    fun setThrottleSafe(value: Float) {
        if (!isAvailable) return
        try { setThrottle(value) } catch (e: Throwable) { Log.w(TAG, "setThrottle failed", e) }
    }

    @JvmStatic
    fun setBrakeSafe(value: Float) {
        if (!isAvailable) return
        try { setBrake(value) } catch (e: Throwable) { Log.w(TAG, "setBrake failed", e) }
    }

    @JvmStatic
    fun shiftUpSafe() {
        if (!isAvailable) return
        try { shiftUp() } catch (e: Throwable) { Log.w(TAG, "shiftUp failed", e) }
    }

    @JvmStatic
    fun shiftDownSafe() {
        if (!isAvailable) return
        try { shiftDown() } catch (e: Throwable) { Log.w(TAG, "shiftDown failed", e) }
    }

    @JvmStatic
    fun setDRSActiveSafe(active: Boolean) {
        if (!isAvailable) return
        try { setDRSActive(active) } catch (e: Throwable) { Log.w(TAG, "setDRSActive failed", e) }
    }

    @JvmStatic
    fun initWithOffsets(
        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean,
        disableAutoGear: Boolean = false,
        enableUnlock: Boolean = false
    ) {
        if (!isAvailable) {
            Log.w(TAG, "Native library not available, skipping initWithOffsets")
            return
        }
        init(
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SET_THROTTLE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SET_BRAKE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SET_CLUTCH,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SHIFT_UP,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_SHIFT_DOWN,
            OffsetTable.IRDS_DRIVETRAIN_SET_GEAR,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_FIXED_UPDATE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_THROTTLE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_BRAKE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_ACTUAL_THROTTLE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_ACTUAL_BRAKE_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD,
            OffsetTable.IRDS_DRIVETRAIN_CURRENT_GEAR_FIELD,
            OffsetTable.IRDS_DRIVETRAIN_FIXED_UPDATE,
            OffsetTable.IRDS_DRIVETRAIN_AUTOMATIC_FIELD,
            OffsetTable.IRDS_PLAYER_CONTROLS_UPDATE,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE,
            OffsetTable.BILLING_MANAGER_AWAKE,
            OffsetTable.BILLING_MANAGER_INITIALIZE_BILLING,
            OffsetTable.BILLING_MANAGER_ON_OWNED_NONE,
            OffsetTable.BILLING_MANAGER_ON_PURCHASE_FAILED,
            OffsetTable.BILLING_MANAGER_SET_UNLOCKED,
            OffsetTable.BILLING_MANAGER_IS_UNLOCKED_FIELD,
            OffsetTable.BILLING_MANAGER_HAS_STORE_CONNECTION_FIELD,
            OffsetTable.BILLING_MANAGER_HAS_COMPLETED_OWNERSHIP_CHECK_FIELD,
            enableControlReplacement,
            enableAutoDRS,
            disableAutoGear,
            enableUnlock
        )
    }
}
