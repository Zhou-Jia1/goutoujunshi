package com.shengjidaguai.goutoujunshi.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    @Test fun calmScalesEveryMotionAndRemoveAnimationsWins() {
        assertEquals(JunshiMotion(150, 250, 400, 4000, 120), JunshiMotion.resolve(false, false))
        assertEquals(JunshiMotion(225, 375, 600, 6000, 180), JunshiMotion.resolve(true, false))
        for (calm in listOf(false, true)) assertEquals(JunshiMotion(0, 0, 0, 0, 0), JunshiMotion.resolve(calm, true))
    }
}
