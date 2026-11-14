/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */
package app.universal.patch.manager.patcher.host

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1223
// https://github.com/MorpheApp/morphe-manager/pull/1246

import kotlinx.serialization.json.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import app.universal.patch.manager.patcher.runtime.usage.PatcherResourceMonitor

object RuntimeProtocol {
    fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> buildJsonObject {
            put("_upmNumericType", value.javaClass.simpleName)
            put("value", JsonPrimitive(value))
        }
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to encode(it.value) })
        is Iterable<*> -> JsonArray(value.map(::encode))
        is Array<*> -> JsonArray(value.map(::encode))
        else -> error("Unsupported protocol value: ${value.javaClass.name}")
    }

    fun decode(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> if (value.keys == setOf("_upmNumericType", "value")) {
            val number = value.getValue("value").jsonPrimitive.content
            when (value.getValue("_upmNumericType").jsonPrimitive.content) {
                "Byte" -> number.toByte()
                "Short" -> number.toShort()
                "Integer" -> number.toInt()
                "Long" -> number.toLong()
                "Float" -> number.toFloat()
                "Double" -> number.toDouble()
                else -> error("Unsupported numeric option type")
            }
        } else value.entries.associate { it.key to decode(it.value) }
        is JsonArray -> value.map(::decode)
        is JsonPrimitive -> if (value.isString) value.content else
            value.booleanOrNull ?: value.intOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
    }

    fun invoke(entry: Class<*>, params: Map<String, Any?>, send: (String, Map<String, Any?>) -> Unit,
        isCancelled: () -> Boolean = { false }): String? {
        val method = entry.methods.single { it.name == "runPatcher" && it.parameterCount == 2 }
        val callback = Proxy.newProxyInstance(entry.classLoader, arrayOf(method.parameterTypes[1])) { proxy, m, args ->
            when (m.name) {
                "log" -> { send("log", mapOf("level" to args!![0], "message" to args[1])); null }
                "event" -> { send("event", mapOf("event" to args!![0])); null }
                "isCancelled" -> isCancelled()
                "toString" -> "BoundRuntimeCallback"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> error("Unknown callback method: ${m.name}")
            }
        }
        return try { method.invoke(null, params, callback) as? String }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    fun run(requestPath: String, eventPath: String, execute: (Map<String, Any?>, (String, Map<String, Any?>) -> Unit) -> String?) {
        // app_process stdout may be a socket, which cannot be reopened through /proc.
        val output = if (eventPath == "-") java.io.FileOutputStream(java.io.FileDescriptor.out)
            else File(eventPath).outputStream()
        output.bufferedWriter().use { writer ->
            var stallWatch: StallWatch? = null
            val send: (String, Map<String, Any?>) -> Unit = { type, values ->
                stallWatch?.onEvent(type)
                synchronized(writer) {
                    try {
                        writer.write(encode(values + ("type" to type)).toString())
                        writer.newLine()
                        writer.flush()
                    } catch (_: java.io.IOException) {
                        // Cancellation travels over the command pipe; event delivery must not abort native code.
                        // In particular, never throw from a Reseam JNI callback on a closed pipe.
                    }
                }
            }
            send("started", emptyMap())
            send("log", mapOf("level" to "INFO", "message" to vmDescription()))
            val monitor = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "RuntimeMemory").apply { isDaemon = true }
            }
            var resources: PatcherResourceMonitor.Session? = null
            val failure = try {
                stallWatch = StallWatch({ message ->
                    send("log", mapOf("level" to "WARN", "message" to message))
                }).start()
                monitor.scheduleAtFixedRate({
                    val rt = Runtime.getRuntime()
                    runCatching { send("memory", mapOf("used" to (rt.totalMemory() - rt.freeMemory()) / 1048576,
                        "max" to rt.maxMemory() / 1048576)) }
                }, 0, 1, TimeUnit.SECONDS)
                resources = PatcherResourceMonitor.start(includeProcessMemory = true) { send("log", mapOf("level" to "INFO", "message" to it)) }
                @Suppress("UNCHECKED_CAST")
                val params = decode(Json.parseToJsonElement(File(requestPath).readText())) as Map<String, Any?>
                execute(params, send)
            } catch (error: Throwable) {
                if (error is OutOfMemoryError) "java.lang.OutOfMemoryError: runtime heap exhausted"
                    else error.stackTraceToString()
            } finally {
                stallWatch?.close()
                resources?.stop()
                monitor.shutdownNow()
                monitor.awaitTermination(1, TimeUnit.SECONDS)
            }
            // No telemetry from this run may follow its terminal event into the next run.
            send("finished", mapOf("error" to failure))
        }
    }
}
