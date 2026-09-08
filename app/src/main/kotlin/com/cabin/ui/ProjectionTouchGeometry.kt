package com.cabin.ui

/**
 * Normalize one surface-local touch axis without treating letterbox padding as crop.
 *
 * An oversized AA surface retains the existing positive center-crop subtraction and
 * surface-size denominator: its visible axis spans 0..(containerExtent/surfaceExtent).
 * A fitted CarPlay surface is smaller than its container, but MotionEvent coordinates
 * already start at that surface's edge; subtracting a negative crop would shift all taps.
 * Out-of-view gesture positions are preserved for the existing transport to handle.
 */
internal fun normalizeProjectionTouchCoordinate(
    surfaceLocalPosition: Float,
    surfaceExtent: Int,
    containerExtent: Int,
): Float {
    if (surfaceExtent <= 0 || containerExtent <= 0 || !surfaceLocalPosition.isFinite()) return 0f
    val cropOffset = (surfaceExtent - containerExtent).coerceAtLeast(0) / 2f
    return (surfaceLocalPosition - cropOffset) / surfaceExtent
}
