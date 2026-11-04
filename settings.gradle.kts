import java.util.Properties

val localProps = Properties().apply {
    val file = rootDir.resolve("local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun githubUser(): String? =
    localProps.getProperty("gpr.user")
        ?: providers.gradleProperty("gpr.user").orNull
        ?: System.getenv("GITHUB_ACTOR")

fun githubToken(): String? =
    localProps.getProperty("gpr.key")
        ?: providers.gradleProperty("gpr.key").orNull
        ?: System.getenv("GITHUB_TOKEN")

pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        ivy {
            // Standalone D8 builds are published as versioned JARs in the official R8 bucket.
            name = "R8 releases"
            url = uri("https://storage.googleapis.com/r8-releases/raw")
            patternLayout { artifact("[revision]/[artifact].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.android.tools", "r8") }
        }
        maven {
            // From PR #39: https://github.com/Jman-Github/Universal-Patch-Manager/pull/39
            url = uri("https://maven.pkg.github.com/brosssh/registry")
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // A repository must be specified for some reason. "registry" is a dummy.
            url = uri("https://maven.pkg.github.com/revanced/registry")
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // Morphe library is published to its repository-specific GitHub Packages endpoint.
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-library")
            content {
                includeModule("app.morphe", "morphe-library")
            }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // Morphe publishes multiple runtime artifacts under the app.morphe group from this endpoint.
            url = uri("https://maven.pkg.github.com/MorpheApp/morphe-patcher")
            content {
                includeGroup("app.morphe")
            }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven {
            // Morphe multidexlib2 is also published to its own GitHub Packages endpoint.
            url = uri("https://maven.pkg.github.com/MorpheApp/multidexlib2")
            content {
                includeModule("app.morphe", "multidexlib2")
            }
            credentials {
                val gprUser: String? = githubUser()
                val gprKey: String? = githubToken()

                username = gprUser.orEmpty().ifBlank { "anonymous" }
                password = gprKey.orEmpty()
            }
        }
        maven("https://git.reseam.app/api/packages/reseam/maven") {
            content { includeGroup("app.reseam") }
        }
        maven("https://jitpack.io") {
            metadataSources {
                mavenPom()
                artifact()
            }
        }
    }
}

rootProject.name = "universal-patch-manager"
include(":app", ":api")
