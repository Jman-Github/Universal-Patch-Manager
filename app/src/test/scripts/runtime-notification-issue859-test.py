"""Exercise issue #859 against production notification code on the Android probe."""
from pathlib import Path
import xml.etree.ElementTree as ET

source_path = Path(__file__).with_name("notification-ui-test.py")
root = source_path.resolve().parents[4]
base = root / "app/src/main"
source = source_path.read_text(encoding="utf-8")
source = source.replace('"drawable/ic_notification_status.png"',
    '"drawable/ic_notification_status.png", "drawable/ic_notification_artwork.xml"')
resources = ET.parse(base / "res/values/strings.xml").getroot()
needed = {"runtime_updates_channel", "runtime_shared_support_updated",
          "runtime_updates_found", "runtime_updates_available_count",
          "runtime_updates_installed", "runtime_updates_installed_count"}
extra = "".join(ET.tostring(e, encoding="unicode") for e in resources
                if e.attrib.get("name") in needed)
source = source.replace('</resources>")',
    extra.replace(chr(34), chr(92) + chr(34)).replace(chr(10), chr(92) + "n") + '</resources>")', 1)
source = source.replace('    source = work / "Probe.kt"', '''
    runtime_source = work / "RuntimeUpdateNotifications.kt"
    runtime_source.write_text(
        (BASE / "java/app/universal/patch/manager/util/RuntimeUpdateNotifications.kt")
        .read_text(encoding="utf-8")
        .replace("import kotlinx.serialization.Serializable", "")
        .replace("@Serializable", ""), encoding="utf-8")
    source = work / "Probe.kt"''', 1)
source = source.replace('"-d", classes, source, activity, permission,',
                        '"-d", classes, source, activity, permission, runtime_source,', 1)
source = source.replace('import java.util.Locale', '''
import java.util.Locale
import app.universal.patch.manager.util.RuntimeUpdateNotifications
import app.universal.patch.manager.util.PendingRuntimeUpdate
''', 1)
source = source.replace("    Locale.setDefault(Locale.US)", """
    Locale.setDefault(Locale.US)
    for (detail in listOf("98%", " 98 % ", "98.5%", "９８％", "٩٨٪", "٩٨\\u061c٪")) {
        val builder = Notification.Builder(targetContext, "probe")
            .applyProgressNotification(targetContext, "Download", detail, 100, 98, false)
        for (expanded in listOf(false, true)) {
            val views = if (expanded) builder.createBigContentView() else builder.createContentView()
            val root = views.apply(targetContext, null)
            val left = root.findViewById<TextView>(R.id.notification_detail)
            check(left.text.isNullOrEmpty())
            check(left.visibility == if (expanded) View.GONE else View.INVISIBLE)
            check(root.findViewById<TextView>(R.id.notification_percentage).text.toString() == "98%")
        }
    }
    val manager = targetContext.getSystemService(android.app.NotificationManager::class.java)
    fun notice(): Notification {
        repeat(50) {
            manager.activeNotifications.firstOrNull { it.id == 9018 }?.let { return it.notification }
            Thread.sleep(100)
        }
        error("Completion notification was not posted")
    }
    val support = PendingRuntimeUpdate("support", null, null, "UPM runtime support", "v1", sharedSupport=true)
    val engine = PendingRuntimeUpdate("morphe", null, null, "Morphe", "v2")
    RuntimeUpdateNotifications.showCompleted(targetContext, listOf(support))
    var result = notice()
    check(result.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Runtime updated")
    check(result.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString() ==
        "The shared support runtime has been updated")
    manager.cancel(9018)
    RuntimeUpdateNotifications.showCompleted(targetContext, listOf(engine))
    result = notice()
    check(result.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Runtime updated")
    check(result.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Morphe v2"))
    manager.cancel(9018)
    RuntimeUpdateNotifications.showCompleted(targetContext, listOf(support, engine))
    result = notice()
    check(result.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "Runtimes updated")
    check(result.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Morphe v2"))
    manager.cancel(9018)
""", 1)
source = source.replace(
    'it.singleOrNull()?.notification?.extras?.getInt(Notification.EXTRA_PROGRESS) == 250 ||\n'
    '                it.singleOrNull()?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "25%"',
    '''val content = it.singleOrNull()?.notification?.contentView?.apply(app, null)
            content?.findViewById<ProgressBar>(R.id.notification_progress)?.progress == 250 &&
                content.findViewById<TextView>(R.id.notification_percentage).text.toString() == "25%"''')
exec(compile(source, str(source_path), "exec"), {"__file__": str(source_path), "__name__": "__main__"})
