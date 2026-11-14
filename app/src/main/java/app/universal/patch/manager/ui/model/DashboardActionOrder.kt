package app.universal.patch.manager.ui.model

internal fun dashboardActionOrder(stored: String, defaults: List<String>, hidden: Set<String>, group: String): List<String> =
    (stored.split(',').filter { it in defaults }.distinct() + defaults)
        .distinct().filterNot { "$group:$it" in hidden }
