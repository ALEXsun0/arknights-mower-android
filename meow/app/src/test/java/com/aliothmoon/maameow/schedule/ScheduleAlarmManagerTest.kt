package com.aliothmoon.maameow.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.aliothmoon.maameow.schedule.model.ScheduleStrategy
import com.aliothmoon.maameow.schedule.model.ScheduleType
import com.aliothmoon.maameow.schedule.service.ScheduleAlarmManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleAlarmManagerTest {
    private val platformAlarms = mockk<AlarmManager>(relaxed = true)
    private val context = mockk<Context> {
        every { getSystemService(Context.ALARM_SERVICE) } returns platformAlarms
    }
    private val alarms = spyk(ScheduleAlarmManager(context))
    private val strategy = ScheduleStrategy(
        id = "daily", name = "Daily", profileId = "profile-1",
        scheduleType = ScheduleType.INTERVAL,
        startTimeMs = System.currentTimeMillis() + 3_600_000L,
        intervalMinutes = 60,
    )

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun fixedTimeInNextMinuteRegistersAtScheduledTime() {
        val now = ZonedDateTime.of(2026, 9, 9, 12, 0, 45, 0, ZoneId.systemDefault())
        val scheduled = now.plusMinutes(1).withSecond(0)
        mockkStatic(ZonedDateTime::class)
        every { ZonedDateTime.now(any<ZoneId>()) } returns now
        prepareRegistration()

        assertTrue(alarms.scheduleNext(strategy.copy(
            scheduleType = ScheduleType.FIXED_TIME,
            daysOfWeek = setOf(scheduled.dayOfWeek),
            executionTimes = listOf(scheduled.toLocalTime()),
        )))

        verify(exactly = 1) {
            platformAlarms.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, scheduled.toInstant().toEpochMilli(), any(),
            )
            anyConstructed<Intent>().putExtra(
                ScheduleAlarmManager.EXTRA_SCHEDULED_TIME, scheduled.toInstant().toEpochMilli(),
            )
        }
    }

    @Test
    fun intervalStartingSoonRegistersAtScheduledTime() {
        val scheduledTime = System.currentTimeMillis() + 15_000L
        prepareRegistration()

        assertTrue(alarms.scheduleNext(strategy.copy(startTimeMs = scheduledTime)))

        verify(exactly = 1) {
            platformAlarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, scheduledTime, any())
            anyConstructed<Intent>().putExtra(ScheduleAlarmManager.EXTRA_SCHEDULED_TIME, scheduledTime)
        }
    }

    private fun prepareRegistration() {
        every { alarms.canScheduleExact() } returns true
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setClassName(any<Context>(), any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk()
    }

    @Test
    fun missingPermissionDoesNotAttemptAlarmClockFallback() {
        every { alarms.canScheduleExact() } returns false
        assertFalse(alarms.scheduleNext(strategy))
        assertFalse(alarms.scheduleRetry(strategy.id, 123L))
        verify(exactly = 0) { platformAlarms.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { platformAlarms.setAlarmClock(any(), any()) }
    }

    @Test
    fun revokedPermissionDuringRegistrationDoesNotCrash_andGrantAllowsRescheduling() {
        every { alarms.canScheduleExact() } returns true
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setClassName(any<Context>(), any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk()
        every { platformAlarms.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("revoked")
        assertFalse(alarms.scheduleNext(strategy))
        every { platformAlarms.setExactAndAllowWhileIdle(any(), any(), any()) } returns Unit
        assertTrue(alarms.scheduleNext(strategy))
        verify(exactly = 0) { platformAlarms.setAlarmClock(any(), any()) }
    }

    @Test
    fun retryIsCappedAndCarriesIncrementedCount() {
        every { alarms.canScheduleExact() } returns true
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setClassName(any<Context>(), any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getBroadcast(any(), any(), any(), any()) } returns mockk()

        assertTrue(alarms.scheduleRetry(strategy.id, 123L, retryCount = 0))
        verify(exactly = 1) {
            anyConstructed<Intent>().putExtra(ScheduleAlarmManager.EXTRA_RETRY_COUNT, 1)
        }
        assertTrue(alarms.scheduleRetry(strategy.id, 123L, retryCount = ScheduleAlarmManager.MAX_RETRY_COUNT - 1))
        assertFalse(alarms.scheduleRetry(strategy.id, 123L, retryCount = ScheduleAlarmManager.MAX_RETRY_COUNT))
        verify(exactly = 2) { platformAlarms.setExactAndAllowWhileIdle(any(), any(), any()) }
    }
}
