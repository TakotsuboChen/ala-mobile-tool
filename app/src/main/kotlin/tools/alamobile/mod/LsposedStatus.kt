package tools.alamobile.mod

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import java.io.File

object LsposedStatus {

    private val LSPOSED_MANAGER_PACKAGES = listOf(
        "org.lsposed.manager",
        "com.android.shell"
    )

    /**
     * Returns true if the module is likely activated by LSPosed.
     *
     * Detection combines multiple signals:
     * 1. AlaMobileModule writes a flag file when it loads in the target process.
     * 2. LSPosed Manager package is installed.
     */
    fun isActivated(context: Context): Boolean {
        val flag = File(Environment.getExternalStorageDirectory(), "AlaMobileTool/activated.flag")
        if (flag.exists()) {
            return true
        }

        val moduleFile = context.getExternalFilesDir(null)?.resolve("activated.flag")
        if (moduleFile?.exists() == true) {
            return true
        }

        return lsposedManagerInstalled(context)
    }

    private fun lsposedManagerInstalled(context: Context): Boolean {
        val pm = context.packageManager
        return LSPOSED_MANAGER_PACKAGES.any { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
