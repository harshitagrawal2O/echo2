package com.fersaiyan.cyanbridge.ai.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityMathTest {

    private fun frame(width: Int, height: Int, pixel: (x: Int, y: Int) -> Int): IntArray =
        IntArray(width * height) { i -> pixel(i % width, i / width) }

    private fun gray(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun aBlackFrameIsTooDarkAndAMidGrayFrameIsNot() {
        val black = frame(32, 32) { _, _ -> gray(0) }
        val mid = frame(32, 32) { _, _ -> gray(96) }

        assertTrue(ImageQualityMath.isTooDark(ImageQualityMath.meanLuma(black)))
        assertFalse(ImageQualityMath.isTooDark(ImageQualityMath.meanLuma(mid)))
    }

    @Test
    fun aCoveredLensFrameIsRefusedButTwilightPassesThrough() {
        // A finger over the lens leaks a little light; well under the threshold.
        val covered = frame(32, 32) { _, _ -> gray(6) }
        // A dim-but-real scene sits above it; the gate must not eat usable photos.
        val twilight = frame(32, 32) { _, _ -> gray(30) }

        assertTrue(ImageQualityMath.isTooDark(ImageQualityMath.meanLuma(covered)))
        assertFalse(ImageQualityMath.isTooDark(ImageQualityMath.meanLuma(twilight)))
    }

    @Test
    fun sharpEdgesScoreFarAboveFlatAndSmoothFrames() {
        val size = 32
        // Checkerboard: hard edges everywhere, the sharpest thing a frame can contain.
        val checkerboard = frame(size, size) { x, y -> gray(if ((x + y) % 2 == 0) 0 else 255) }
        // Flat: no detail at all, the degenerate blur case.
        val flat = frame(size, size) { _, _ -> gray(128) }
        // Horizontal gradient: brightness varies but with no edges, like heavy motion blur.
        val gradient = frame(size, size) { x, _ -> gray(x * 255 / (size - 1)) }

        val sharp = ImageQualityMath.laplacianSharpness(checkerboard, size, size)
        val none = ImageQualityMath.laplacianSharpness(flat, size, size)
        val soft = ImageQualityMath.laplacianSharpness(gradient, size, size)

        assertEquals(0.0, none, 1e-9)
        assertTrue("gradient ($soft) should be near-flat", soft < ImageQualityMath.RETAKE_SHARPNESS_THRESHOLD)
        assertTrue("checkerboard ($sharp) should clear the retake threshold by orders of magnitude", sharp > 1_000)

        assertTrue(ImageQualityMath.shouldRetakeForBlur(none))
        assertTrue(ImageQualityMath.shouldRetakeForBlur(soft))
        assertFalse(ImageQualityMath.shouldRetakeForBlur(sharp))
    }

    @Test
    fun degenerateInputsDoNotThrow() {
        assertEquals(0.0, ImageQualityMath.meanLuma(IntArray(0)), 1e-9)
        assertEquals(0.0, ImageQualityMath.laplacianSharpness(IntArray(4), 2, 2), 1e-9)
    }
}
