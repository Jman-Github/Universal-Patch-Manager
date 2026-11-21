package app.universal.patch.manager.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardActionOrderTest {
    private val defaults = listOf("storage", "add", "batch")

    @Test
    fun importedOrderRecoversMissingAndInvalidButtons() {
        assertEquals(
            listOf("batch", "add", "storage"),
            dashboardActionOrder("batch,unknown,add,batch", defaults, emptySet(), "apps")
        )
        assertEquals(defaults, dashboardActionOrder("", defaults, emptySet(), "apps"))
    }

    @Test
    fun allButtonsCanBeHiddenWithoutRestoringDefaults() {
        assertEquals(
            emptyList<String>(),
            dashboardActionOrder(
                "batch,storage,add", defaults,
                setOf("apps:storage", "apps:add", "apps:batch"), "apps"
            )
        )
    }

    @Test
    fun hiddenButtonsKeepTheirSavedPositionWhenShownAgain() {
        val stored = "batch,add,storage"
        assertEquals(
            listOf("batch", "storage"),
            dashboardActionOrder(stored, defaults, setOf("apps:add"), "apps")
        )
        assertEquals(
            listOf("batch", "add", "storage"),
            dashboardActionOrder(stored, defaults, emptySet(), "apps")
        )
    }

    @Test
    fun hidingAnAppsButtonDoesNotHideTheBundlesButton() {
        assertEquals(
            listOf("add", "discovery"),
            dashboardActionOrder("add,discovery", listOf("discovery", "add"), setOf("apps:add"), "bundles")
        )
    }
}
