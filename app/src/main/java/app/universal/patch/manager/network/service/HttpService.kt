package app.universal.patch.manager.network.service

import android.util.Log
import app.universal.patch.manager.network.utils.APIError
import app.universal.patch.manager.network.utils.APIFailure
import app.universal.patch.manager.network.utils.APIResponse
import app.universal.patch.manager.util.tag
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * @author Aliucord Authors, DiamondMiner88
 */
class HttpService(
    val json: Json,
    val http: HttpClient,
    private val downloadWarning: (String, Throwable) -> Unit = { message, error ->
        Log.w("HttpService", message, error)
    },
) {
    suspend inline fun <reified T> request(crossinline builder: HttpRequestBuilder.() -> Unit = {}): APIResponse<T> {
        var body: String? = null
        return try {
            runWith429Retry("request") {
                try {
                    val response = http.request {
                        builder()
                    }

                    if (response.status == HttpStatusCode.TooManyRequests) {
                        throw TooManyRequestsException(response.retryAfterMillis())
                    }

                    if (response.status.isSuccess()) {
                        body = response.bodyAsText()

                        if (T::class == String::class) {
                            @Suppress("UNCHECKED_CAST")
                            return@runWith429Retry APIResponse.Success(body as T)
                        }

                        APIResponse.Success(json.decodeFromString(body!!))
                    } else {
                        body = try {
                            response.bodyAsText()
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            null
                        }

                        Log.e(
                            tag,
                            "Failed to fetch: API error, http status: ${response.status}, body: $body"
                        )
                        APIResponse.Error(APIError(response.status, body))
                    }
                } catch (t: TooManyRequestsException) {
                    throw t
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to fetch: error: $t, body: $body")
                    APIResponse.Failure(APIFailure(t, body))
                }
            }
        } catch (t: TooManyRequestsException) {
            Log.w(tag, "request failed with HTTP 429 after retries")
            APIResponse.Error(APIError(HttpStatusCode.TooManyRequests, body))
        }
    }

    suspend fun streamTo(
        outputStream: OutputStream,
        builder: HttpRequestBuilder.() -> Unit,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)? = null
    ) {
        try {
            runWith429Retry("streamTo") {
                http.prepareGet(builder).execute { httpResponse ->
                    when {
                        httpResponse.status == HttpStatusCode.TooManyRequests -> {
                            throw TooManyRequestsException(httpResponse.retryAfterMillis())
                        }
                        httpResponse.status.isSuccess() -> {
                            val encoding = httpResponse.headers[HttpHeaders.ContentEncoding]
                            val contentLength = httpResponse.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                ?.takeIf { encoding == null || encoding.equals("identity", true) }
                            val channel: ByteReadChannel = httpResponse.body()
                            withContext(Dispatchers.IO) {
                                var bytesRead = 0L
                                var lastReportedBytes = 0L
                                var lastReportedAt = 0L
                                fun reportProgress(force: Boolean = false) {
                                    if (onProgress == null) return
                                    val now = System.currentTimeMillis()
                                    val byteDelta = abs(bytesRead - lastReportedBytes)
                                    if (!force && byteDelta < 64 * 1024 && now - lastReportedAt < 200) return
                                    lastReportedBytes = bytesRead
                                    lastReportedAt = now
                                    onProgress(bytesRead, contentLength)
                                }
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = channel.readAvailable(buffer)
                                    if (count == -1) break
                                    if (count == 0) continue
                                    outputStream.write(buffer, 0, count)
                                    bytesRead += count.toLong()
                                    reportProgress()
                                }
                                if (contentLength != null && bytesRead != contentLength) {
                                    throw IOException("Incomplete download: $bytesRead of $contentLength bytes")
                                }
                                reportProgress(force = true)
                            }
                        }
                        else -> throw HttpException(httpResponse.status)
                    }
                }
            }
        } catch (t: TooManyRequestsException) {
            throw HttpException(HttpStatusCode.TooManyRequests)
        }
    }

    suspend fun download(
        saveLocation: File,
        resumeFrom: Long = 0,
        builder: HttpRequestBuilder.() -> Unit
    ) {
        try {
            runWith429Retry("download") {
                http.prepareGet {
                    if (resumeFrom > 0) {
                        header(HttpHeaders.Range, "bytes=$resumeFrom-")
                    }
                    builder()
                }.execute { httpResponse ->
                    when {
                        httpResponse.status == HttpStatusCode.TooManyRequests -> throw TooManyRequestsException(httpResponse.retryAfterMillis())
                        httpResponse.status.isSuccess() -> {
                            val channel: ByteReadChannel = httpResponse.body()
                            val append = resumeFrom > 0 && httpResponse.status == HttpStatusCode.PartialContent
                            if (resumeFrom > 0 && !append && saveLocation.exists()) {
                                saveLocation.delete()
                            }
                            FileOutputStream(saveLocation, append).use { outputStream ->
                                withContext(Dispatchers.IO) {
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        val count = channel.readAvailable(buffer)
                                        if (count == -1) break
                                        if (count == 0) continue
                                        outputStream.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        else -> throw HttpException(httpResponse.status)
                    }
                }
            }
        } catch (t: TooManyRequestsException) {
            throw HttpException(HttpStatusCode.TooManyRequests)
        }
    }

    suspend fun downloadToFile(
        saveLocation: File,
        threads: Int = DEFAULT_DOWNLOAD_THREADS,
        builder: HttpRequestBuilder.() -> Unit,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)? = null
    ) {
        withContext(Dispatchers.IO) {
            val destination = saveLocation.absoluteFile
            destination.parentFile?.mkdirs()
            // Keep a previous export intact until a complete replacement is ready.
            val partial = File.createTempFile("upm-download-", ".tmp", destination.parentFile)
            val workers = threads.coerceIn(1, MAX_DOWNLOAD_THREADS)
            try {
                val probe = if (workers > 1) probeRangeSupport(builder) else RangeProbe(false, null)
                val totalSize = probe.contentLength
                val parallelComplete = if (probe.supportsRanges && totalSize != null && totalSize >= MIN_MULTIPART_SIZE) {
                    try {
                        downloadConcurrent(partial, totalSize, workers, probe.validator, builder, onProgress)
                        true
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: IOException) {
                        // coroutineScope has joined every range worker before this file is truncated.
                        downloadWarning("Parallel download failed; restarting with one stream", failure)
                        false
                    } catch (failure: HttpException) {
                        downloadWarning("Parallel download rejected; restarting with one stream", failure)
                        false
                    }
                } else false
                if (!parallelComplete) {
                    onProgress?.invoke(0L, null)
                    FileOutputStream(partial, false).use { output ->
                        streamTo(output, {
                            builder()
                            header(HttpHeaders.AcceptEncoding, "identity")
                            timeout { socketTimeoutMillis = DOWNLOAD_IDLE_TIMEOUT_MS }
                        }, onProgress)
                    }
                }
                Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally {
                partial.delete()
            }
        }
    }

    class HttpException(status: HttpStatusCode) : Exception("Failed to fetch: http status: $status")
    class TooManyRequestsException(val retryAfterMillis: Long?) : Exception("HTTP 429 Too Many Requests")

    @PublishedApi
    internal suspend fun <T> runWith429Retry(operationName: String, block: suspend () -> T): T {
        var attempt = 0
        var delayMs = INITIAL_RETRY_DELAY_MS
        while (true) {
            try {
                attempt += 1
                return block()
            } catch (t: TooManyRequestsException) {
                if (attempt >= MAX_RETRY_ATTEMPTS) throw t
                val wait = (t.retryAfterMillis ?: delayMs).coerceAtMost(MAX_RETRY_DELAY_MS)
                Log.w(tag, "$operationName hit HTTP 429 (attempt $attempt/$MAX_RETRY_ATTEMPTS), retrying in ${wait}ms")
                delay(wait)
                delayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
            }
        }
    }

    @PublishedApi
    internal fun HttpResponse.retryAfterMillis(): Long? {
        val headerValue = headers[HttpHeaders.RetryAfter] ?: return null
        return headerValue.toLongOrNull()?.coerceAtLeast(0)?.times(1000)
    }

    private data class RangeProbe(
        val supportsRanges: Boolean,
        val contentLength: Long?,
        val validator: String? = null,
    )

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
        val (start, end, total) = match.destructured
        val parsed = ContentRange(start.toLongOrNull() ?: return null,
            end.toLongOrNull() ?: return null, total.toLongOrNull() ?: return null)
        return parsed.takeIf { it.start <= it.end && it.end < it.total }
    }

    private suspend fun probeRangeSupport(builder: HttpRequestBuilder.() -> Unit): RangeProbe {
        // Probes are optional optimizations: do not spend the download's retry budget on them.
        suspend fun probe(block: suspend () -> RangeProbe?): RangeProbe? = try {
            withTimeoutOrNull(RANGE_PROBE_TIMEOUT_MS) { block() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
        val head = probe {
            http.prepareRequest {
                builder()
                method = HttpMethod.Head
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                val length = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it > 0 }
                RangeProbe(response.status.isSuccess() &&
                    response.headers[HttpHeaders.AcceptRanges].equals("bytes", true) && length != null,
                    length.takeIf { response.status.isSuccess() },
                    response.headers[HttpHeaders.ETag]?.takeUnless { it.startsWith("W/") }
                        ?: response.headers[HttpHeaders.LastModified])
            }
        }
        if (head?.supportsRanges == true) return head
        return probe {
            http.prepareGet {
                builder()
                header(HttpHeaders.Range, "bytes=0-0")
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                RangeProbe(response.status == HttpStatusCode.PartialContent &&
                    range?.start == 0L && range.end == 0L,
                    range?.total,
                    response.headers[HttpHeaders.ETag]?.takeUnless { it.startsWith("W/") }
                        ?: response.headers[HttpHeaders.LastModified])
            }
        } ?: RangeProbe(false, head?.contentLength)
    }

    private suspend fun downloadConcurrent(
        saveLocation: File,
        totalSize: Long,
        threads: Int,
        validator: String?,
        builder: HttpRequestBuilder.() -> Unit,
        onProgress: ((bytesRead: Long, contentLength: Long?) -> Unit)?,
    ) = coroutineScope {
        saveLocation.parentFile?.mkdirs()
        if (saveLocation.exists()) {
            saveLocation.delete()
        }
        RandomAccessFile(saveLocation, "rw").use { raf ->
            raf.setLength(totalSize)
        }

        val totalRead = AtomicLong(0L)
        val lastReportedBytes = AtomicLong(0L)
        val lastReportedAt = AtomicLong(0L)
        val progressLock = Any()

        fun reportProgress(force: Boolean = false) {
            if (onProgress == null) return
            val now = System.currentTimeMillis()
            val current = totalRead.get()
            val byteDelta = abs(current - lastReportedBytes.get())
            val timeDelta = now - lastReportedAt.get()
            if (!force && byteDelta < 64 * 1024 && timeDelta < 200) return
            synchronized(progressLock) {
                val nowLocked = System.currentTimeMillis()
                val currentLocked = totalRead.get()
                val byteDeltaLocked = abs(currentLocked - lastReportedBytes.get())
                val timeDeltaLocked = nowLocked - lastReportedAt.get()
                if (!force && byteDeltaLocked < 64 * 1024 && timeDeltaLocked < 200) return
                lastReportedBytes.set(currentLocked)
                lastReportedAt.set(nowLocked)
                onProgress(currentLocked, totalSize)
            }
        }

        val chunkSize = totalSize / threads
        val ranges = (0 until threads).map { index ->
            val start = index * chunkSize
            val end = if (index == threads - 1) totalSize - 1 else (start + chunkSize - 1)
            start to end
        }

        ranges.map { (start, end) ->
            async(Dispatchers.IO) {
                runWith429Retry("downloadRange") {
                    http.prepareGet {
                        builder()
                        header(HttpHeaders.Range, "bytes=$start-$end")
                        header(HttpHeaders.AcceptEncoding, "identity")
                        validator?.let { header(HttpHeaders.IfRange, it) }
                        timeout { socketTimeoutMillis = DOWNLOAD_IDLE_TIMEOUT_MS }
                    }.execute { httpResponse ->
                        when {
                            httpResponse.status == HttpStatusCode.TooManyRequests ->
                                throw TooManyRequestsException(httpResponse.retryAfterMillis())
                            httpResponse.status == HttpStatusCode.PartialContent -> {
                                val expected = end - start + 1
                                if (parseContentRange(httpResponse.headers[HttpHeaders.ContentRange]) !=
                                    ContentRange(start, end, totalSize)) {
                                    throw IOException("Server returned an unexpected byte range")
                                }
                                val channel: ByteReadChannel = httpResponse.body()
                                RandomAccessFile(saveLocation, "rw").use { raf ->
                                    raf.seek(start)
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var received = 0L
                                    while (true) {
                                        val count = channel.readAvailable(buffer)
                                        if (count == -1) break
                                        if (count == 0) continue
                                        if (count > expected - received) {
                                            throw IOException("Server exceeded the requested byte range")
                                        }
                                        raf.write(buffer, 0, count)
                                        received += count
                                        totalRead.addAndGet(count.toLong())
                                        reportProgress()
                                    }
                                    if (received != expected) {
                                        throw IOException("Incomplete byte range: $received of $expected bytes")
                                    }
                                }
                            }
                            else -> throw HttpException(httpResponse.status)
                        }
                    }
                }
            }
        }.awaitAll()

        reportProgress(force = true)
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val INITIAL_RETRY_DELAY_MS = 1_000L
        private const val MAX_RETRY_DELAY_MS = 30_000L
        private const val DEFAULT_DOWNLOAD_THREADS = 5
        private const val MIN_MULTIPART_SIZE = 1024L * 1024L
        private const val MAX_DOWNLOAD_THREADS = 8
        private const val RANGE_PROBE_TIMEOUT_MS = 5_000L
        private const val DOWNLOAD_IDLE_TIMEOUT_MS = 15_000L
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
    }
}
