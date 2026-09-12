package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class GameActivityStateTest {
    private val pkg = "com.hypergryph.arknights"
    private fun dump(state: String = "RESUMED", app: String = "ProcessRecord{abc 1234:$pkg/u0a316}",
                     visible: Boolean = true, finishing: Boolean = false) = """
Display #21 (activities from top to bottom):
  * Task{abc #5788 type=standard}
    mLastPausedActivity: ActivityRecord{abc u0 $pkg/.Game}
    * Hist  #0: ActivityRecord{abc u0 $pkg/.Game}
      app=$app
      state=$state stopped=false finishing=$finishing
      mVisibleRequested=$visible mVisible=$visible
"""
    private fun parse(value: String) = GameActivityState.parse(value, pkg)
    @Test fun actualResumedActivityIsReady() {
        val state = parse(dump()).single()
        assertEquals(21, state.displayId)
        assertEquals(5788, state.rootTaskId)
        assertTrue(state.resumed)
    }
    @Test fun deadFinishingAndDestroyedActivitiesAreNotPresent() {
        assertFalse(parse(dump(app = "null")).single().present)
        assertFalse(parse(dump(finishing = true)).single().present)
        assertFalse(parse(dump(state = "DESTROYED")).single().present)
    }
    @Test fun pausedActivityHasPlacementButMustBeResumedBeforeLaunchSucceeds() {
        val state = parse(dump(state = "STOPPED", visible = false)).single()
        assertTrue(state.present)
        assertFalse(state.resumed)
    }
    @Test fun summariesAndOtherDisplaysCannotTurnStoppedActivityIntoReady() {
        val state = parse(dump(state = "STOPPED", visible = false) + """
  Resumed activities in task display areas (from top to bottom):
    Resumed: ActivityRecord{old u0 $pkg/.Game}
Display #0 (activities from top to bottom):
  * Task{def #12 type=standard}
    * Hist  #0: ActivityRecord{def u0 example.other/.Main}
      app=ProcessRecord{def 111:example.other/u0a100}
      state=RESUMED stopped=false finishing=false
      mVisible=true
""").single()
        assertEquals(21, state.displayId)
        assertFalse(state.resumed)
        assertTrue(parse("Display #21\n    mLastPausedActivity: ActivityRecord{old u0 $pkg/.Game}").isEmpty())
    }
    @Test fun missingStateCannotClaimSuccess() {
        assertFalse(parse(dump().replace("state=RESUMED", "unknown=RESUMED")).single().present)
    }
    @Test fun exactPackageAndRootTaskAreUsed() {
        assertTrue(parse(dump().replace(pkg, "$pkg.extra")).isEmpty())
        assertEquals(99, parse(dump().replace("#5788 type", "#5788 rootTaskId=99 type")).single().rootTaskId)
    }
}
