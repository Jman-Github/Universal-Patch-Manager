package app.universal.patch.manager.network.service

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.url
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class HttpServiceDownloadTest {
    private val payload = ByteArray(2 * 1024 * 1024 + 37) { (it * 31).toByte() }

    private fun serve(handler: (HttpExchange) -> Unit, test: (String) -> Unit) {
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = pool
        server.createContext("/") { exchange ->
            try { handler(exchange) } catch (_: java.io.IOException) { /* Client cancelled. */ }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            finally { exchange.close() }
        }
        server.start()
        try { test("http://127.0.0.1:" + server.address.port + "/") }
        finally { server.stop(0); pool.shutdownNow() }
    }

    private fun client() = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            socketTimeoutMillis = 30_000
            requestTimeoutMillis = 60_000
        }
    }

    private fun head(exchange: HttpExchange): Boolean {
        if (exchange.requestMethod != "HEAD") return false
        exchange.responseHeaders.set("Content-Length", payload.size.toString())
        exchange.responseHeaders.set("Accept-Ranges", "bytes")
        exchange.responseHeaders.set("ETag", "\"one\"")
        exchange.sendResponseHeaders(200, -1)
        return true
    }

    private fun full(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, payload.size.toLong())
        exchange.responseBody.write(payload)
    }

    private fun range(exchange: HttpExchange, mode: String = "valid") {
        val header = exchange.requestHeaders.getFirst("Range")
        if (header == null) { full(exchange); return }
        val (start, end) = header.removePrefix("bytes=").split("-").map(String::toInt)
        val data = payload.copyOfRange(start, end + 1)
        exchange.responseHeaders.set("Content-Range",
            if (mode == "wrong") "bytes 1-${data.size}/${payload.size}"
            else "bytes $start-$end/${payload.size}")
        val actual = when (mode) {
            "short" -> data.copyOf(data.size / 2)
            "long" -> data + byteArrayOf(42)
            else -> data
        }
        exchange.sendResponseHeaders(206, actual.size.toLong())
        exchange.responseBody.write(actual)
    }

    private fun download(address: String, threads: Int = 5): ByteArray {
        val file = Files.createTempFile("upm-download", ".bin").toFile()
        try {
            client().use { client ->
                runBlocking { HttpService(Json, client, downloadWarning = { _, _ -> }).downloadToFile(file, threads, { url(address) }) }
            }
            return file.readBytes()
        } finally { file.delete() }
    }

    @Test fun validRangesAreConcurrentAndByteExact() {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val requests = AtomicInteger()
        serve({ exchange ->
            if (!head(exchange)) {
                assertEquals("\"one\"", exchange.requestHeaders.getFirst("If-Range"))
                requests.incrementAndGet()
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try { Thread.sleep(80); range(exchange) } finally { active.decrementAndGet() }
            }
        }) { address ->
            assertArrayEquals(payload, download(address))
            assertEquals(5, requests.get())
            assertTrue("Expected overlapping range requests", peak.get() > 1)
        }
    }

    @Test fun ignoredRangesFallBackToOneStream() {
        val fullRequests = AtomicInteger()
        serve({ exchange ->
            if (!head(exchange)) {
                if (exchange.requestHeaders.getFirst("Range") == null) fullRequests.incrementAndGet()
                full(exchange)
            }
        }) { address ->
            assertArrayEquals(payload, download(address))
            assertEquals(1, fullRequests.get())
        }
    }

    private fun invalidRangeFallsBack(mode: String) {
        val fullRequests = AtomicInteger()
        serve({ exchange ->
            if (!head(exchange)) {
                if (exchange.requestHeaders.getFirst("Range") == null) fullRequests.incrementAndGet()
                range(exchange, mode)
            }
        }) { address ->
            assertArrayEquals(payload, download(address))
            assertEquals(1, fullRequests.get())
        }
    }

    @Test fun wrongRangeFallsBack() = invalidRangeFallsBack("wrong")
    @Test fun shortRangeFallsBack() = invalidRangeFallsBack("short")
    @Test fun oversizedRangeFallsBack() = invalidRangeFallsBack("long")

    @Test fun unknownLengthUsesStreamingDownload() {
        serve({ exchange ->
            if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(405, -1)
            else {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write(payload)
            }
        }) { address -> assertArrayEquals(payload, download(address)) }
    }

    @Test fun singleStreamSkipsProbes() {
        val requests = AtomicInteger()
        serve({ exchange ->
            requests.incrementAndGet()
            assertEquals("GET", exchange.requestMethod)
            assertNull(exchange.requestHeaders.getFirst("Range"))
            full(exchange)
        }) { address ->
            assertArrayEquals(payload, download(address, 1))
            assertEquals(1, requests.get())
        }
    }

    @Test fun stalledRangesFallBackWithinBoundedTime() {
        serve({ exchange ->
            if (!head(exchange)) {
                if (exchange.requestHeaders.getFirst("Range") != null) Thread.sleep(20_000)
                else full(exchange)
            }
        }) { address ->
            val started = System.nanoTime()
            assertArrayEquals(payload, download(address))
            assertTrue("Stalled ranges should fall back promptly",
                (System.nanoTime() - started) / 1_000_000 < 19_000)
        }
    }

    @Test fun slowHeadProbeDoesNotHoldUpDownload() {
        serve({ exchange ->
            if (exchange.requestMethod == "HEAD") Thread.sleep(10_000)
            else range(exchange)
        }) { address ->
            val started = System.nanoTime()
            assertArrayEquals(payload, download(address))
            assertTrue("Optional HEAD probe should time out",
                (System.nanoTime() - started) / 1_000_000 < 9_000)
        }
    }

    @Test fun truncatedSingleStreamFailsAndDeletesOutput() {
        serve({ exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload, 0, payload.size / 2)
        }) { address ->
            val file = Files.createTempFile("upm-short", ".bin").toFile().also { it.delete() }
            try {
                client().use { client ->
                    runBlocking {
                        try {
                            HttpService(Json, client, downloadWarning = { _, _ -> }).downloadToFile(file, 1, { url(address) })
                            fail("Truncated response must fail")
                        } catch (_: java.io.IOException) {
                            assertFalse(file.exists())
                        }
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun cancellationDuringProbeDoesNotStartDownload() {
        val entered = CompletableDeferred<Unit>()
        val gets = AtomicInteger()
        serve({ exchange ->
            if (exchange.requestMethod == "HEAD") { entered.complete(Unit); Thread.sleep(10_000) }
            else { gets.incrementAndGet(); full(exchange) }
        }) { address ->
            val file = Files.createTempFile("upm-cancel", ".bin").toFile().also { it.delete() }
            try {
                client().use { client ->
                    runBlocking {
                        val job = launch {
                            HttpService(Json, client, downloadWarning = { _, _ -> }).downloadToFile(file, builder = { url(address) })
                        }
                        withTimeout(3_000) { entered.await() }
                        withTimeout(3_000) { job.cancelAndJoin() }
                        assertFalse(file.exists())
                        assertEquals(0, gets.get())
                    }
                }
            } finally { file.delete() }
        }
    }

    @Test fun requestCancellationIsNotConvertedToApiFailure() {
        val entered = CompletableDeferred<Unit>()
        serve({ _ -> entered.complete(Unit); Thread.sleep(10_000) }) { address ->
            client().use { client ->
                runBlocking {
                    var returned = false
                    val job = launch {
                        HttpService(Json, client, downloadWarning = { _, _ -> }).request<String> { url(address) }
                        returned = true
                    }
                    withTimeout(3_000) { entered.await() }
                    withTimeout(3_000) { job.cancelAndJoin() }
                    assertFalse(returned)
                }
            }
        }
    }

    private fun preservesExistingFile(cancel: Boolean) {
        val directory = Files.createTempDirectory("upm-existing").toFile()
        val target = directory.resolve("bundle.rvp").also { it.writeText("previous bundle") }
        try {
            serve({ exchange ->
                if (!head(exchange)) {
                    if (cancel) range(exchange) else exchange.sendResponseHeaders(500, -1)
                }
            }) { address ->
                client().use { client ->
                    runBlocking {
                        val failure = runCatching {
                            HttpService(Json, client, downloadWarning = { _, _ -> }).downloadToFile(target, builder = { url(address) },
                                onProgress = { read, _ ->
                                    if (cancel && read > 0) throw CancellationException("Stop")
                                })
                        }.exceptionOrNull()
                        assertTrue(if (cancel) failure is CancellationException else failure is HttpService.HttpException)
                        assertEquals("previous bundle", target.readText())
                        assertEquals(listOf(target.name), directory.listFiles()!!.map { it.name })
                    }
                }
            }
        } finally {
            directory.listFiles()?.forEach { it.delete() }
            directory.delete()
        }
    }

    @Test fun failedReplacementPreservesExistingFile() = preservesExistingFile(cancel = false)
    @Test fun cancelledReplacementPreservesExistingFile() = preservesExistingFile(cancel = true)

    @Test fun simultaneousDownloadsKeepIndependentFiles() {
        serve({ exchange -> if (!head(exchange)) range(exchange) }) { address ->
            runBlocking {
                val outputs = (0..2).map { async(Dispatchers.IO) { download(address) } }.awaitAll()
                outputs.forEach { assertArrayEquals(payload, it) }
            }
        }
    }

    @Test fun cancellationDuringRangesDeletesPreallocatedFile() {
        val file = Files.createTempFile("upm-cancel-ranges", ".bin").toFile().also { it.delete() }
        try {
            serve({ exchange -> if (!head(exchange)) range(exchange) }) { address ->
                client().use { client ->
                    runBlocking {
                        try {
                            HttpService(Json, client, downloadWarning = { _, _ -> }).downloadToFile(file, builder = { url(address) },
                                onProgress = { read, _ -> if (read > 0) throw CancellationException("Stop") })
                            fail("Cancellation should escape")
                        } catch (_: CancellationException) {
                            assertFalse(file.exists())
                        }
                    }
                }
            }
        } finally { file.delete() }
    }
}
