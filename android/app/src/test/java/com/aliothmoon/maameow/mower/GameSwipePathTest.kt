package com.aliothmoon.maameow.mower

import org.junit.Assert.*
import org.junit.Test

class GameSwipePathTest {
    @Test fun mowerOperatorListLongSwipeKeepsSpeedAndFinalNoInertiaSegment() {
        val path = GameSwipePath(listOf(650 to 540, 650 to 580, 3150 to 580, 3150 to 540))
        assertEquals(670 to 580, path.at(1, 1, 125))
        assertEquals(1919 to 580, path.at(1, 125, 125))
        assertEquals(1919 to 560, path.at(2, 1, 2))
        assertEquals(1919 to 540, path.end)
    }
    @Test fun negativeAndVerticalOvershootStayInsideTheDisplay() {
        val path = GameSwipePath(listOf(600 to 500, -1900 to -2000, 600 to 500))
        assertEquals(0 to 0, path.at(0, 1, 1))
        assertEquals(600 to 500, path.end)
        assertEquals(600 to 1079, GameSwipePath(listOf(600 to 500, 600 to 3000)).end)
    }
    @Test fun validCoordinatesStayUnchangedAndExtremeEndpointsDoNotOverflow() {
        assertEquals(200 to 300, GameSwipePath(listOf(100 to 200, 300 to 400)).at(0, 1, 2))
        val path = GameSwipePath(listOf(0 to 0, Int.MAX_VALUE to Int.MAX_VALUE, Int.MIN_VALUE to Int.MIN_VALUE))
        assertEquals(1919 to 1079, path.at(1, 0, 100))
        assertEquals(0 to 0, path.end)
    }
    @Test fun invalidStartingPointIsStillRejected() {
        assertThrows(IllegalArgumentException::class.java) { GameSwipePath(listOf(-1 to 500, 600 to 500)) }
        assertThrows(IllegalArgumentException::class.java) { GameSwipePath(listOf(1920 to 500, 600 to 500)) }
    }
}
