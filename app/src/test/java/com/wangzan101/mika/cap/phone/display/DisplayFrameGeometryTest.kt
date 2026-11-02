package com.wangzan101.mika.cap.phone.display

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayFrameGeometryTest {
    @Test fun portraitAndUpsideDownKeepLogicalDimensions() {
        for (rotation in listOf(0, 2)) {
            val geometry = DisplayFrameGeometry(720, 1600, rotation)
            assertEquals(720, geometry.width)
            assertEquals(1600, geometry.height)
            assertEquals(360 to 800, geometry.previewSize(360))
        }
    }

    @Test fun bothLandscapeDirectionsSwapDimensionsAndPreviewAspect() {
        for (rotation in listOf(1, 3)) {
            val geometry = DisplayFrameGeometry(720, 1600, rotation)
            assertEquals(1600, geometry.width)
            assertEquals(720, geometry.height)
            assertEquals(360 to 162, geometry.previewSize(360))
        }
    }

    @Test fun naturallyLandscapeDisplayCanRotateToPortrait() {
        assertEquals(360 to 800, DisplayFrameGeometry(1600, 720, 1).previewSize(360))
        assertEquals(360 to 162, DisplayFrameGeometry(1600, 720, 0).previewSize(360))
    }

    @Test fun previewDoesNotUpscaleSmallFrames() {
        assertEquals(80 to 36, DisplayFrameGeometry(36, 80, 3).previewSize(360))
    }
}
