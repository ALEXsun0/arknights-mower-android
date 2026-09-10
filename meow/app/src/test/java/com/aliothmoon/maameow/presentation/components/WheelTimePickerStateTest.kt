package com.aliothmoon.maameow.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelTimePickerStateTest {
    @Test
    fun initialWheelSelectionPreservesEveryHour() {
        for (hour in 0..23) {
            val state = WheelTimePickerState(hour, 41)

            assertEquals(hour >= 12, state.isPm)
            state.selectHour(state.startHourIndex + 1)

            assertEquals(hour, state.hour)
            assertEquals(41, state.minute)
        }
    }

    @Test
    fun twelveAmIsMidnightAndTwelvePmIsNoon() {
        val state = WheelTimePickerState(9, 30)
        state.selectHour(12)
        assertEquals(0, state.hour)

        state.selectPeriod(true)
        assertEquals(12, state.hour)

        state.selectPeriod(false)
        assertEquals(0, state.hour)
        assertEquals(30, state.minute)
    }

    @Test
    fun scrollingHoursPreservesSelectedPeriod() {
        val state = WheelTimePickerState(0, 59)
        state.selectPeriod(true)

        for (hour in 1..11) {
            state.selectHour(hour)
            assertEquals(hour + 12, state.hour)
        }
        state.selectHour(12)
        assertEquals(12, state.hour)
        assertEquals(59, state.minute)
    }

    @Test
    fun switchingPeriodPreservesClockHourAndMinute() {
        val state = WheelTimePickerState(23, 59)
        state.selectPeriod(false)
        assertEquals(11, state.hour)
        state.selectPeriod(false)
        assertEquals(11, state.hour)
        state.selectPeriod(true)
        assertEquals(23, state.hour)
        assertEquals(59, state.minute)
    }

    @Test
    fun initialValuesStayWithinValidTimeRange() {
        val early = WheelTimePickerState(-1, -1)
        early.selectHour(early.startHourIndex + 1)
        assertEquals(0, early.hour)
        assertEquals(0, early.minute)

        val late = WheelTimePickerState(24, 60)
        late.selectHour(late.startHourIndex + 1)
        assertEquals(23, late.hour)
        assertEquals(59, late.minute)
    }
}
