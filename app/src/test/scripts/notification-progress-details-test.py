"""Run the existing Android notification probe with the percentage-only detail removed."""
from pathlib import Path

source_path = Path(__file__).with_name("notification-ui-test.py")
source = source_path.read_text(encoding="utf-8")
# The notification artwork was renamed since the original fixture was written.
source = source.replace('"drawable/ic_notification_status.png"', '"drawable/ic_notification_status.png", "drawable/ic_notification_artwork.xml"')
source = source.replace(
    '''            it.singleOrNull()?.notification?.extras?.getInt(Notification.EXTRA_PROGRESS) == 250 ||
                it.singleOrNull()?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "25%"''',
    '''            val content = it.singleOrNull()?.notification?.contentView?.apply(app, null)
            content?.findViewById<ProgressBar>(R.id.notification_progress)?.progress == 250 &&
                content.findViewById<TextView>(R.id.notification_percentage).text.toString() == "25%" &&
                content.findViewById<TextView>(R.id.notification_detail).text.isNullOrEmpty()''')
source = source.replace(
    "    Locale.setDefault(Locale.US)",
    '''    Locale.setDefault(Locale.US)
    for (detail in listOf("25%", " 25 % ", "25.5%")) {
        val builder = Notification.Builder(targetContext, "probe")
            .applyProgressNotification(targetContext, "Download", detail, 100, 25, false)
        val root = builder.createContentView().apply(targetContext, null)
        check(root.findViewById<TextView>(R.id.notification_detail).text.isNullOrEmpty())
        check(root.findViewById<TextView>(R.id.notification_percentage).text.toString() == "25%")
    }''')
exec(compile(source, str(source_path), "exec"), {"__file__": str(source_path), "__name__": "__main__"})
