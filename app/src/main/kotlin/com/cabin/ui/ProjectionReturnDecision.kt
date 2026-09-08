package com.cabin.ui

import com.cabin.CabinManager

internal enum class ProjectionReturnDecision { WAIT, RETURN, CANCEL }

/** One recent, explicit Hub Connect can return to video. Never navigates after leaving the Hub. */
internal fun projectionReturnDecision(
    requestedAtMs: Long?,
    nowMs: Long,
    state: CabinManager.State,
    eligible: Boolean,
): ProjectionReturnDecision {
    if (requestedAtMs == null) return ProjectionReturnDecision.WAIT
    if (!eligible || nowMs < requestedAtMs || nowMs - requestedAtMs > 120_000L) return ProjectionReturnDecision.CANCEL
    return if (state == CabinManager.State.STREAMING) ProjectionReturnDecision.RETURN else ProjectionReturnDecision.WAIT
}
