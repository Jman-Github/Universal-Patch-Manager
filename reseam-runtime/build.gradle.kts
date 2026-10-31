import kotlin.random.Random
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.parcelize)
}

val reseamSdkVersion = "0.35.1"
val apkEditorLib by configurations.creating

val strippedApkEditorLib by tasks.registering(Jar::class) {
    archiveFileName.set("APKEditor-android.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    doFirst {
        from(apkEditorLib.resolve().map { zipTree(it) })
    }
    exclude(
        "android/**",
        "org/xmlpull/**",
        "antlr/**",
        "org/antlr/**",
        "com/beust/jcommander/**",
        "javax/annotation/**",
        "smali.properties",
        "baksmali.properties"
    )
}

android {
    namespace = "app.universal.patch.manager.reseam.runtime"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.universal.patch.manager.reseam.runtime"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.4.0"
        buildConfigField("String", "RESEAM_SDK_VERSION", "\"$reseamSdkVersion\"")
    }

    val keystoreFile = rootProject.file("app/keystore.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keystoreEntryAlias = System.getenv("KEYSTORE_ENTRY_ALIAS")
    val keystoreEntryPassword = System.getenv("KEYSTORE_ENTRY_PASSWORD")
    val hasReleaseSigningCredentials = keystoreFile.exists() &&
        !keystorePassword.isNullOrBlank() &&
        !keystoreEntryAlias.isNullOrBlank() &&
        !keystoreEntryPassword.isNullOrBlank()
    val signAsDebug = project.hasProperty("signAsDebug")
    if (System.getenv("CI").toBoolean() && !signAsDebug && !hasReleaseSigningCredentials) {
        throw GradleException(
            "Release signing credentials are required in CI. Set KEYSTORE_PASSWORD, " +
                "KEYSTORE_ENTRY_ALIAS, and KEYSTORE_ENTRY_PASSWORD, or pass -PsignAsDebug for debug signing."
        )
    }
    val releaseSigningConfig = if (signAsDebug || !hasReleaseSigningCredentials) {
        signingConfigs.getByName("debug")
    } else {
        signingConfigs.maybeCreate("release").apply {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keystoreEntryAlias
            keyPassword = keystoreEntryPassword
        }
    }

    buildTypes {
        debug {
            buildConfigField("long", "BUILD_ID", "${Random.nextLong()}L")
            signingConfig = releaseSigningConfig
        }
        release {
            buildConfigField("long", "BUILD_ID", "0L")
            signingConfig = releaseSigningConfig
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    sourceSets.getByName("main").kotlin.directories.add(rootProject.file("shared/merger/src/main/java").path)
    sourceSets.getByName("main").kotlin.directories.add(rootProject.file("shared/runtime-plugin/src/main/java").path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("reseam-runtime.apk")
        }
    }
}

dependencies {
    implementation("app.reseam:reseam-sdk:$reseamSdkVersion") {
        exclude(group = "xmlpull", module = "xmlpull")
        exclude(group = "xpp3", module = "xpp3")
    }
    implementation(libs.apksig)
    implementation("com.android.tools.build:apkzlib:8.5.2")
    compileOnly("com.google.guava:guava:32.1.2-jre")
    implementation(libs.xpp3)
    apkEditorLib(files("$rootDir/libs/APKEditor-1.4.7.jar"))
    implementation(files(strippedApkEditorLib))
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.kotlinx.coroutines.android)
    testImplementation("junit:junit:4.13.2")

}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
