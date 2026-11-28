package app.universal.patch.manager.patcher.split

import com.reandroid.apk.ApkModule
import com.reandroid.apk.DexFileInputSource
import com.reandroid.arsc.value.DataType
import java.util.zip.ZipEntry

object SplitDexCompression {
    // android.R.attr.useEmbeddedDex: inlined here so this policy also runs outside Android.
    private const val USE_EMBEDDED_DEX_ATTR = 0x101059e

    @JvmStatic
    fun apply(module: ApkModule) {
        val application = module.androidManifest?.applicationElement ?: return
        // Direct DEX loading requires uncompressed entries:
        // https://developer.android.com/privacy-and-security/security-dex
        val embeddedDex = application.getAttributes {
            it.nameId == USE_EMBEDDED_DEX_ATTR || it.name == "useEmbeddedDex"
        }.asSequence().any {
            // An unresolved reference could enable direct loading; keep its DEX stored.
            it.dataType != DataType.BOOLEAN || it.dataAsBoolean
        }
        val uncompressed = module.uncompressedFiles
        for (source in module.zipEntryMap.toArray()) {
            val name = source.alias
            if ('/' in name || !DexFileInputSource.isDexName(name)) continue
            if (embeddedDex) {
                uncompressed.addPath(name)
                source.method = ZipEntry.STORED
            } else {
                // Match AntiSplit-M's compressed DEX output, including renamed split DEX.
                // https://github.com/AbdurazaaqMohammed/AntiSplit-M/blob/3ed96fc36872fa2deabafa4b8e141213c2e81abf/app/src/main/java/com/reandroid/apk/ApkModule.java#L1339
                uncompressed.removePath(source.name)
                uncompressed.removePath(name)
                source.method = ZipEntry.DEFLATED
            }
        }
    }
}
