package tools.alamobile.mod

import tools.alamobile.mod.offsets.OffsetTable

object NativeBridge {

    init {
        System.loadLibrary("ala-core")
    }

    @JvmStatic
    external fun init(
        // Pedal / control replacement offsets
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

        // DRS offsets
        drsToggle: Long,

        // Unlock offsets
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

    /**
     * Helper that passes all [OffsetTable] values to the native layer.
     */
    @JvmStatic
    fun initWithOffsets(
        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean,
        disableAutoGear: Boolean = false,
        enableUnlock: Boolean = false
    ) {
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
