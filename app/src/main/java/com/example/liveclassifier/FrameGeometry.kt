package com.example.liveclassifier

/** Pixel bounds, independent of Android so edge cases can be tested on the build server. */
internal data class SquareCrop(val left: Int, val top: Int, val size: Int)

internal object FrameGeometry {
    fun centerSquare(
        frameWidth: Int,
        frameHeight: Int,
        cropLeft: Int = 0,
        cropTop: Int = 0,
        cropRight: Int = frameWidth,
        cropBottom: Int = frameHeight,
    ): SquareCrop {
        require(frameWidth > 0 && frameHeight > 0) { "Empty camera image" }
        val left = cropLeft.coerceIn(0, frameWidth)
        val top = cropTop.coerceIn(0, frameHeight)
        val right = cropRight.coerceIn(0, frameWidth)
        val bottom = cropBottom.coerceIn(0, frameHeight)
        require(right > left && bottom > top) { "Empty camera crop" }
        val size = minOf(right - left, bottom - top)
        return SquareCrop(left + (right - left - size) / 2, top + (bottom - top - size) / 2, size)
    }
}
