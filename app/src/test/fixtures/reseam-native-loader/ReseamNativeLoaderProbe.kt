package app.universal.patch.manager.patcher.reseam

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import app.universal.patch.manager.patcher.runtime.reseam.prepareReseamRuntimeFiles
import dalvik.system.DexClassLoader
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the real bridge, snapshot preparation, DexClassLoader and Reseam JNI on ART. */
object ReseamNativeLoaderProbe {
    private const val HOST = "app.reseam.sdk.ReseamAndroidHost"
    private class ProbeContext(private val directory: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCodeCacheDir(): File = directory.resolve("code-cache").apply { mkdirs() }
        override fun getCacheDir(): File = directory.resolve("cache").apply { mkdirs() }
        override fun getClassLoader(): ClassLoader = ReseamNativeLoaderProbe::class.java.classLoader!!
    }
    private fun activeLoader(): ClassLoader = ReseamRuntimeBridge::class.java.getDeclaredField("classLoader").let {
        it.isAccessible = true
        it.get(ReseamRuntimeBridge) as ClassLoader
    }
    private fun nativeInit(loader: ClassLoader) { Class.forName(HOST, true, loader) }

    @JvmStatic fun main(arguments: Array<String>) {
        val root = File(arguments[0])
        val a = root.resolve("source-a.apk")
        if (arguments.getOrNull(1) == "prepare") {
            repeat(4) {
                prepareReseamRuntimeFiles(a, root.resolve("shared-publication"), Build.SUPPORTED_ABIS.toList())
            }
            println("PASS: concurrent process snapshot publication")
            return
        }
        if (arguments.getOrNull(1) == "metadata") {
            val files = prepareReseamRuntimeFiles(a, root.resolve("metadata-runtime"), Build.SUPPORTED_ABIS.toList())
            val loader = DexClassLoader(files.apkFile.path, root.path,
                files.nativeLibraryDirectory.path, ReseamNativeLoaderProbe::class.java.classLoader!!.parent)
            val method = loader.loadClass("app.universal.patch.manager.reseam.runtime.ReseamRuntimeEntry")
                .getMethod("loadMetadataForBundle", String::class.java)
            repeat(20) { check((method.invoke(null, arguments[2]) as List<*>).isNotEmpty()) }
            check(Runtime.getRuntime().maxMemory() / 1048576 == 1024L)
            println("PASS: real Reseam metadata decoded 20 times with 1024 MB ART heap and JIT workaround")
            return
        }
        val b = root.resolve("source-b.apk")
        val abis = Build.SUPPORTED_ABIS.toList()
        val parent = ReseamNativeLoaderProbe::class.java.classLoader!!.parent
        val optimized = root.resolve("dex").apply { mkdirs() }
        val baseline = prepareReseamRuntimeFiles(a, root.resolve("baseline"), abis)
        fun competitor() = DexClassLoader(baseline.apkFile.path, optimized.path,
            baseline.nativeLibraryDirectory.path, parent)
        val original = competitor()
        nativeInit(original)
        val competing = competitor()
        try {
            nativeInit(competing)
            error("Expected ART to reject a competing JNI owner")
        } catch (expected: UnsatisfiedLinkError) {
            check(original !== competing)
            check(expected.message.orEmpty().contains("already")) { expected.toString() }
            println("PASS: reproduced ART's native library ownership collision")
        }
        val entry = competing.loadClass("app.universal.patch.manager.reseam.runtime.ReseamRuntimeEntry")
        try {
            entry.getMethod("loadMetadataForBundle", String::class.java).invokeReseamRuntime("/missing-bundle")
            error("Expected failed JNI initialization")
        } catch (expected: IOException) {
            check(expected.cause is LinkageError)
            println("PASS: failed JNI initializer is an import error, not an uncaught LinkageError")
        }

        val context = ProbeContext(root.resolve("manager"))
        ReseamRuntimeBridge.initialize(context, a.path)
        ReseamRuntimeBridge.engineVersion()
        val first = activeLoader()
        nativeInit(first)
        val alias = root.resolve("reimport.apk")
        a.copyTo(alias, overwrite = true)
        ReseamRuntimeBridge.initialize(context, alias.path)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === first)
        nativeInit(activeLoader())
        alias.setLastModified(alias.lastModified() + 10_000L)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === first)
        println("PASS: identical reimports and timestamp changes reuse the JNI owner")

        ReseamRuntimeBridge.initialize(context, b.path)
        ReseamRuntimeBridge.engineVersion()
        val second = activeLoader()
        check(second !== first)
        nativeInit(second)
        ReseamRuntimeBridge.initialize(context, a.path)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === first)
        nativeInit(first)
        println("PASS: runtime replacement and switching back retain independent native owners")

        val stamp = alias.lastModified()
        val size = alias.length()
        b.copyTo(alias, overwrite = true)
        alias.setLastModified(stamp)
        check(alias.length() == size)
        ReseamRuntimeBridge.initialize(context, alias.path)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === second)
        nativeInit(second)
        a.copyTo(alias, overwrite = true)
        alias.setLastModified(stamp)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === first)
        println("PASS: content replacement with unchanged path, size and timestamp selects the right JNI owner")

        ReseamRuntimeBridge.initialize(context, alias.path)
        a.delete()
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === first)
        nativeInit(first)
        println("PASS: deleting the original runtime APK does not invalidate the retained snapshot")

        val workers = Executors.newFixedThreadPool(8)
        try {
            val calls = (1..24).map { workers.submit<String?> { ReseamRuntimeBridge.engineVersion().also { nativeInit(activeLoader()) } } }
            calls.forEach { check(it.get(30, TimeUnit.SECONDS) != null) }
        } finally { workers.shutdownNow() }
        println("PASS: concurrent metadata entry lookups do not create competing loaders")

        ReseamRuntimeBridge.initialize(context, b.path)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === second)
        nativeInit(second)
        ReseamRuntimeBridge.initialize(context, alias.path)
        ReseamRuntimeBridge.engineVersion()
        check(activeLoader() === first)
        nativeInit(first)
        println("PASS: explicit bridge reinitialization retains existing JNI owners")
        println("PASS: Reseam JNI loader regression completed on Android ${Build.VERSION.SDK_INT}")
    }
}
