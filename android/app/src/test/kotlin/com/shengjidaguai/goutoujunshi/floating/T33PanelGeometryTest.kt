package com.shengjidaguai.goutoujunshi.floating
import org.junit.Assert.*
import org.junit.Test
class T33PanelGeometryTest {
    @Test fun dragNeverCoversMoreThanFiftyFivePercent() {
        assertEquals(550, FloatingPanelGeometry.height(1000, 900))
        assertEquals(320, FloatingPanelGeometry.height(1000, 320))
    }
    @Test fun smallestHeightStillHasReachableHandle() {
        assertEquals(200, FloatingPanelGeometry.height(1000, -500))
    }
}
