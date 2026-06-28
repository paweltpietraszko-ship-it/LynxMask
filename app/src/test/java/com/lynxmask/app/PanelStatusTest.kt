package com.lynxmask.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelStatusTest {

    @Test
    fun greenWhenOnlyMaskedTokens_noGuardOrFlags() {
        assertEquals(
            PanelStatusKind.GREEN,
            resolvePanelStatus(hasRedHits = false, hasYellowAlerts = false)
        )
    }

    @Test
    fun yellowOnlyWhenActionableAlertsExist() {
        assertEquals(
            PanelStatusKind.YELLOW,
            resolvePanelStatus(hasRedHits = false, hasYellowAlerts = true)
        )
    }

    @Test
    fun redTakesPriorityOverYellow() {
        assertEquals(
            PanelStatusKind.RED,
            resolvePanelStatus(hasRedHits = true, hasYellowAlerts = true)
        )
    }

    @Test
    fun exportBlockedByRedFirst() {
        assertEquals(
            ExportBlockReason.RED_HITS,
            resolveExportBlockReason(hasRedHits = true, hasYellowAlerts = true, allFlagsHandled = false)
        )
    }

    @Test
    fun exportBlockedByYellowWhenNoRed() {
        assertEquals(
            ExportBlockReason.YELLOW_ALERTS,
            resolveExportBlockReason(hasRedHits = false, hasYellowAlerts = true, allFlagsHandled = true)
        )
    }

    @Test
    fun exportAllowedWhenAllHandled() {
        assertEquals(
            ExportBlockReason.NONE,
            resolveExportBlockReason(hasRedHits = false, hasYellowAlerts = false, allFlagsHandled = true)
        )
    }
}
