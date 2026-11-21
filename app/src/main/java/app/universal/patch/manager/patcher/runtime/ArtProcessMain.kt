package app.universal.patch.manager.patcher.runtime

import app.universal.patch.manager.patcher.host.RuntimeProtocol
import dalvik.system.DexClassLoader

/** Fresh ART VM, with heap properties set before app_process starts. */
object ArtProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        // Reserve stdout for the protocol, including when patch code prints diagnostics.
        System.setOut(System.err)
        RuntimeProtocol.run(args[0], "-") { params, send ->
            android.system.Os.setenv("TMPDIR", params["cacheDir"] as String, true)
            val host = ArtProcessMain::class.java.classLoader!!
            val loader = if (args[2].isNotEmpty()) DexClassLoader(args[2],
                params["cacheDir"] as String, args[3].ifEmpty { null }, host.parent) else host
            val device = runCatching {
                loader.loadClass("app.universal.patch.manager.patcher.runtime.RuntimeDevice")
            }.getOrNull()
            device?.methods?.firstOrNull { it.name == "configure" && it.parameterCount == 1 }
                ?.invoke(device.getField("INSTANCE").get(null), params["device"])
            RuntimeProtocol.invoke(loader.loadClass(args[1]), params, send)
        }
        kotlin.system.exitProcess(0)
    }
}
