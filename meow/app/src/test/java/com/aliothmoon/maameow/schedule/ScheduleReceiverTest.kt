package com.aliothmoon.maameow.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.aliothmoon.maameow.schedule.data.ScheduleStrategyRepository
import com.aliothmoon.maameow.schedule.model.ScheduleStrategy
import com.aliothmoon.maameow.schedule.receiver.ScheduleReceiver
import com.aliothmoon.maameow.schedule.service.ScheduleAlarmManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.IOException

class ScheduleReceiverTest {
    @Test
    fun rejectedServiceRearmsBeforeWritingResult_andReleasesHandoffLock() = runBlocking {
        val strategy = ScheduleStrategy(id = "daily", name = "Daily", profileId = "profile-1")
        val repository = mockk<ScheduleStrategyRepository>()
        coEvery { repository.getById(strategy.id) } returns strategy
        coEvery { repository.recordExecutionResult(any(), any(), any(), any()) } throws IOException("disk full")
        val alarms = mockk<ScheduleAlarmManager>(relaxed = true)
        startKoin {
            modules(module {
                single { repository }
                single { alarms }
            })
        }
        try {
            val lock = mockk<PowerManager.WakeLock>(relaxed = true) {
                every { isHeld } returns true
            }
            val power = mockk<PowerManager> {
                every { newWakeLock(any(), any()) } returns lock
            }
            val context = mockk<Context> {
                every { getSystemService(Context.POWER_SERVICE) } returns power
                every { getString(any(), *anyVararg()) } returns "service start failed"
            }
            val incoming = mockk<Intent> {
                every { action } returns ScheduleAlarmManager.ACTION_SCHEDULE_TRIGGER
                every { getStringExtra(ScheduleAlarmManager.EXTRA_STRATEGY_ID) } returns strategy.id
                every { getLongExtra(ScheduleAlarmManager.EXTRA_SCHEDULED_TIME, 0L) } returns 123L
                every { getIntExtra(ScheduleAlarmManager.EXTRA_RETRY_COUNT, 0) } returns 0
            }
            mockkConstructor(Intent::class)
            every { anyConstructed<Intent>().setClassName(any<Context>(), any()) } answers { self as Intent }
            every { anyConstructed<Intent>().setAction(any()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<Long>()) } answers { self as Intent }
            every { anyConstructed<Intent>().putExtra(any<String>(), any<Int>()) } answers { self as Intent }
            mockkStatic(ContextCompat::class)
            every { ContextCompat.startForegroundService(context, any()) } throws SecurityException("denied")
            val finished = CompletableDeferred<Unit>()
            val pending = mockk<BroadcastReceiver.PendingResult> {
                every { finish() } answers { finished.complete(Unit) }
            }
            val receiver = spyk(ScheduleReceiver())
            every { receiver.goAsync() } returns pending
            receiver.onReceive(context, incoming)
            withTimeout(2_000L) { finished.await() }
            verify(exactly = 1) { alarms.scheduleNext(strategy, 123L) }
            verify(exactly = 0) { alarms.scheduleRetry(any(), any(), any()) }
            coVerify(exactly = 1) { repository.recordExecutionResult(strategy.id, any(), "service start failed", any()) }
            verify(exactly = 1) { lock.release() }
        } finally {
            stopKoin()
            unmockkAll()
        }
    }
}
