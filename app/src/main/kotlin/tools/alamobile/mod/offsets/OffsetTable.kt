package tools.alamobile.mod.offsets

/**
 * IL2CPP method/field offsets for Ala Mobile 8.0.0 (versionCode 200142).
 *
 * These values are generated from the Il2CppDumper output. Do NOT hard-code
 * guessed offsets; run `tools/run-il2cpp-dumper.sh` after every game update
 * and regenerate this file.
 *
 * Method offsets are the *RVA* values from Il2CppDumper (relative virtual
 * addresses in libil2cpp.so). `dl_iterate_phdr` returns the ELF base address,
 * which corresponds to RVA 0, so we must use RVA here, not the file offset.
 */
object OffsetTable {

    // IRDSCarControllInput (TypeDefIndex: 328)
    // RVA values from dump.cs (e.g. "RVA: 0x1A551F4").
    const val IRDS_CAR_CONTROLL_INPUT_SET_THROTTLE: Long = 0x1A551F4L
    const val IRDS_CAR_CONTROLL_INPUT_SET_BRAKE: Long = 0x1A551D8L
    const val IRDS_CAR_CONTROLL_INPUT_SET_CLUTCH: Long = 0x1A55294L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_UP: Long = 0x1A56FF0L
    const val IRDS_CAR_CONTROLL_INPUT_SHIFT_DOWN: Long = 0x1A57044L
    const val IRDS_CAR_CONTROLL_INPUT_DRS_TOGGLE: Long = 0x1A570ACL
    const val IRDS_CAR_CONTROLL_INPUT_FIXED_UPDATE: Long = 0x1A56908L

    // IRDSCarControllInput instance fields (relative to instance base)
    const val IRDS_CAR_CONTROLL_INPUT_THROTTLE_FIELD: Long = 0x174L // _inputTorque
    const val IRDS_CAR_CONTROLL_INPUT_BRAKE_FIELD: Long = 0x178L    // _brake
    const val IRDS_CAR_CONTROLL_INPUT_ACTUAL_THROTTLE_FIELD: Long = 0x16CL // actualInputTorque
    const val IRDS_CAR_CONTROLL_INPUT_ACTUAL_BRAKE_FIELD: Long = 0x170L    // actualBrake
    const val IRDS_CAR_CONTROLL_INPUT_CLUTCH_FIELD: Long = 0xD0L    // inputClutch

    // IRDSDrivetrain (TypeDefIndex: 337)
    const val IRDS_DRIVETRAIN_SET_GEAR: Long = 0x1A5D014L
    const val IRDS_DRIVETRAIN_SHIFT_UP: Long = 0x1A5F16CL
    const val IRDS_DRIVETRAIN_SHIFT_DOWN: Long = 0x1A5F2F0L
    const val IRDS_DRIVETRAIN_FIXED_UPDATE: Long = 0x1A5DDD8L

    // IRDSDrivetrain instance fields
    const val IRDS_DRIVETRAIN_CURRENT_GEAR_FIELD: Long = 0xC0L
    const val IRDS_DRIVETRAIN_THROTTLE_FIELD: Long = 0xB4L
    const val IRDS_DRIVETRAIN_THROTTLE_INPUT_FIELD: Long = 0xB8L
    const val IRDS_DRIVETRAIN_AUTOMATIC_FIELD: Long = 0xBCL

    // BillingManager (TypeDefIndex: 910)
    const val BILLING_MANAGER_AWAKE: Long = 0x186CC90L
    const val BILLING_MANAGER_INITIALIZE_BILLING: Long = 0x186CE20L
    const val BILLING_MANAGER_ON_OWNED_NONE: Long = 0x186E32CL
    const val BILLING_MANAGER_ON_PURCHASE_FAILED: Long = 0x186E2A0L
    const val BILLING_MANAGER_SET_UNLOCKED: Long = 0x186E440L
    const val BILLING_MANAGER_GET_INSTANCE: Long = 0x186C958L

    // BillingManager instance fields
    const val BILLING_MANAGER_IS_UNLOCKED_FIELD: Long = 0x20L
    const val BILLING_MANAGER_HAS_STORE_CONNECTION_FIELD: Long = 0x21L
    const val BILLING_MANAGER_HAS_COMPLETED_OWNERSHIP_CHECK_FIELD: Long = 0x22L
}
