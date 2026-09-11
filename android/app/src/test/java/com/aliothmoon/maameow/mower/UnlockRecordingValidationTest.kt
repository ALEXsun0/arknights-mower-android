package com.aliothmoon.maameow.mower
import com.aliothmoon.maameow.domain.models.*
import org.junit.Assert.*
import org.junit.Test
class UnlockRecordingValidationTest {
    private val recording = UnlockGesture(screenWidth=1080, screenHeight=2400, rotation=0, steps=listOf(UnlockStep.Tap(50,60)))
    @Test fun roundTripPreservesRecording() { assertEquals(recording, UnlockGesture.parseOrNull(recording.toJson().toString())) }
    @Test fun mismatchedFormatIsRejected() { assertNull(UnlockGesture.parseOrNull(recording.toJson().put("version",2).toString())) }
    @Test fun outsideScreenIsRejected() { assertNull(UnlockGesture.parseOrNull(recording.copy(steps=listOf(UnlockStep.Tap(-1,0))).toJson().toString())) }
    @Test fun oversizedHoldIsRejected() { assertNull(UnlockGesture.parseOrNull(recording.copy(steps=listOf(UnlockStep.LongPress(0,0,100000))).toJson().toString())) }
    @Test fun backwardsTimestampsAreRejected() {
        val steps = listOf(UnlockStep.Swipe(listOf(GesturePoint(0,0,5),GesturePoint(1,1,2))))
        assertNull(UnlockGesture.parseOrNull(recording.copy(steps=steps).toJson().toString()))
    }
    @Test fun unknownStepsAreRejected() {
        val json = recording.toJson(); json.getJSONArray("steps").getJSONObject(0).put("type","unknown")
        assertNull(UnlockGesture.parseOrNull(json.toString()))
    }
}
