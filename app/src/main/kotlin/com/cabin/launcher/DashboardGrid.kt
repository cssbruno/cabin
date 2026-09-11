package com.cabin.launcher

/** Persist one landscape layout; transpose its geometry for portrait without rotating content. */
internal data class DashboardGrid(val portrait: Boolean) {
    val columns = if (portrait) DASHBOARD_ROWS else DASHBOARD_COLUMNS
    val rows = if (portrait) DASHBOARD_COLUMNS else DASHBOARD_ROWS

    // Transposition is its own inverse, so edits round-trip to the saved layout.
    fun transform(tile: DashboardTile): DashboardTile = if (portrait) {
        tile.copy(x = tile.y, y = tile.x, width = tile.height, height = tile.width)
    } else tile

    fun storedX(x: Int, y: Int) = if (portrait) y else x
    fun storedY(x: Int, y: Int) = if (portrait) x else y
    fun storedWidth(width: Int, height: Int) = if (portrait) height else width
    fun storedHeight(width: Int, height: Int) = if (portrait) width else height
}
