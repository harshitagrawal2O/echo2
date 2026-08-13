package com.fersaiyan.cyanbridge.ai.image

/**
 * Quality checks for photos taken by someone who cannot see the frame.
 *
 * The VizWiz dataset - photos taken by blind users asking real questions - found fewer than half
 * were legible or had the subject adequately in frame: darkness, blur, a finger over the lens.
 * The failure mode is not an error; it is a confidently wrong answer about whatever was actually
 * captured. These checks catch the two defects we can detect cheaply before uploading.
 *
 * Pure functions over ARGB pixel arrays so the thresholds can be unit-tested and tuned without a
 * device. Callers downscale first: the numbers below are calibrated for frames no wider than
 * [ANALYSIS_MAX_DIMENSION], and sharpness in particular is resolution-dependent.
 */
object ImageQualityMath {

    /** Analyse at thumbnail size: quality defects survive downscaling, latency does not. */
    const val ANALYSIS_MAX_DIMENSION = 256

    /**
     * Below this mean luma (0..255) the frame is treated as unusable: a covered lens, a pocket,
     * or a genuinely dark room. Normal indoor scenes measure 60-120; a covered lens is under 10.
     * Deliberately conservative so twilight scenes still go through.
     */
    const val DARK_LUMA_THRESHOLD = 18.0

    /**
     * Below this Laplacian variance the frame is blurry enough to retake once. Calibration point:
     * tune against logged values from real captures (logged as PhoneCaptureQuality), not guessed.
     */
    const val RETAKE_SHARPNESS_THRESHOLD = 10.0

    /** Rec. 601 luma of one ARGB pixel, 0..255. */
    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** Mean luma of the frame, 0..255. */
    fun meanLuma(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        for (p in pixels) sum += luma(p)
        return sum / pixels.size
    }

    /**
     * Variance of the 4-neighbour Laplacian over the grayscale frame - the standard cheap focus
     * measure. Sharp edges produce large second derivatives; blur flattens them toward zero.
     */
    fun laplacianSharpness(pixels: IntArray, width: Int, height: Int): Double {
        require(pixels.size == width * height) { "pixels length must equal width*height" }
        if (width < 3 || height < 3) return 0.0

        val gray = DoubleArray(pixels.size)
        for (i in pixels.indices) gray[i] = luma(pixels[i])

        var sum = 0.0
        var sumSq = 0.0
        val count = (width - 2) * (height - 2)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]
                sum += lap
                sumSq += lap * lap
            }
        }
        val mean = sum / count
        return sumSq / count - mean * mean
    }

    fun isTooDark(meanLuma: Double): Boolean = meanLuma < DARK_LUMA_THRESHOLD

    fun shouldRetakeForBlur(sharpness: Double): Boolean = sharpness < RETAKE_SHARPNESS_THRESHOLD
}
