package app.universal.patch.manager.patcher.host

import app.universal.patch.manager.patcher.runtime.JvmHeapPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

fun main() {
    check(JvmHeapPolicy.limitMb(2048, true) == 2048)
    check(JvmHeapPolicy.limitMb(6144, true) == 2048)
    check(JvmHeapPolicy.limitMb(16384, true) == 3072)
    check(JvmHeapPolicy.limitMb(Long.MAX_VALUE, true) == 3072)
    check(JvmHeapPolicy.limitMb(2048, false) == 1024)
    check(JvmHeapPolicy.limitMb(2560, false) == 1280)
    check(JvmHeapPolicy.limitMb(16384, false) == 1536)
    check(JvmHeapPolicy.minimumMb(false) == 1024)

    val shortSpike = GcOverheadWindow()
    check(shortSpike.sample(0, 500_000) == null)
    check(shortSpike.sample(10_000, 510_000) == null)
    check(shortSpike.sample(120_000, 510_000) == null)

    val sustained = GcOverheadWindow()
    var collection = 0L
    for (second in 0..240 step 10) {
        if (second > 0) collection += if (second <= 120) 8_000 else 2_000
        val share = sustained.sample(second * 1000L, collection)
        if (second < 120) check(share == null)
        if (second == 120) check(share != null && share >= 0.8)
        if (second == 240) check(share == null) // Old pressure must age out.
    }
    val boundary = GcOverheadWindow()
    boundary.sample(0, 0)
    check(boundary.sample(120_000, 72_000) == 0.6)
    val reset = GcOverheadWindow()
    reset.sample(0, 1000)
    check(reset.sample(120_000, -1) == null)
    check(reset.sample(130_000, 100) == null)
    check(reset.sample(140_000, 50) == null)

    val baseline = CountDownLatch(1)
    val exhausted = CountDownLatch(1)
    val phase = AtomicInteger()
    val failure = AtomicReference<String>()
    GcOverheadWatch(
        gcMillis = {
            val value = if (phase.get() == 0) 0L else 72_000L
            baseline.countDown()
            value
        },
        onExhausted = { failure.set(it); exhausted.countDown() },
        pollMs = 5,
        clockMillis = { if (phase.get() == 0) 0L else 120_000L }
    ).use {
        check(baseline.await(2, TimeUnit.SECONDS))
        phase.set(1)
        check(exhausted.await(2, TimeUnit.SECONDS))
    }
    check(failure.get().startsWith("java.lang.OutOfMemoryError:"))
    check(Thread.getAllStackTraces().keys.none { it.name == "GcOverheadWatch" && it.isAlive })
    println("PASS: heap budgets, rolling GC window, threshold, recovery and watcher shutdown")
}

interface MemoryFixtureCallback {
    fun log(level: String, message: String)
    fun event(value: Map<String, Any?>)
    fun isCancelled(): Boolean
}

object MemoryRuntimeFixtureEntry {
    @JvmStatic fun runPatcher(params: Map<String, Any?>, callback: MemoryFixtureCallback): String? {
        callback.log("INFO", "fixture run")
        callback.event(mapOf("fixture" to true))
        if (params["fail"] == true) throw OutOfMemoryError("fixture heap exhausted")
        return null
    }
}
