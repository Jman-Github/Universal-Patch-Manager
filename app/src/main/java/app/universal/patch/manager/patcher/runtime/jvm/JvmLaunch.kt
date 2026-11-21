/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.patcher.runtime.jvm

// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212
// https://github.com/MorpheApp/morphe-manager/pull/1474

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Launch data for a disposable embedded JVM or ART worker in [PatcherService].
 *
 * @param command The glibc loader and JVM executable. Empty for ART.
 * @param mainClass The JVM host's fully qualified class name.
 */
@Parcelize
data class JvmLaunch(
    val command: List<String>,
    val options: List<String>,
    val mainClass: String,
    val entryClass: String,
    val runtimeClassPath: String? = null,
    val nativeLibraryDir: String? = null
) : Parcelable
