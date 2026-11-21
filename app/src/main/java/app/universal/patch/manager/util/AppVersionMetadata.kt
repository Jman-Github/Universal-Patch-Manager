package app.universal.patch.manager.util

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat

/** Only reuse a code when the APK metadata describes the retained version. */
fun PackageInfo.versionCodeFor(version: String): Long? {
    val code = PackageInfoCompat.getLongVersionCode(this)
    return code.takeIf {
        versionName == version || (versionName.isNullOrBlank() && code.toString() == version)
    }
}
