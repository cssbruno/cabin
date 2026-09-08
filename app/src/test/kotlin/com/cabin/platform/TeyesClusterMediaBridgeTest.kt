package com.cabin.platform

import com.cabin.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Test

class TeyesClusterMediaBridgeTest {
    @Test
    fun `cluster text is whitespace-normalized and capped at 15 code points`() {
        assertEquals("A long song tit", fitHondaClusterText("  A long   song title  "))
    }

    @Test
    fun `cluster text does not split surrogate pairs`() {
        assertEquals("12345678901234🎵", fitHondaClusterText("12345678901234🎵tail"))
    }

    @Test
    fun `null metadata becomes an empty cluster field`() {
        assertEquals("", fitHondaClusterText(null))
    }

    @Test
    fun `line breaks and control whitespace cannot corrupt a cluster field`() {
        assertEquals("Song Artist", fitHondaClusterText("Song\n\tArtist"))
    }

    @Test
    fun `bidi overrides and null controls are removed from cluster fields`() {
        assertEquals("SafeTitle", fitHondaClusterText("Safe\u202ETitle\u0000"))
    }

    @Test
    fun `navigation is formatted into bounded Honda fields`() {
        val text =
            formatTeyesNavigation(
                NavigationState(
                    status = 1,
                    maneuverType = 1,
                    orderType = 6,
                    turnSide = 1,
                    roadName = "Avenida Beira Mar Fortaleza",
                    remainDistance = 1280,
                    timeToDestination = 754,
                ),
            )

        assertEquals("TURN LEFT", text.maneuver)
        assertEquals("Avenida Beira M", text.road)
        assertEquals("1.3 km | 13 min", text.progress)
    }

    @Test
    fun `roundabout exit is clamped to supported profile range`() {
        val text =
            formatTeyesNavigation(
                NavigationState(status = 1, maneuverType = 7, orderType = 14, roundaboutExit = 99),
            )

        assertEquals("EXIT 19", text.maneuver)
    }

    @Test
    fun `CarPlay instructions work without Android Auto orderType`() {
        val maneuvers = mapOf(
            1 to "TURN LEFT",
            2 to "TURN RIGHT",
            12 to "ARRIVED",
            49 to "SLIGHT LEFT",
            53 to "KEEP RIGHT",
        )
        for ((type, expected) in maneuvers) {
            assertEquals(expected, formatTeyesNavigation(NavigationState(status = 1, maneuverType = type)).maneuver)
        }
    }

    @Test
    fun `CarPlay roundabout exit is encoded in maneuverType`() {
        assertEquals("EXIT 3", formatTeyesNavigation(NavigationState(status = 1, maneuverType = 30)).maneuver)
    }

    @Test
    fun `normalized Android Auto roundabout overrides lossy straight orderType`() {
        val state = NavigationState(status = 1, maneuverType = 30, orderType = 16, roundaboutExit = 3)
        assertEquals("EXIT 3", formatTeyesNavigation(state).maneuver)
    }
}
