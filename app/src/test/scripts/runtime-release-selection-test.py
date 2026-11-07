"""Exercise production catalog resolution with deterministic GitHub/device/HTTP fixtures."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]
CACHE = Path(os.environ.get("GRADLE_USER_HOME", ROOT / ".gradle-user-home")) / "caches/modules-2/files-2.1"
JAVA = Path(os.environ.get("JAVA_HOME", r"C:\Program Files\Java\jdk-17")) / "bin/java.exe"

def artifact(group, name, version="*"):
    return max((CACHE / group / name).glob(version + "/*/*.jar"), key=lambda p: p.stat().st_mtime)

def cp(files):
    return os.pathsep.join(map(str, files))

compiler = [artifact("org.jetbrains.kotlin", name, "2.4.10") for name in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
             artifact("org.jetbrains", "annotations", "13.0")]
dependencies = [artifact("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.10"),
                artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.10.2"),
                artifact("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm"),
                artifact("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm")]
fixtures = {
    "Application.kt": """
package android.app
class Application {
    val filesDir = java.io.File(System.getProperty("java.io.tmpdir"))
    val assets = Assets()
    fun getString(id: Int, vararg args: Any): String = "No compatible runtime release: " + args.joinToString()
}
class Assets { fun open(name: String): java.io.InputStream = error("Unexpected asset access: $name") }
""",
    "R.kt": """
package app.universal.patch.manager
object R { object string { const val runtime_catalog_no_compatible_release = 1 } }
""",
    "Device.kt": """
package android.os
object Build {
    val SUPPORTED_64_BIT_ABIS = arrayOf("arm64-v8a")
    val SUPPORTED_32_BIT_ABIS = arrayOf("armeabi-v7a")
    object VERSION { const val SDK_INT = 26 }
}
object Process { fun is64Bit() = true }
""",
    "Request.kt": """
package io.ktor.client.request
class HttpRequestBuilder { var location = "" }
fun HttpRequestBuilder.url(value: String) { location = value }
""",
    "Loaded.kt": """
package app.universal.patch.manager.network.runtime
class LoadedPatcherRuntimePlugin(val packageName: String, val kind: PatcherRuntimeKind)
""",
    "API.kt": """
package app.universal.patch.manager.network.api
import app.universal.patch.manager.network.dto.GitHubRelease
class Response<T>(val data: T)
fun <T> Response<T>.successOrThrow(label: String): T = data
class UpmAPI(var releases: List<GitHubRelease>) {
    suspend fun getRepositoryReleaseByTag(repoUrl: String, tag: String): Response<GitHubRelease> =
        Response(releases.first { it.tagName == tag })
    suspend fun getRepositoryReleaseHistory(repoUrl: String, prerelease: Boolean? = null,
        limit: Int = 20, tagPrefix: String? = null): Response<List<GitHubRelease>> =
        Response(releases.filter {
            !it.draft && (prerelease == null || it.prerelease == prerelease) &&
                (tagPrefix == null || it.tagName.startsWith(tagPrefix))
        }.take(limit))
}
""",
    "Http.kt": """
package app.universal.patch.manager.network.service
import app.universal.patch.manager.network.api.Response
import app.universal.patch.manager.network.runtime.RuntimeReleaseManifest
import io.ktor.client.request.HttpRequestBuilder
class HttpService(val metadata: MutableMap<String, RuntimeReleaseManifest>) {
    suspend inline fun <reified T> request(builder: HttpRequestBuilder.() -> Unit): Response<T> {
        val url = HttpRequestBuilder().apply(builder).location
        @Suppress("UNCHECKED_CAST")
        return Response(metadata.getValue(url) as T)
    }
}
""",
    "Main.kt": """
import android.app.Application
import app.universal.patch.manager.domain.repository.RuntimeCatalogRepository
import app.universal.patch.manager.network.api.UpmAPI
import app.universal.patch.manager.network.dto.*
import app.universal.patch.manager.network.runtime.*
import app.universal.patch.manager.network.service.HttpService
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val item = RuntimeCatalogItem("morphe", "morphe", "Morphe", "", "app.universal.patch.manager.morphe.runtime",
        "https://github.com/example/runtimes", "morphe.apk")
    fun release(tag: String, pre: Boolean) = GitHubRelease(tag, prerelease = pre, assets = listOf(
        GitHubAsset(item.assetName, "https://apk/$tag"), GitHubAsset("runtime-release.json", "https://metadata/$tag")))
    val stable = release("morphe-v3.0", false)
    val pre = release("morphe-v2.1-beta", true)
    val api = UpmAPI(listOf(stable, pre))
    val metadata = api.releases.associate { release ->
        "https://metadata/" + release.tagName to RuntimeReleaseManifest(
            1, item.id, item.kind, item.packageName, item.assetName, release.tagName, 1, "upstream",
            "a".repeat(64), 100, 1, 1)
    }.toMutableMap()
    val repository = RuntimeCatalogRepository(Application(), api, HttpService(metadata))
    val base = PatcherRuntimePluginSourceEntry("source", item.repoUrl, "morphe", catalogId = item.id)
    suspend fun selected(entry: PatcherRuntimePluginSourceEntry) =
        repository.resolve(item, entry.releasePrereleaseFilter).release.tagName
    check(repository.resolve(item).release.tagName == stable.tagName)
    check(selected(base) == stable.tagName)
    check(selected(base.copy(prerelease = true)) == pre.tagName) {
        "Prerelease-only mode unexpectedly selected a stable release"
    }
    check(selected(base.copy(latest = true)) == stable.tagName)
    check(selected(base.copy(prerelease = true, latest = true)) == stable.tagName)
    api.releases = listOf(pre, stable)
    check(repository.resolve(item).release.tagName == pre.tagName) {
        "New catalog imports must default to the latest channel, including prereleases"
    }
    check(selected(base) == stable.tagName) {
        "Saved stable-only settings must remain stable-only"
    }
    check(selected(base.copy(latest = true)) == pre.tagName)
    metadata["https://metadata/" + pre.tagName] =
        metadata.getValue("https://metadata/" + pre.tagName).copy(minHostProtocol = 2, maxHostProtocol = 2)
    check(runCatching { selected(base.copy(prerelease = true)) }.isFailure) {
        "Incompatible prereleases must not fall back to another channel"
    }
    println("PASS: stable, prerelease-only, latest in either order, default import and incompatible-channel isolation")
}
"""
}
production = [
    "domain/repository/RuntimeCatalogRepository.kt",
    "network/dto/GitHubRelease.kt",
    "network/runtime/RuntimeCatalog.kt",
    "network/runtime/RuntimeCatalogCache.kt",
    "network/runtime/PatcherRuntimePluginState.kt",
    "network/runtime/PatcherRuntimeKind.kt",
    "patcher/patch/PatchBundleType.kt"]
(ROOT / "build").mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="runtime-release-selection-", dir=ROOT / "build") as temporary:
    work = Path(temporary)
    sources = []
    for name, content in fixtures.items():
        source = work / name
        source.write_text(content)
        sources.append(source)
    sources += [ROOT / "app/src/main/java/app/universal/patch/manager" / path for path in production]
    classes = work / "classes"
    subprocess.run([str(JAVA), "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp(dependencies),
        "-d", str(classes), *map(str, sources)], check=True, timeout=90)
    subprocess.run([str(JAVA), "-cp", cp([classes] + dependencies), "MainKt"], check=True, timeout=30)
