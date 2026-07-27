package tools.alamobile.mod.offsets

/**
 * IL2CPP method/field offsets for Ala Mobile 8.0.0 (versionCode 200142).
 *
 * These values are generated from the Il2CppDumper output. Do NOT hard-code
 * guessed offsets; run `tools/run-il2cpp-dumper.sh` after every game update
 * and regenerate this file.
 *
 * Method offsets are relative to the base of libil2cpp.so.
 */
object OffsetTable {

    // IRDSCarControllInput (TypeDefIndex: 328)
    // Method offsets are the "Offset" values from Il2CppDumper (file offset in libil2cpp.so).
    const val IRDS_CAR_CONTROLL_INPUT_SET_THROTTLE: Long = 0x1A511F4L
    const val IRDS_CAR_CONTROLL_INPUT_SET_BRAKE: Long = 0x1A511D8L
    const val IRDS_CAR_CONTROLL_INPUT_SET_CLUTCH: Long = 0x1A51294L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_UP: Long = 0x1A52FF0L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_DOWN: Long = 0x1A53044L
    const val IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE: Long = 0x1A530ACL

    // IRDSCarControllInput instance fields
    const val IRDS_CAR_CONTROLL_INPUT_THROTTLE_FIELD: Long = 0x174L // _inputTorque
    const val IRDS_CAR_CONTROLL_INPUT_BRAKE_FIELD: Long = 0x178L    // _brake
    const val IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD: Long = 0xD0L    // inputClutch

    // IRDSDrivetrain (TypeDefIndex: 337)
    const val IRDS_DRIVETRAIN_SET_GEAR: Long = 0x1A59014L
    const val IRDS_DRIVETRAIN_SHIFT_UP: Long = 0x1A5B16CL
    const val IRDS_DRIVETRAIN_SHIFT_DOWN: Long = 0x1A5B2F0L

    // IRDSDrivetrain instance fields
    const val IRDS_DRIVETRAIN_CURRENT_GEAR_FIELD: Long = 0xC0L
    const val IRDS_DRIVETRAIN_THROTTLE_FIELD: Long = 0xB4L
    const val IRDS_DRIVETRAIN_THROTTLE_INPUT_FIELD: Long = 0xB8L
}
