package tools.alamobile.mod

object NativeBridge {

    init {
        System.loadLibrary("ala-core")
    }

    @JvmStatic
    external fun init(
        throttleSetter: Long,
        brakeSetter: Long,
        gearSetter: Long,
        drsSetter: Long,
        drsGetter: Long,
        enableControlReplacement: Boolean,
        enableAutoDRS: Boolean
    )

    @JvmStatic
    external fun setThrottle(value: Float)

    @JvmStatic
    external fun setBrake(value: Float)

    @JvmStatic
    external fun setGear(gear: Int)

    @JvmStatic
    external fun shiftUp()

    @JvmStatic
    external fun shiftDown()

    @JvmStatic
    external fun setDRSActive(active: Boolean)
}
