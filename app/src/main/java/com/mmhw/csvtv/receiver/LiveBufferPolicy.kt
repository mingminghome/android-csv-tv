package com.mmhw.csvtv.receiver

/**
 * Auto buffer for live screen cast. Starts tight, steps up when the player
 * starves or errors, and steps down after a stretch of clean playback.
 */
object LiveBufferPolicy {
    data class Profile(
        val targetOffsetMs: Int,
        val minOffsetMs: Int,
        val maxOffsetMs: Int,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val playbackMs: Int,
        val rebufferMs: Int
    )

    val profiles = listOf(
        Profile(300, 150, 600, 200, 1_000, 100, 200),
        Profile(500, 250, 900, 350, 1_600, 200, 350),
        Profile(800, 400, 1_400, 550, 2_400, 350, 550),
        Profile(1_200, 700, 2_000, 800, 3_200, 500, 800),
        Profile(1_800, 1_000, 2_800, 1_200, 4_000, 800, 1_200)
    )

    const val startLevel = 0
    const val stableAfterMs = 12_000L
    const val minAdjustGapMs = 1_500L

    val maxLevel: Int get() = profiles.lastIndex

    fun profile(level: Int): Profile = profiles[level.coerceIn(0, maxLevel)]

    fun afterRebuffer(level: Int): Int = (level + 1).coerceAtMost(maxLevel)

    fun afterError(level: Int): Int = (level + 2).coerceAtMost(maxLevel)

    fun afterStable(level: Int): Int = (level - 1).coerceAtLeast(0)

    fun shouldAdjust(nowMs: Long, lastAdjustMs: Long): Boolean =
        nowMs - lastAdjustMs >= minAdjustGapMs
}
