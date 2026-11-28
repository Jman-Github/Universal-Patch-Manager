package app.universal.patch.manager.domain.repository

import java.io.File
import java.nio.file.Files

/** Only remove inactive managed archives and their adjacent ART compilation artifacts. */
internal fun pruneRuntimeArchives(directory: File, active: File, retained: Set<File>) {
    if (Files.isSymbolicLink(directory.toPath())) return
    val root = directory.canonicalFile
    val current = active.canonicalFile
    if (current.parentFile != root || !current.isFile) return
    val protected = retained.map { it.canonicalFile }.toSet() + current
    directory.listFiles().orEmpty().forEach { file ->
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile ||
            file.canonicalFile in protected ||
            !(file.name == "patcher-runtime.apk" || file.name.matches(Regex("[a-f0-9]{64}\\.apk")))) return@forEach
        file.delete()
    }
    val protectedNames = protected.filter { it.parentFile == root }.map { it.nameWithoutExtension }.toSet()
    val oat = directory.resolve("oat")
    if (Files.isSymbolicLink(oat.toPath())) return
    val artifactDirectories = listOf(oat) + oat.listFiles().orEmpty()
        .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
    artifactDirectories.forEach { folder ->
        folder.listFiles().orEmpty().filter {
            it.isFile && !Files.isSymbolicLink(it.toPath()) &&
                it.extension in setOf("odex", "vdex", "art") &&
                it.nameWithoutExtension !in protectedNames &&
                (it.nameWithoutExtension == "patcher-runtime" || it.nameWithoutExtension.matches(Regex("[a-f0-9]{64}"))) &&
                !directory.resolve(it.nameWithoutExtension + ".apk").exists()
        }.forEach { it.delete() }
    }
}

/** Retired source folders are private runtime data, never arbitrary paths from a backup. */
internal fun pruneOrphanRuntimeDirectories(root: File, sourceIds: Set<String>, retained: Set<File>) {
    val canonicalRoot = root.canonicalFile
    val protected = retained.map { it.canonicalFile.toPath() }
    root.listFiles().orEmpty().filter { directory ->
        directory.name.matches(Regex("[a-zA-Z0-9-]+")) && directory.name !in sourceIds &&
            !Files.isSymbolicLink(directory.toPath()) && directory.isDirectory &&
            directory.canonicalFile.parentFile == canonicalRoot &&
            protected.none { it.startsWith(directory.canonicalFile.toPath()) }
    }.forEach { it.deleteRecursively() }
}
