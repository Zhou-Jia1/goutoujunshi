package com.shengjidaguai.goutoujunshi.floating

import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T33CopyCollapseTest {
    @Test fun ordinaryCopyCollapsesOnlyAfterOneSecond()=runTest {
        val timer=FloatingCopyCollapse(this);var folded=0
        timer.copied({true}) {folded++};runCurrent()
        advanceTimeBy(999);runCurrent();assertEquals(0,folded)
        advanceTimeBy(1);runCurrent();assertEquals(1,folded)
    }
    @Test fun newInteractionWithinSameWindowCannotCollapseOrCancelB()=runTest {
        val timer=FloatingCopyCollapse(this);var owned="A";var canceled:String?=null
        timer.copied({true}) {canceled=owned};runCurrent();advanceTimeBy(500)
        timer.newInteraction();owned="B"
        advanceTimeBy(1000);runCurrent();assertNull("Old A copy must not close the same panel or cancel B",canceled)
    }
    @Test fun changedControllerEpochCannotCollapseEvenWithoutPointerEvent()=runTest {
        val timer=FloatingCopyCollapse(this);var epoch=1L;var folded=0
        val copiedEpoch=epoch;timer.copied({epoch==copiedEpoch}) {folded++};runCurrent()
        epoch++;advanceTimeBy(1000);runCurrent();assertEquals(0,folded)
    }
    @Test fun newCopyRestartsTimerAndCloseRevokesIt()=runTest {
        val timer=FloatingCopyCollapse(this);var folded=0
        timer.copied({true}) {folded++};runCurrent();advanceTimeBy(700)
        timer.copied({true}) {folded++};runCurrent();advanceTimeBy(600);runCurrent();assertEquals(0,folded)
        timer.close();advanceTimeBy(1000);runCurrent();assertEquals(0,folded)
    }
}
