package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import com.cabin.CabinManager
import com.cabin.platform.ClimateNoticeMode
import com.cabin.platform.TeyesAirflowMode
import com.cabin.platform.TeyesClimateState

/** Copy follows observed connection state, never guesses a permission or phone error. */
internal data class ProjectionConnectionPresentation(
    val title: String,
    val nextStep: String,
    val stage: Int,
    val busy: Boolean,
)

internal fun projectionConnectionPresentation(resources: android.content.res.Resources, state: CabinManager.State): ProjectionConnectionPresentation =
    when (state) {
        CabinManager.State.DISCONNECTED ->
            ProjectionConnectionPresentation(
                resources.getString(R.string.hub_connection_ready),
                resources.getString(R.string.connection_plug_detail),
                0,
                false,
            )
        CabinManager.State.CONNECTING ->
            ProjectionConnectionPresentation(
                resources.getString(R.string.connection_preparing),
                resources.getString(R.string.connection_waiting_detail),
                0,
                true,
            )
        CabinManager.State.DEVICE_CONNECTED ->
            ProjectionConnectionPresentation(
                resources.getString(R.string.hub_connection_connected),
                resources.getString(R.string.connection_starting_detail),
                1,
                true,
            )
        CabinManager.State.STREAMING ->
            ProjectionConnectionPresentation(resources.getString(R.string.hub_connection_projection_ready), resources.getString(R.string.connection_ready_detail), 2, false)
    }

/** Retain projection space without making climate controls shorter than their tap targets. */
internal fun climatePanelHeightDp(
    availableHeightDp: Float,
    visible: Boolean,
): Float {
    if (!visible || !availableHeightDp.isFinite() || availableHeightDp <= 0f) return 0f
    val projectionReserve = if (availableHeightDp >= 240f) 64f else (availableHeightDp * 0.15f).coerceAtMost(24f)
    return (availableHeightDp * 0.48f).coerceIn(200f, 244f).coerceAtMost(availableHeightDp - projectionReserve)
}

internal data class ProjectionClimateNoticeVisibility(val panel: Boolean, val summary: Boolean)

/** A changed climate reading cannot expand the video-resizing panel in Summary/Off mode. */
internal fun projectionClimateNoticeVisibility(
    mode: ClimateNoticeMode,
    panelAlreadyVisible: Boolean,
): ProjectionClimateNoticeVisibility =
    ProjectionClimateNoticeVisibility(
        panel = panelAlreadyVisible || mode == ClimateNoticeMode.PANEL,
        summary = !panelAlreadyVisible && mode == ClimateNoticeMode.SUMMARY,
    )

/** Exactly one known combination can be highlighted; contradictory telemetry selects none. */
internal fun selectedClimateAirflow(state: TeyesClimateState): TeyesAirflowMode? =
    when {
        state.blowBody && !state.blowFoot && !state.blowUp -> TeyesAirflowMode.BODY
        state.blowBody && state.blowFoot && !state.blowUp -> TeyesAirflowMode.BODY_FOOT
        !state.blowBody && state.blowFoot && !state.blowUp -> TeyesAirflowMode.FOOT
        !state.blowBody && state.blowFoot && state.blowUp -> TeyesAirflowMode.UP_FOOT
        else -> null
    }
