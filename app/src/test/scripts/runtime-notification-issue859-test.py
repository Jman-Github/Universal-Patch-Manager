"""Exercise issue #859 against production notification code on the Android probe."""
from pathlib import Path
import xml.etree.ElementTree as ET

source_path = Path(__file__).with_name("notification-ui-test.py")
root = source_path.resolve().parents[4]
base = root / "app/src/main"
source = source_path.read_text(encoding="utf-8")
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
exec(compile(source, str(source_path), "exec"), {"__file__": str(source_path), "__name__": "__main__"})
