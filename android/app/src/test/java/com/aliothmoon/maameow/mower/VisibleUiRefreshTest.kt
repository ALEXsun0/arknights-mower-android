package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class VisibleUiRefreshTest {
    private class Queue {
        val pending = mutableListOf<Pair<Runnable, Long>>()
        var refreshes = 0
        lateinit var loop: VisibleUiRefresh
        init {
            loop = VisibleUiRefresh(
                schedule = { task, delay -> pending += task to delay },
                cancel = { task -> pending.removeAll { it.first === task } },
                refresh = { refreshes++ },
            )
        }
        fun tick() { pending.removeAt(0).first.run() }
    }

    @Test fun hiddenOrDestroyedPageHasNoRefreshesOrQueuedWork() {
        val q = Queue()
        assertTrue(q.pending.isEmpty())
        q.loop.start(); q.tick()
        assertEquals(1, q.refreshes)
        val stale = q.pending.single().first
        q.loop.stop()
        assertTrue(q.pending.isEmpty())
        stale.run()
        assertEquals(1, q.refreshes)
        assertTrue(q.pending.isEmpty())
    }

    @Test fun repeatedResumeAndForegroundSwitchesNeverDuplicateTheLoop() {
        val q = Queue()
        repeat(5) { q.loop.start() }
        assertEquals(1, q.pending.size)
        assertEquals(0L, q.pending.single().second)
        q.tick()
        assertEquals(1000L, q.pending.single().second)
        repeat(50) { q.loop.stop(); q.loop.start(); q.tick() }
        assertEquals(51, q.refreshes)
        assertEquals(1, q.pending.size)
    }

    @Test fun queuedCallbackFromPreviousVisibilityCannotReviveAfterResume() {
        val q = Queue()
        q.loop.start()
        val stale = q.pending.single().first
        q.loop.stop(); q.loop.start()
        stale.run()
        assertEquals(0, q.refreshes)
        assertEquals(1, q.pending.size)
        q.tick()
        assertEquals(1, q.refreshes)
        assertEquals(1, q.pending.size)
    }

    @Test fun pauseDuringRefreshDoesNotRequeue() {
        val pending = mutableListOf<Runnable>()
        lateinit var loop: VisibleUiRefresh
        loop = VisibleUiRefresh(
            schedule = { task, _ -> pending += task },
            cancel = { pending.remove(it) },
            refresh = { loop.stop() },
        )
        loop.start()
        pending.removeAt(0).run()
        assertTrue(pending.isEmpty())
    }
}
