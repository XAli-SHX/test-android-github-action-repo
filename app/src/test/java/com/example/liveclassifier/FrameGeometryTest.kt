package com.example.liveclassifier

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGeometryTest {
    @Test fun landscapeSensor() {
        assertEquals(SquareCrop(80, 0, 480), FrameGeometry.centerSquare(640, 480))
    }

    @Test fun portraitSensor() {
        assertEquals(SquareCrop(0, 80, 480), FrameGeometry.centerSquare(480, 640))
    }

    @Test fun respectsOffsetViewport() {
        assertEquals(SquareCrop(40, 10, 400), FrameGeometry.centerSquare(640, 480, 20, 10, 460, 410))
    }

    @Test fun oddSizesStayInsideImage() {
        assertEquals(SquareCrop(80, 0, 479), FrameGeometry.centerSquare(640, 479))
    }

    @Test fun clipsOutOfBoundsViewport() {
        assertEquals(SquareCrop(80, 0, 480), FrameGeometry.centerSquare(640, 480, -20, -10, 700, 500))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsEmptyImage() {
        FrameGeometry.centerSquare(0, 480)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidCrop() {
        FrameGeometry.centerSquare(640, 480, 200, 0, 100, 480)
    }
}
