package com.mmhw.csvtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun levelZeroMatchesDeviceStartAndStaysInsideTheCap() {
        val low = PlaybackBufferPolicy.limitsFor(totalMb = 2_000, lowRam = true)
        val start = PlaybackBufferPolicy.profile(PlaybackBufferPolicy.startLevel, low)
        assertEquals(12_000, start.minBufferMs)
        assertEquals(28_000, start.maxBufferMs)
        assertEquals(2_000, start.playbackMs)
        assertEquals(6_000, start.rebufferMs)
        assertEquals(8_000, start.liveOffsetMs)
        assertEquals(18 * 1024 * 1024, start.targetBytes)
    }

    @Test
    fun shortPlayJumpsTwoLevelsSoTheNextCushionCoversAboutTenSeconds() {
        assertEquals(2, PlaybackBufferPolicy.afterUnderrun(0, playedBeforeMs = 10_000))
        assertEquals(2, PlaybackBufferPolicy.afterUnderrun(0, playedBeforeMs = 1))
        assertEquals(1, PlaybackBufferPolicy.afterUnderrun(0, playedBeforeMs = 20_000))
        assertEquals(
            PlaybackBufferPolicy.maxLevel,
            PlaybackBufferPolicy.afterUnderrun(PlaybackBufferPolicy.maxLevel, playedBeforeMs = 5_000)
        )

        val mid = PlaybackBufferPolicy.limitsFor(totalMb = 3_500, lowRam = false)
        val deepened = PlaybackBufferPolicy.profile(2, mid)
        assertTrue(deepened.rebufferMs >= 15_000)
        assertTrue(deepened.rebufferMs > PlaybackBufferPolicy.profile(0, mid).rebufferMs)
    }

    @Test
    fun stableStepsDownOneLevel() {
        assertEquals(0, PlaybackBufferPolicy.afterStable(0))
        assertEquals(2, PlaybackBufferPolicy.afterStable(3))
    }

    @Test
    fun profilesStayInsideRamCaps() {
        val limits = listOf(
            PlaybackBufferPolicy.limitsFor(1_500, lowRam = true),
            PlaybackBufferPolicy.limitsFor(3_000, lowRam = false),
            PlaybackBufferPolicy.limitsFor(6_000, lowRam = false)
        )
        for (limit in limits) {
            for (level in 0..PlaybackBufferPolicy.maxLevel) {
                val profile = PlaybackBufferPolicy.profile(level, limit)
                assertTrue(profile.playbackMs <= profile.rebufferMs)
                assertTrue(profile.rebufferMs <= profile.minBufferMs)
                assertTrue(profile.minBufferMs <= profile.maxBufferMs)
                assertTrue(profile.maxBufferMs <= limit.ceilingMaxMs)
                assertTrue(profile.liveOffsetMs in profile.playbackMs..profile.maxBufferMs)
                assertTrue(profile.targetBytes in limit.startBytes..limit.ceilingBytes)
            }
        }
    }

    @Test
    fun growingBufferStepsUpOnceThenWaitsInsteadOfReconnecting() {
        val limits = PlaybackBufferPolicy.limitsFor(4_096, lowRam = false)
        val start = PlaybackBufferPolicy.profile(0, limits)
        val step = PlaybackBufferPolicy.onBufferingTick(
            tick(
                level = 0,
                profile = start,
                bufferingForMs = 2_000,
                stuckForMs = 0,
                bufferedAheadMs = 3_000,
                stepped = false
            )
        )
        assertEquals(PlaybackBufferPolicy.BufferingAction.STEP_UP, step)

        val level = PlaybackBufferPolicy.afterUnderrun(0, playedBeforeMs = 10_000)
        val deepened = PlaybackBufferPolicy.profile(level, limits)
        val stillFilling = PlaybackBufferPolicy.onBufferingTick(
            tick(
                level = level,
                profile = deepened,
                bufferingForMs = 8_000,
                stuckForMs = 0,
                bufferedAheadMs = 8_000,
                stepped = true
            )
        )
        assertEquals(PlaybackBufferPolicy.BufferingAction.WAIT, stillFilling)
    }

    @Test
    fun playableBufferThatStopsGrowingResumesWithoutReconnect() {
        val limits = PlaybackBufferPolicy.limitsFor(2_000, lowRam = true)
        val profile = PlaybackBufferPolicy.profile(2, limits)
        val action = PlaybackBufferPolicy.onBufferingTick(
            tick(
                level = 2,
                profile = profile,
                bufferingForMs = 12_000,
                stuckForMs = PlaybackBufferPolicy.segmentGapMs,
                bufferedAheadMs = 10_000,
                stepped = true
            )
        )
        assertEquals(PlaybackBufferPolicy.BufferingAction.RESUME, action)
    }

    @Test
    fun quickRefillDoesNotGrowTheCushion() {
        val limits = PlaybackBufferPolicy.limitsFor(4_096, lowRam = false)
        val profile = PlaybackBufferPolicy.profile(0, limits)
        val action = PlaybackBufferPolicy.onBufferingTick(
            tick(
                level = 0,
                profile = profile,
                bufferingForMs = 2_000,
                stuckForMs = 0,
                bufferedAheadMs = profile.rebufferMs.toLong(),
                stepped = false
            )
        )
        assertEquals(PlaybackBufferPolicy.BufferingAction.WAIT, action)
    }

    @Test
    fun deadBufferReconnects() {
        val limits = PlaybackBufferPolicy.limitsFor(4_096, lowRam = false)
        val profile = PlaybackBufferPolicy.profile(PlaybackBufferPolicy.maxLevel, limits)
        val action = PlaybackBufferPolicy.onBufferingTick(
            tick(
                level = PlaybackBufferPolicy.maxLevel,
                profile = profile,
                bufferingForMs = 12_000,
                stuckForMs = PlaybackBufferPolicy.stuckReconnectMs,
                bufferedAheadMs = 400,
                stepped = true
            )
        )
        assertEquals(PlaybackBufferPolicy.BufferingAction.RECONNECT, action)
    }

    @Test
    fun tricklePastTheFillDeadlineResumesWithWhatArrived() {
        val limits = PlaybackBufferPolicy.limitsFor(6_000, lowRam = false)
        val profile = PlaybackBufferPolicy.profile(2, limits)
        val action = PlaybackBufferPolicy.onBufferingTick(
            tick(
                level = 2,
                profile = profile,
                bufferingForMs = profile.rebufferMs + PlaybackBufferPolicy.fillSlackMs.toLong(),
                stuckForMs = 0,
                bufferedAheadMs = profile.playbackMs.toLong(),
                stepped = true
            )
        )
        assertEquals(PlaybackBufferPolicy.BufferingAction.RESUME, action)
    }

    private fun tick(
        level: Int,
        profile: PlaybackBufferPolicy.Profile,
        bufferingForMs: Long,
        stuckForMs: Long,
        bufferedAheadMs: Long,
        stepped: Boolean
    ) = PlaybackBufferPolicy.BufferingTick(
        level = level,
        profile = profile,
        bufferingForMs = bufferingForMs,
        stuckForMs = stuckForMs,
        bufferedAheadMs = bufferedAheadMs,
        steppedThisEpisode = stepped
    )
}
