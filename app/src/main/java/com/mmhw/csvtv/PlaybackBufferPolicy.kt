package com.mmhw.csvtv

/**
 * Dynamic buffer for catalog playback.
 *
 * Starts at the device's normal cushion so healthy channels join quickly. When a
 * stream underruns — the "plays ~10s, then buffers, then reloads" loop — the level
 * steps up so the player holds more media before resuming. The level steps back
 * down after a long clean stretch.
 *
 * Reconnect is reserved for a buffer that is not growing and is too small to play.
 * A buffer that is still filling, or that already holds a playable stretch the
 * source will not extend (short live window, byte cap), resumes in place.
 */
object PlaybackBufferPolicy {
    data class Limits(
        val startMinMs: Int,
        val startMaxMs: Int,
        val ceilingMaxMs: Int,
        val playbackMs: Int,
        val startBytes: Int,
        val ceilingBytes: Int
    )

    data class Profile(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val playbackMs: Int,
        val rebufferMs: Int,
        val targetBytes: Int,
        val liveOffsetMs: Int
    )

    enum class BufferingAction {
        /** Keep the connection. The load control is still filling toward the target. */
        WAIT,
        /** This underrun should raise the cushion. Apply the new profile, do not reconnect. */
        STEP_UP,
        /** Enough media is buffered and the target will not fill. Resume without reconnecting. */
        RESUME,
        /** Nothing playable is arriving. Tear down and rejoin. */
        RECONNECT
    }

    data class BufferingTick(
        val level: Int,
        val profile: Profile,
        val bufferingForMs: Long,
        val stuckForMs: Long,
        val bufferedAheadMs: Long,
        val steppedThisEpisode: Boolean
    )

    /**
     * Resume targets by level. Level 2 is the first response to a channel that
     * dies after about 10s of playback (a short play jumps two levels).
     */
    private val rebufferTargetsMs = intArrayOf(6_000, 12_000, 18_000, 24_000, 32_000)
    private val liveOffsetTargetsMs = intArrayOf(8_000, 12_000, 16_000, 22_000, 28_000)

    const val startLevel = 0
    val maxLevel: Int get() = rebufferTargetsMs.lastIndex

    /** Clean playback before stepping the cushion back down one level. */
    const val stableAfterMs = 45_000L

    /** How long a rebuffer must last before the cushion grows. */
    const val stepUpAfterBufferingMs = 2_000L

    /** No buffer growth for this long means the source has stopped extending the window. */
    const val segmentGapMs = 4_000L

    /** Extra wait past the resume target before playing whatever has arrived. */
    const val fillSlackMs = 4_000L

    /** No growth and nothing playable for this long → reconnect. */
    const val stuckReconnectMs = 10_000L

    /** A play stretch this short means the current cushion cannot cover the source. */
    const val shortPlayMs = 15_000L

    fun limitsFor(totalMb: Long, lowRam: Boolean): Limits = when {
        lowRam || totalMb < 2_800 -> Limits(
            startMinMs = 12_000,
            startMaxMs = 28_000,
            ceilingMaxMs = 28_000,
            playbackMs = 2_000,
            startBytes = 18 * 1024 * 1024,
            ceilingBytes = 24 * 1024 * 1024
        )
        totalMb < 4_096 -> Limits(
            startMinMs = 18_000,
            startMaxMs = 40_000,
            ceilingMaxMs = 50_000,
            playbackMs = 2_500,
            startBytes = 28 * 1024 * 1024,
            ceilingBytes = 40 * 1024 * 1024
        )
        else -> Limits(
            startMinMs = 25_000,
            startMaxMs = 55_000,
            ceilingMaxMs = 70_000,
            playbackMs = 3_000,
            startBytes = 48 * 1024 * 1024,
            ceilingBytes = 64 * 1024 * 1024
        )
    }

    fun profile(level: Int, limits: Limits): Profile {
        val clamped = level.coerceIn(0, maxLevel)
        val maxMs = lerp(limits.startMaxMs, limits.ceilingMaxMs, clamped)
            .coerceAtLeast(limits.playbackMs)
        val rebuffer = rebufferTargetsMs[clamped].coerceIn(limits.playbackMs, maxMs)
        val minMs = lerp(limits.startMinMs, maxMs, clamped).coerceIn(rebuffer, maxMs)
        val live = liveOffsetTargetsMs[clamped].coerceIn(limits.playbackMs, maxMs)
        val bytes = lerp(limits.startBytes, limits.ceilingBytes, clamped)
            .coerceIn(limits.startBytes, limits.ceilingBytes)
        return Profile(
            minBufferMs = minMs,
            maxBufferMs = maxMs,
            playbackMs = limits.playbackMs,
            rebufferMs = rebuffer,
            targetBytes = bytes,
            liveOffsetMs = live
        )
    }

    /**
     * A channel that only stays up for a few seconds jumps two levels so the next
     * resume asks for more than that stretch. Longer failures climb one level.
     */
    fun afterUnderrun(level: Int, playedBeforeMs: Long): Int {
        val jump = if (playedBeforeMs in 1..shortPlayMs) 2 else 1
        return (level + jump).coerceAtMost(maxLevel)
    }

    fun afterStable(level: Int): Int = (level - 1).coerceAtLeast(startLevel)

    fun onBufferingTick(tick: BufferingTick): BufferingAction {
        val profile = tick.profile
        if (!tick.steppedThisEpisode &&
            tick.level < maxLevel &&
            tick.bufferingForMs >= stepUpAfterBufferingMs &&
            tick.bufferedAheadMs < profile.rebufferMs
        ) {
            return BufferingAction.STEP_UP
        }
        if (tick.bufferedAheadMs >= profile.rebufferMs) {
            return BufferingAction.WAIT
        }
        val deadline = profile.rebufferMs + fillSlackMs
        val playable = tick.bufferedAheadMs >= profile.playbackMs
        if (playable && (tick.stuckForMs >= segmentGapMs || tick.bufferingForMs >= deadline)) {
            return BufferingAction.RESUME
        }
        if (tick.bufferedAheadMs < profile.playbackMs && tick.stuckForMs >= stuckReconnectMs) {
            return BufferingAction.RECONNECT
        }
        return BufferingAction.WAIT
    }

    private fun lerp(start: Int, end: Int, level: Int): Int {
        if (maxLevel <= 0) return start
        return start + (end - start) * level / maxLevel
    }
}
