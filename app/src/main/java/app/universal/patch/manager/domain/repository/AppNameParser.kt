package app.universal.patch.manager.domain.repository

import kotlinx.serialization.json.*
import java.net.URI

internal val appNamePackagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

/** Registry detail responses must identify the requested app before metadata is accepted. */
internal fun parseRegistryAppMetadata(body: String, packageName: String): Pair<String?, String?> {
    val app = Json.parseToJsonElement(body) as? JsonObject ?: error("Invalid app response")
    require((app["package_name"] as? JsonPrimitive)?.content == packageName) { "Wrong app response" }
    val name = (app["app_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 200 && it != packageName }
    val rawIcon = (app["icon_url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val icon = listOf("fdroid", "izzy", "play").firstNotNullOfOrNull { safeStoreAppIconUrl(rawIcon, it) }
    return name to icon
}

// Provider selection and JSON-LD handling follow the registry's name enrichment:
// https://github.com/Jman-Github/Patch-Bundle-Registry/blob/bundles/scripts/refresh_app_metadata.py
// Keep discovery on its own API path. These parsers are only for local app selection.
internal fun parseStoreAppName(html: String, provider: String, packageName: String? = null): String? {
    if (provider == "izzy") {
        if (packageName == null || !Regex("AppID:</b>\\s*</td>\\s*<td[^>]*>\\s*${Regex.escape(packageName)}\\s*</td>", RegexOption.IGNORE_CASE).containsMatchIn(html)) return null
        return Regex("<h2>([^<]+)</h2>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
    }
    if (provider == "play") {
        val scripts = Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (script in scripts.findAll(html)) {
            if (!script.groupValues[1].contains("application/ld+json", ignoreCase = true)) continue
            val root = runCatching { Json.parseToJsonElement(script.groupValues[2]) }.getOrNull() ?: continue
            fun findName(value: JsonElement, depth: Int = 0): String? {
                if (depth > 16) return null
                if (value is JsonArray) return value.firstNotNullOfOrNull { findName(it, depth + 1) }
                if (value !is JsonObject) return null
                val type = value["@type"]
                val types = if (type is JsonArray) type else listOfNotNull(type)
                if (types.any { (it as? JsonPrimitive)?.content?.substringAfterLast('/') in setOf("SoftwareApplication", "MobileApplication") }) {
                    (value["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotBlank)?.let { return it }
                }
                return value.values.firstNotNullOfOrNull { findName(it, depth + 1) }
            }
            findName(root)?.let { return it }
        }
    }
    val attributes = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
    val title = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).firstNotNullOfOrNull { tag ->
        val attrs = attributes.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
        attrs["content"]?.takeIf { (attrs["property"] ?: attrs["name"]).equals("og:title", true) }
    } ?: return null
    val suffix = when (provider) {
        "play" -> " - Apps on Google Play"
        "fdroid" -> " | F-Droid - Free and Open Source Android App Repository"
        "izzy" -> " | IzzyOnDroid"
        else -> return null
    }
    return title.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)?.trim()?.takeIf(String::isNotBlank)
}

/** Use application icons only, never a store's generic logo or an unrelated image. */
internal fun parseStoreAppIconUrl(html: String, provider: String, packageName: String? = null): String? {
    if (parseStoreAppName(html, provider, packageName) == null) return null
    if (provider == "play") {
        fun image(value: JsonElement?): String? = when (value) {
            is JsonPrimitive -> safeStoreAppIconUrl(value.takeIf { it.isString }?.content, provider)
            is JsonArray -> value.firstNotNullOfOrNull(::image)
            is JsonObject -> image(value["url"]) ?: image(value["contentUrl"])
            else -> null
        }
        fun find(value: JsonElement, depth: Int = 0): String? {
            if (depth > 16) return null
            if (value is JsonArray) return value.firstNotNullOfOrNull { find(it, depth + 1) }
            if (value !is JsonObject) return null
            val type = value["@type"]
            val types = if (type is JsonArray) type else listOfNotNull(type)
            if (types.any { (it as? JsonPrimitive)?.content?.substringAfterLast('/') in setOf("SoftwareApplication", "MobileApplication") }) {
                image(value["image"])?.let { return it }
            }
            return value.values.firstNotNullOfOrNull { find(it, depth + 1) }
        }
        for (script in Regex("<script\\b([^>]*)>(.*?)</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html)) {
            if (!script.groupValues[1].contains("application/ld+json", true)) continue
            val value = runCatching { Json.parseToJsonElement(script.groupValues[2]) }.getOrNull() ?: continue
            find(value)?.let { return it }
        }
    }
    val attributes = Regex("([\\w:-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL)
    for (tag in Regex("<(?:meta|img)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
        val attrs = attributes.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
        val raw = when {
            (attrs["property"] ?: attrs["name"]).equals("og:image", true) -> attrs["content"]
            provider == "fdroid" && "package-icon" in attrs["class"].orEmpty().split(' ') -> attrs["src"]
            provider == "izzy" && "appicon" in attrs["class"].orEmpty().split(' ') -> attrs["src"]
            else -> null
        }
        safeStoreAppIconUrl(raw, provider)?.let { return it }
    }
    return null
}

internal fun safeStoreAppIconUrl(raw: String?, provider: String): String? = runCatching {
    if (raw.isNullOrBlank() || raw.length > 4096) return null
    val base = when (provider) {
        "fdroid" -> "https://f-droid.org/"
        "izzy" -> "https://apt.izzysoft.de/"
        "play" -> "https://play.google.com/"
        else -> return null
    }
    val url = URI(base).resolve(raw.trim().replace("&amp;", "&"))
    val host = url.host?.lowercase() ?: return null
    val allowed = when (provider) {
        "fdroid" -> host == "f-droid.org"
        "izzy" -> host == "apt.izzysoft.de" || host == "repo.izzyondroid.org"
        else -> host == "googleusercontent.com" || host.endsWith(".googleusercontent.com")
    }
    if (!allowed || !url.scheme.equals("https", true) || url.userInfo != null || url.port !in setOf(-1, 443)) return null
    url.toASCIIString()
}.getOrNull()
