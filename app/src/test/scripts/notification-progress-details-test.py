"""Check percentage-only text against the production progress notification."""
from pathlib import Path

source_path = Path(__file__).with_name("notification-ui-test.py")
source = source_path.read_text(encoding="utf-8")
source = source.replace(
    "    Locale.setDefault(Locale.US)",
    '''    Locale.setDefault(Locale.US)
    for (detail in listOf("25%", " 25 % ", "25.5%", "２５％", "٢٥٪", "٢٥\\u061c٪")) {
        val notification = Notification.Builder(targetContext, "probe")
            .applyProgressNotification("Download", detail, 100, 25, false)
            .build()
        check(notification.extras.getCharSequence(Notification.EXTRA_TEXT) == null)
        check(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "25% · Download")
    }
    try {
        for ((locale, appName, rtl) in listOf(
            Triple(Locale.US, "يوتيوب", false),
            Triple(Locale.forLanguageTag("he-IL"), "YouTube", true),
            Triple(Locale.forLanguageTag("ar-SA"), "YouTube", true)
        )) {
            Locale.setDefault(locale)
            val title = Notification.Builder(targetContext, "probe")
                .applyProgressNotification(appName, null, 100, 25, false)
                .build().extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            val readingOrder = java.text.Bidi(title, java.text.Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
            check(readingOrder.baseIsLeftToRight() != rtl) { "Wrong title direction for $locale" }
            check(readingOrder.getLevelAt(title.indexOf(appName)) % 2 == if (rtl) 0 else 1) {
                "Wrong app name direction for $locale"
            }
            val percent = java.text.NumberFormat.getPercentInstance(locale).format(0.25)
            check(title.contains(percent))
        }
    } finally {
        Locale.setDefault(Locale.US)
    }''', 1)
exec(compile(source, str(source_path), "exec"), {"__file__": str(source_path), "__name__": "__main__"})
