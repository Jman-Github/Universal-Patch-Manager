"""Inflate the production notification layouts and exercise progress on an Android emulator.

Runs before assembleRelease. Installs and removes an isolated test APK;
does not install, modify, or post notifications from the manager app.
"""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[4]
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("compat", Path(__file__).with_name("urv-import-compat-test.py"))
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
artifact = compat.artifact
SDK = Path(os.environ["ANDROID_HOME"])
JAVA_BIN = Path(r"C:\Program Files\Java\jdk-17\bin")
ANDROID = next((SDK / "platforms").glob("android-37*/android.jar"))
TOOLS = SDK / "build-tools/36.1.0"
ADB = [SDK / "platform-tools/adb.exe", "-s", os.environ.get("ANDROID_SERIAL", "emulator-5554")]
PACKAGE = "app.universal.patch.manager.notificationprobe"
BASE = ROOT / "app/src/main"
def run(*args):
    subprocess.run(list(map(str, args)), check=True, timeout=120)
def cp(paths):
    return os.pathsep.join(map(str, paths))

kotlin = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["kotlin"]
stdlib = artifact("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
compiler = [artifact("org.jetbrains.kotlin", n, kotlin) for n in
    ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime")]
compiler += [artifact("org.jetbrains", "annotations"), artifact("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm")]
with tempfile.TemporaryDirectory(prefix="notification-ui-", dir=ROOT / "build") as directory:
    work = Path(directory)
    resources = work / "res"
    for rel in ("layout/notification_progress.xml", "layout/notification_progress_expanded.xml",
                "drawable/notification_progress_track.xml", "values/notification_colors.xml",
                "values-night/notification_colors.xml", "drawable/ic_notification_status.png"):
        dst = resources / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(BASE / "res" / rel, dst)
    (resources / "values/probe_strings.xml").write_text("<resources><string name='download_progress_notification_channel'>Downloads</string><string name='download_progress_indeterminate'>Downloading</string></resources>")
    manifest = work / "AndroidManifest.xml"
    manifest.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/><uses-permission android:name="android.permission.POST_NOTIFICATIONS"/><application android:label="UPM notification probe" /><instrumentation android:name="probe.Probe" android:targetPackage="{PACKAGE}"/></manifest>')
    run(TOOLS / "aapt2.exe", "compile", "--dir", resources, "-o", work / "resources.zip")
    run(TOOLS / "aapt2.exe", "link", "-I", ANDROID, "--manifest", manifest,
        "--custom-package", "app.universal.patch.manager", "--java", work / "java",
        "-o", work / "probe.apk", work / "resources.zip")
    activity = work / "MainActivity.kt"
    activity.write_text("package app.universal.patch.manager\nclass MainActivity : android.app.Activity()")
    permission = work / "Permission.kt"
    permission.write_text("package app.universal.patch.manager.util.permission\nfun android.content.Context.hasNotificationPermission() = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED")
    classes = work / "classes"
    classes.mkdir()
    run(JAVA_BIN / "javac.exe", "-d", classes, *list((work / "java").rglob("*.java")))
    source = work / "Probe.kt"
    source.write_text("""
package probe
import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.widget.ProgressBar
import android.widget.TextView
import android.view.View
import app.universal.patch.manager.R
import app.universal.patch.manager.util.applyProgressNotification
import app.universal.patch.manager.util.applyManagerNotificationIcon
import app.universal.patch.manager.util.DownloadProgressNotifier
import java.util.Locale
class Probe : android.app.Instrumentation() {
override fun onCreate(arguments: android.os.Bundle?) { super.onCreate(arguments); start() }
override fun onStart() {
    var failure: Throwable? = null
    runOnMainSync { try { verify() } catch (error: Throwable) { failure = error } }
    if (failure == null) try { verifyDownloads() } catch (error: Throwable) { failure = error }
    val result = android.os.Bundle().apply {
        putString("stream", failure?.stackTraceToString() ?: "PASS: light/dark layouts, minimal artwork, background progress, completion/failure/cancellation removal")
    }
    finish(if (failure == null) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED, result)
}
private fun verifyDownloads() {
    val app = android.app.Application::class.java.cast(
        targetContext.applicationContext)!!
    val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    val notifier = DownloadProgressNotifier(app)
    fun awaitNotification(predicate: (List<android.service.notification.StatusBarNotification>) -> Boolean) {
        repeat(50) {
            if (predicate(manager.activeNotifications.toList())) return
            Thread.sleep(100)
        }
        error("Notification state did not converge")
    }
    val session = notifier.begin("Bundle download")
    try {
        awaitNotification { it.size == 1 }
        Thread.sleep(1100)
        session.update(250, 1000)
        awaitNotification {
            it.singleOrNull()?.notification?.extras?.getInt(Notification.EXTRA_PROGRESS) == 250 ||
                it.singleOrNull()?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "25%"
        }
        // Complete immediately after an update: removal must work even if replacement
        // notifications are rate-limited by Android.
        session.complete()
        awaitNotification { it.isEmpty() }
        session.update(900, 1000)
        Thread.sleep(200)
        check(manager.activeNotifications.isEmpty()) { "Closed session posted again" }
        notifier.begin("Failure").fail()
        awaitNotification { it.isEmpty() }
        notifier.begin("Cancel").cancel()
        awaitNotification { it.isEmpty() }
    } finally {
        session.cancel()
        notifier.clearStaleNotifications()
    }
}
private fun verify() {
    Locale.setDefault(Locale.US)
    val app = targetContext
    for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
        val configuration = Configuration(app.resources.configuration).apply { uiMode = night }
        val context = app.createConfigurationContext(configuration)
        for ((max, current, unknown, expected) in listOf(
            listOf(100, 0, 0, 0), listOf(200, 50, 0, 25),
            listOf(100, 200, 0, 100), listOf(1000, 999, 0, 99), listOf(100, -10, 0, 0),
            listOf(0, 0, 0, -1), listOf(100, 50, 1, -1))) {
            val builder = Notification.Builder(context, "probe")
                .applyManagerNotificationIcon(context)
                .setContentTitle("Patching").setContentText("Applying patches")
                .applyProgressNotification(context, "Patching", "Applying patches", max, current, unknown != 0)
            check(builder.build().getLargeIcon() != null) { "Minimal artwork is missing" }
            for (views in listOf(builder.createContentView(), builder.createBigContentView())) {
                val root = views.apply(context, null)
                fun visibleBars(view: View): Int {
                    if (view.visibility != View.VISIBLE) return 0
                    if (view is ProgressBar) return 1
                    if (view !is android.view.ViewGroup) return 0
                    return (0 until view.childCount).sumOf { visibleBars(view.getChildAt(it)) }
                }
                check(visibleBars(root) == 1) { "Expected one progress bar, got " + visibleBars(root) }
                val title = root.findViewById<TextView>(R.id.notification_title)
                check(title.text.toString() == "Patching")
                val detail = root.findViewById<TextView>(R.id.notification_detail)
                check(detail.text.toString() == "Applying patches")
                val percent = root.findViewById<TextView>(R.id.notification_percentage)
                val bar = root.findViewById<ProgressBar>(R.id.notification_progress)
                if (expected < 0) {
                    check(percent.visibility == View.GONE)
                    check(bar.isIndeterminate)
                } else {
                    check(percent.text.toString() == "$expected%")
                    check(percent.visibility == View.VISIBLE)
                    check(!bar.isIndeterminate)
                    check(bar.progress == current.coerceIn(0, max))
                    val track = bar.progressDrawable as android.graphics.drawable.LayerDrawable
                    val fill = track.findDrawableByLayerId(android.R.id.progress)
                    check(fill.level == (bar.progress.toDouble() * 10000 / max).toInt())
                }
                root.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
                check(root.measuredHeight > 0)
            }
        }
    }
}
}
""")
    run(JAVA_BIN / "java.exe", "-cp", cp(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", cp([ANDROID, classes, stdlib]),
        "-d", classes, source, activity, permission,
        *[BASE / "java/app/universal/patch/manager/util" / name for name in
          ("ProgressNotification.kt", "ManagerNotificationIcon.kt", "DownloadProgressNotifier.kt")])
    jar = work / "probe.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        for path in classes.rglob("*.class"):
            archive.write(path, path.relative_to(classes).as_posix())
    dex = work / "dex"
    dex.mkdir()
    run(JAVA_BIN / "java.exe", "-cp", artifact("com.android.tools", "r8"),
        "com.android.tools.r8.D8", "--min-api", "26", "--lib", ANDROID, "--output", dex, jar, stdlib)
    with zipfile.ZipFile(work / "probe.apk", "a") as archive:
        archive.write(dex / "classes.dex", "classes.dex")
    run(JAVA_BIN / "keytool.exe", "-genkeypair", "-keystore", work / "key.jks",
        "-storepass", "android", "-keypass", "android", "-alias", "probe", "-keyalg", "RSA",
        "-dname", "CN=Notification probe", "-validity", "1")
    run(JAVA_BIN / "java.exe", "-jar", TOOLS / "lib/apksigner.jar", "sign",
        "--ks", work / "key.jks", "--ks-pass", "pass:android", work / "probe.apk")
    installed = False
    try:
        run(*ADB, "install", "--no-streaming", work / "probe.apk")
        installed = True
        run(*ADB, "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        output = subprocess.check_output(list(map(str, ADB + ["shell", "am", "instrument", "-w", "-r", PACKAGE + "/probe.Probe"])), text=True, timeout=120)
        print(output)
        if "INSTRUMENTATION_CODE: -1" not in output or "PASS:" not in output:
            raise AssertionError("Notification layout instrumentation failed")
    finally:
        if installed:
            run(*ADB, "uninstall", PACKAGE)
