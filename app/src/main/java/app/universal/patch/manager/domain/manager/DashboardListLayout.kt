package app.universal.patch.manager.domain.manager

/** How the Apps and Patch Bundles tabs lay out their entries. */
enum class DashboardListLayout {
    /** Full cards with details and quick actions. */
    CARDS,

    /** One short row per entry, so many fit on screen at once. */
    COMPACT,

    /** Icon tiles in a grid. */
    GRID;

    companion object {
        /** The layout saved under [name], or null when this version doesn't have it. */
        fun fromName(name: String): DashboardListLayout? = entries.firstOrNull { it.name == name }
    }
}
