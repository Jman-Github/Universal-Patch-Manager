import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Android requires a packaged adaptive icon and launcher component for each selectable design.
 * The variant setup follows Morphe's generator integration, credited in third-party/NOTICE.
 * https://github.com/MorpheApp/morphe-manager/blob/9bc1903a24704294492d6beed3f12eb6d3445d33/buildSrc/src/main/kotlin/GenerateLauncherIconsTask.kt
 */
@CacheableTask
abstract class GenerateManagerAppIcons : DefaultTask() {
    @get:OutputDirectory abstract val resourcesDir: DirectoryProperty
    @get:OutputDirectory abstract val sourcesDir: DirectoryProperty
    @get:OutputFile abstract val manifestFile: RegularFileProperty
    @get:Inject protected abstract val fileSystem: FileSystemOperations

    private data class Background(val id: String, val hex: String? = null) {
        val resource get() = if (hex != null) "color/manager_icon_$id" else "drawable/ic_manager_${id}_background"
    }
    private data class Foreground(val id: String, val drawable: String)
    private data class Design(
        val id: String,
        val background: String,
        val foreground: String,
        val customBackground: String? = null,
        val customForeground: String? = null
    ) {
        val resource get() = if (id == "default") "ic_launcher" else "ic_manager_$id"
        val component get() = "app.universal.patch.manager.launcher.$id"
        val entry get() = id.uppercase()
    }

    @TaskAction
    fun generate() {
        val backgrounds = listOf(
            Background("navy", "#102E46"),
            Background("teal", "#00685E"),
            Background("purple", "#6236A5"),
            Background("ruby", "#A52B50"),
            Background("amber", "#FFD27A"),
            Background("white", "#FFFFFF"),
            Background("black", "#000000"),
            Background("gradient")
        )
        val foregrounds = listOf(
            // The launcher alias also supplies the enlarged system splash icon.
            Foreground("original", "@drawable/ic_splash_foreground"),
            Foreground("white", "@drawable/ic_manager_mark_white"),
            Foreground("black", "@drawable/ic_manager_mark_black"),
            Foreground("gradient", "@drawable/ic_manager_gradient_foreground")
        )
        val designs = buildList {
            add(Design("default", "", ""))
            add(Design("light", "@android:color/white", "@drawable/ic_manager_mark_black"))
            add(Design("dark", "@android:color/black", "@drawable/ic_manager_mark_white"))
            add(Design("gradient", "@drawable/ic_manager_gradient_background", "@drawable/ic_manager_gradient_foreground"))
            backgrounds.forEach { background ->
                foregrounds.filterNot { background.hex != null && it.id == background.id }.forEach { foreground ->
                    add(
                        Design(
                            "custom_${background.id}_${foreground.id}",
                            "@${background.resource}",
                            foreground.drawable,
                            background.id,
                            foreground.id
                        )
                    )
                }
            }
        }

        val res = resourcesDir.get().asFile
        fileSystem.delete { delete(res) }
        val mipmap = res.resolve("mipmap-anydpi-v26").apply { mkdirs() }
        designs.filter { it.id != "default" }.forEach { design ->
            mipmap.resolve("${design.resource}.xml").writeText(
                """
                <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                    <background android:drawable="${design.background}" />
                    <foreground android:drawable="${design.foreground}" />
                    <monochrome android:drawable="@drawable/ic_manager_mark_white" />
                </adaptive-icon>
                """.trimIndent() + "\n"
            )
        }
        val values = res.resolve("values").apply { mkdirs() }
        values.resolve("manager_icon_colors.xml").writeText(buildString {
            appendLine("<resources>")
            backgrounds.filter { it.hex != null }.forEach {
                appendLine("    <color name=\"manager_icon_${it.id}\">${it.hex}</color>")
            }
            appendLine("</resources>")
        })

        manifestFile.get().asFile.apply { parentFile.mkdirs() }.writeText(buildString {
            appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android">""")
            appendLine("    <application>")
            designs.forEach { design ->
                appendLine("        <activity-alias")
                appendLine("            android:name=\"${design.component}\"")
                appendLine("            android:targetActivity=\"app.universal.patch.manager.MainActivity\"")
                appendLine("            android:icon=\"@mipmap/${design.resource}\"")
                appendLine("            android:enabled=\"${design.id == "default"}\"")
                appendLine("""            android:exported="true">""")
                appendLine("            <intent-filter>")
                appendLine("""                <action android:name="android.intent.action.MAIN" />""")
                appendLine("""                <category android:name="android.intent.category.LAUNCHER" />""")
                appendLine("            </intent-filter>")
                appendLine("        </activity-alias>")
            }
            appendLine("    </application>")
            appendLine("</manifest>")
        })

        val source = sourcesDir.get().asFile.resolve("app/universal/patch/manager/util")
        source.mkdirs()
        source.resolve("ManagerAppIcon.kt").writeText(buildString {
            appendLine("package app.universal.patch.manager.util")
            appendLine()
            appendLine("import androidx.annotation.AnyRes")
            appendLine("import androidx.annotation.DrawableRes")
            appendLine("import androidx.annotation.StringRes")
            appendLine("import app.universal.patch.manager.R")
            appendLine()
            appendLine("enum class ManagerIconBackground(@StringRes val label: Int, @AnyRes val swatch: Int) {")
            backgrounds.forEach {
                val swatch = it.resource.replace('/', '.')
                appendLine("    ${it.id.uppercase()}(R.string.manager_icon_color_${it.id}, R.$swatch),")
            }
            appendLine("}")
            appendLine()
            appendLine("enum class ManagerIconForeground(@StringRes val label: Int) {")
            foregrounds.forEach {
                appendLine("    ${it.id.uppercase()}(R.string.manager_icon_color_${it.id}),")
            }
            appendLine("}")
            appendLine()
            appendLine("enum class ManagerAppIcon(")
            appendLine("    val launcherClass: String,")
            appendLine("    @DrawableRes val icon: Int,")
            appendLine("    val background: ManagerIconBackground? = null,")
            appendLine("    val foreground: ManagerIconForeground? = null")
            appendLine(") {")
            designs.forEach {
                val custom = if (it.customBackground == null) "" else
                    ", ManagerIconBackground.${it.customBackground.uppercase()}, ManagerIconForeground.${it.customForeground!!.uppercase()}"
                appendLine("    ${it.entry}(\"${it.component}\", R.mipmap.${it.resource}$custom),")
            }
            appendLine("}")
        })
    }
}
