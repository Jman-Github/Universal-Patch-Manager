import org.gradle.api.Project
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

@CacheableTransform
abstract class PatcherClassMergerCompatTransform : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        val affected = setOf(
            "morphe-patcher-2.0.1.jar",
            "morphe-patcher-2.1.0.jar",
            "revanced-patcher-19.3.1.jar",
            "revanced-patcher-21.1.0-dev.5.jar",
            "patcher-jvm-22.0.2-dev.1.jar"
        )
        when {
            input.name in affected ->
                ExtensionClassMergerFix.patchJar(input, outputs.file(input.name))
            // The pinned patcher-android 22.0.2-dev.1 publishes this unversioned AAR name.
            input.name == "patcher.aar" ->
                ExtensionClassMergerFix.patchAar(input, outputs.file(input.name))
            else -> outputs.file(input)
        }
    }
}

/** Android D8 inputs and isolated JVM hosts must consume the same corrected SDKs. */
fun Project.usePatcherClassMergerCompatibility() {
    val compatible = Attribute.of("app.upm.patcher-merger-compatible", Boolean::class.javaObjectType)
    val archiveKind = Attribute.of("app.upm.patcher-archive-kind", String::class.java)
    dependencies.attributesSchema.attribute(compatible)
    dependencies.attributesSchema.attribute(archiveKind)
    for (type in listOf("jar", "aar")) {
        dependencies.artifactTypes.maybeCreate(type).attributes.apply {
            attribute(compatible, false)
            attribute(archiveKind, type)
        }
        dependencies.registerTransform(PatcherClassMergerCompatTransform::class.java) {
            // Retain the original archive kind through AGP's AAR extraction.
            // Otherwise both AAR-before-extraction and JAR-after-extraction match.
            from.attribute(ARTIFACT_TYPE_ATTRIBUTE, type).attribute(compatible, false).attribute(archiveKind, type)
            to.attribute(ARTIFACT_TYPE_ATTRIBUTE, type).attribute(compatible, true).attribute(archiveKind, type)
        }
    }
    configurations.configureEach {
        if (name.endsWith("CompileClasspath", ignoreCase = true) ||
            name.endsWith("RuntimeClasspath", ignoreCase = true)) {
            attributes.attribute(compatible, true)
        }
    }
}
