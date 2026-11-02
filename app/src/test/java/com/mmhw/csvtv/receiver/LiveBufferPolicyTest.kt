package com.mmhw.csvtv.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBufferPolicyTest {
    @Test
    fun startsAtLowestLevel() {
        val p = LiveBufferPolicy.profile(LiveBufferPolicy.startLevel)
        assertEquals(300, p.targetOffsetMs)
        assertTrue(p.minBufferMs >= p.playbackMs)
        assertTrue(p.minBufferMs >= p.rebufferMs)
        assertTrue(p.maxBufferMs >= p.minBufferMs)
    }

    @Test
    fun rebufferAndErrorStepUp() {
        assertEquals(1, LiveBufferPolicy.afterRebuffer(0))
        assertEquals(LiveBufferPolicy.maxLevel, LiveBufferPolicy.afterRebuffer(LiveBufferPolicy.maxLevel))
        assertEquals(2, LiveBufferPolicy.afterError(0))
        assertEquals(LiveBufferPolicy.maxLevel, LiveBufferPolicy.afterError(LiveBufferPolicy.maxLevel - 1))
    }

    @Test
    fun stableStepsDown() {
        assertEquals(0, LiveBufferPolicy.afterStable(0))
        assertEquals(2, LiveBufferPolicy.afterStable(3))
    }

    @Test
    fun profilesStayInternallyValid() {
        for (level in 0..LiveBufferPolicy.maxLevel) {
            val p = LiveBufferPolicy.profile(level)
            assertTrue("level $level min>=playback", p.minBufferMs >= p.playbackMs)
            assertTrue("level $level min>=rebuffer", p.minBufferMs >= p.rebufferMs)
            assertTrue("level $level max>=min", p.maxBufferMs >= p.minBufferMs)
            assertTrue("level $level maxOffset>=target", p.maxOffsetMs >= p.targetOffsetMs)
            assertTrue("level $level target>=minOffset", p.targetOffsetMs >= p.minOffsetMs)
        }
    }

    @Test
    fun adjustGap() {
        assertTrue(LiveBufferPolicy.shouldAdjust(2_000, 0))
        assertFalse(LiveBufferPolicy.shouldAdjust(1_000, 0))
        assertTrue(LiveBufferPolicy.shouldAdjust(5_000, 3_000))
    }
}
