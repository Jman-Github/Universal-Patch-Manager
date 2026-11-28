package app.universal.patch.manager.domain.manager

import com.android.apksig.ApkSigner
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.security.PrivateKey

/**
 * EC v1 signatures are supported from API 18. UPM itself requires API 26+,
 * so an older manifest minimum must not prevent signing for supported devices.
 * This overrides the signing compatibility range only, preserving APK contents.
 */
internal fun ApkSigner.Builder.supportSigningKey(key: PrivateKey, input: File): ApkSigner.Builder = apply {
    if (key.algorithm.equals("EC", ignoreCase = true) || key.algorithm.equals("ECDSA", ignoreCase = true)) {
        val manifestMinSdk = RandomAccessFile(input, "r").use { file ->
            ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(
                ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
            )
        }
        setMinSdkVersion(maxOf(18, manifestMinSdk))
    }
}
