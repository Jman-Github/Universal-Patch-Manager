package app.universal.patch.manager.util

import android.content.Context
import app.universal.patch.manager.R
import app.universal.patch.manager.network.service.HttpService
import app.universal.patch.manager.network.utils.APIError
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Localize app-owned generation diagnostics without changing internal retry signals. */
fun Context.catalogErrorMessage(message: String?): String = when {
    message == null -> getString(R.string.patch_bundle_discovery_error)
    message.startsWith("Catalog changed;") && message.contains("release history") ->
        getString(R.string.catalog_changed_history)
    message.startsWith("Catalog changed;") -> getString(R.string.catalog_changed_refresh)
    else -> message
}

/** Convert transport failures to user-facing text while preserving the original exception for logs. */
internal fun Context.networkWarningMessage(error: Throwable): String? {
    for (cause in generateSequence(error) { it.cause }.take(8)) {
        when (cause) {
            is UnknownHostException -> return getString(R.string.warning_network_dns)
            is SocketTimeoutException, is HttpRequestTimeoutException ->
                return getString(R.string.warning_network_timeout)
            is SocketException -> return getString(
                if (cause.message.orEmpty().contains("connection abort", ignoreCase = true)) {
                    R.string.warning_network_connection_aborted
                } else {
                    R.string.warning_network_connection
                }
            )
            is APIError -> return httpWarningMessage(cause.code.value, cause.isRateLimitResponse())
            is HttpService.TooManyRequestsException -> return getString(R.string.warning_network_rate_limit)
            is HttpService.HttpException -> return httpWarningMessage(cause.status.value)
        }
    }
    return null
}

internal fun Throwable.isNetworkRateLimit(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { cause ->
        when (cause) {
            is APIError -> cause.isRateLimitResponse()
            is HttpService.TooManyRequestsException -> true
            is HttpService.HttpException -> cause.status.value == 429
            else -> false
        }
    }

private fun APIError.isRateLimitResponse(): Boolean =
    code.value == 429 || (code.value == 403 && message.orEmpty().contains("rate limit", true))

private fun Context.httpWarningMessage(status: Int, rateLimited: Boolean = false): String =
    if (status == 429 || rateLimited) getString(R.string.warning_network_rate_limit)
    else getString(R.string.warning_network_http_error, status)
