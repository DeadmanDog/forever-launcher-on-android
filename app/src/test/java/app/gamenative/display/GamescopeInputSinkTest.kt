package app.gamenative.display

import org.junit.Assert.assertEquals
import org.junit.Test

class GamescopeInputSinkTest {
    @Test fun absoluteGuestPositionsUseTheSupportedRelativeProtocol() {
        val events = mutableListOf<Pair<Int, Int>>()
        val sink = GamescopeInputSink(1344, 608) { dx, dy -> events.add(dx to dy) }
        sink.pointerMove(672, 304)
        sink.pointerMove(1343, 607)
        sink.pointerMove(1343, 607)
        sink.pointerMove(0, 0)
        assertEquals(listOf(-1344 to -608, 672 to 304, 671 to 303, -1343 to -607), events)
    }

    @Test fun relativeAndAbsoluteInputsShareTheSameGuestPosition() {
        val events = mutableListOf<Pair<Int, Int>>()
        val sink = GamescopeInputSink(1344, 608) { dx, dy -> events.add(dx to dy) }
        sink.pointerMove(600, 300)
        sink.pointerMoveDelta(40, -20)
        sink.pointerMove(640, 280)
        sink.pointerMoveDelta(0, 0)
        sink.pointerMove(641, 281)
        assertEquals(listOf(-1344 to -608, 600 to 300, 40 to -20, 1 to 1), events)
    }

    @Test fun movementIsNotMultipliedByThePhysicalAndroidSurfaceSize() {
        val events = mutableListOf<Pair<Int, Int>>()
        val sink = GamescopeInputSink(1344, 608) { dx, dy -> events.add(dx to dy) }
        sink.pointerMove(1343, 607)
        sink.pointerMoveDelta(1, 1)
        assertEquals(listOf(-1344 to -608, 1343 to 607, 1 to 1), events)
    }

    @Test fun overshootDoesNotOffsetTheNextAbsoluteTouch() {
        var seatX = 0
        var seatY = 0
        val sink = GamescopeInputSink(1344, 608) { dx, dy ->
            seatX = (seatX + dx).coerceIn(0, 1343)
            seatY = (seatY + dy).coerceIn(0, 607)
        }
        sink.pointerMove(1280, 500)
        sink.pointerMoveDelta(200, -900)
        sink.pointerMove(1320, 30)
        assertEquals(1320, seatX)
        assertEquals(30, seatY)
    }

    @Test fun theFirstTouchLandsWhereTappedWhereverTheSeatPointerStarted() {
        // Gamescope starts its pointer in the middle of the screen, not at (0, 0).
        var seatX = 672; var seatY = 304
        val sink = GamescopeInputSink(1344, 608) { dx, dy ->
            seatX = (seatX + dx).coerceIn(0, 1343)
            seatY = (seatY + dy).coerceIn(0, 607)
        }
        sink.pointerMove(100, 50)
        assertEquals(100 to 50, seatX to seatY)
        sink.pointerMove(1000, 500)
        assertEquals(1000 to 500, seatX to seatY)
    }
}
