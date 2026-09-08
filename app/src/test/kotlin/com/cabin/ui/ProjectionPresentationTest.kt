package com.cabin.ui

import com.cabin.CabinManager
import com.cabin.platform.ClimateNoticeMode
import com.cabin.platform.TeyesAirflowMode
import com.cabin.platform.TeyesClimateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], qualifiers = "en")
class ProjectionPresentationTest {
    private val testResources get() = org.robolectric.RuntimeEnvironment.getApplication().resources

    @Test
    fun `summary and off climate updates never allocate projection height`() {
        listOf(ClimateNoticeMode.SUMMARY, ClimateNoticeMode.OFF).forEach { mode ->
            val notice = projectionClimateNoticeVisibility(mode, panelAlreadyVisible = false)
            assertFalse(notice.panel)
            assertEquals(mode == ClimateNoticeMode.SUMMARY, notice.summary)
            assertEquals(0f, climatePanelHeightDp(480f, notice.panel), 0f)
        }
        assertTrue(projectionClimateNoticeVisibility(ClimateNoticeMode.PANEL, false).panel)
    }

    @Test
    fun `automatic updates keep a manually opened panel in every notice mode`() {
        ClimateNoticeMode.entries.forEach { mode ->
            val notice = projectionClimateNoticeVisibility(mode, panelAlreadyVisible = true)
            assertTrue(notice.panel)
            assertFalse(notice.summary)
        }
    }

    @Test
    fun `disconnected state gives one USB step without endless progress`() {
        val presentation = projectionConnectionPresentation(testResources, CabinManager.State.DISCONNECTED)
        assertFalse(presentation.busy)
        assertEquals(0, presentation.stage)
        assertTrue(presentation.nextStep.contains("USB"))
    }

    @Test
    fun `adapter ready explains that phone pairing comes next`() {
        val presentation = projectionConnectionPresentation(testResources, CabinManager.State.DEVICE_CONNECTED)
        assertTrue(presentation.busy)
        assertEquals(1, presentation.stage)
        assertTrue(presentation.nextStep.contains("phone"))
    }

    @Test
    fun `connection progress follows actual manager states`() {
        assertTrue(projectionConnectionPresentation(testResources, CabinManager.State.CONNECTING).busy)
        assertEquals(2, projectionConnectionPresentation(testResources, CabinManager.State.STREAMING).stage)
        assertFalse(projectionConnectionPresentation(testResources, CabinManager.State.STREAMING).busy)
    }

    @Test
    fun `climate height keeps projection space and respects the panel maximum`() {
        listOf(160f, 240f, 320f, 480f, 600f, 1080f).forEach { viewport ->
            val height = climatePanelHeightDp(viewport, true)
            assertTrue(height > 0)
            assertTrue(height < viewport)
            assertTrue(height <= 244f)
        }
    }

    @Test
    fun `compact viewport allocates enough space for header and a full height control`() {
        assertEquals(176f, climatePanelHeightDp(240f, true), 0f)
        assertTrue(climatePanelHeightDp(320f, true) >= 200f)
        // Header (56), panel padding (16), spacing (4) and one button (56).
        assertTrue(climatePanelHeightDp(240f, true) >= 56f + 16f + 4f + 56f)
    }

    @Test
    fun `hidden or invalid viewport never reserves climate height`() {
        assertEquals(0f, climatePanelHeightDp(600f, false), 0f)
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach {
            assertEquals(0f, climatePanelHeightDp(it, true), 0f)
        }
    }

    @Test
    fun `combined airflow does not also highlight individual modes`() {
        assertEquals(TeyesAirflowMode.BODY, selectedClimateAirflow(TeyesClimateState(blowBody = true)))
        assertEquals(TeyesAirflowMode.FOOT, selectedClimateAirflow(TeyesClimateState(blowFoot = true)))
        assertEquals(TeyesAirflowMode.BODY_FOOT, selectedClimateAirflow(TeyesClimateState(blowBody = true, blowFoot = true)))
        assertEquals(TeyesAirflowMode.UP_FOOT, selectedClimateAirflow(TeyesClimateState(blowUp = true, blowFoot = true)))
        assertNull(selectedClimateAirflow(TeyesClimateState()))
        assertNull(selectedClimateAirflow(TeyesClimateState(blowBody = true, blowFoot = true, blowUp = true)))
    }

    @Test
    fun `unavailable climate temperatures never imply zero degrees`() {
        assertEquals("—", formatClimateTemperature(null, false))
        assertEquals("—", formatClimateTemperature(-1, true))
        assertEquals("LOW", formatClimateTemperature(-2, false))
        assertEquals("HIGH", formatClimateTemperature(-3, false))
    }
}
