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
        clutchField: Long,
        drivetrainGearField: Long,

        // DRS offsets
        drsToggle: Long,

        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean,
        disableAutoGear: Boolean
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
        disableAutoGear: Boolean = false
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
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD,
            OffsetTable.IRDS_DRIVETRAIN_CURRENT_GEAR_FIELD,
            OffsetTable.IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE,
            enableControlReplacement,
            enableAutoDRS,
            disableAutoGear
        )
    }
}
