// Code adapted from Morphe, see third-party/NOTICE for more information.
// https://github.com/MorpheApp/morphe-manager/pull/1202
// https://github.com/MorpheApp/morphe-manager/pull/1212

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.universal.patch.manager.patcher.runtime.jvm;

import app.universal.patch.manager.patcher.runtime.jvm.JvmLaunch;

interface IPatcherService {
    // Reserves this one-shot worker and returns the service PID.
    int prepare(in JvmLaunch launch, in String[] bundles);

    // Starts the run and returns the PID of the process doing the patching.
    int run(String requestFile, in ParcelFileDescriptor events);
}
