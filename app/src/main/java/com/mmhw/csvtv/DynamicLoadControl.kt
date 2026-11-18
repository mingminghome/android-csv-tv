package com.mmhw.csvtv

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import kotlin.math.max
import kotlin.math.min

/**
 * [LoadControl] whose time and byte targets can change while the player stays connected.
 *
 * DefaultLoadControl bakes durations in at build time and, for live items, starts
 * playback once half the live offset is buffered. That cap ignored a deeper rebuffer
 * target, so a jittery channel resumed after a couple of seconds and underran again.
 * Thresholds here are read on each load tick. Call [apply] from the main thread.
 */
@UnstableApi
class DynamicLoadControl(
    initial: PlaybackBufferPolicy.Profile
) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val loadingStates = HashMap<PlayerId, PlayerLoadingState>()

    @Volatile private var minBufferUs: Long = 0L
    @Volatile private var maxBufferUs: Long = 0L
    @Volatile private var playbackUs: Long = 0L
    @Volatile private var rebufferUs: Long = 0L
    @Volatile private var targetBytes: Int = 0
    @Volatile private var resumeOverrideUs: Long = C.TIME_UNSET

    private var appliedTargetBytes = -1

    init {
        apply(initial)
    }

    fun apply(profile: PlaybackBufferPolicy.Profile) {
        minBufferUs = Util.msToUs(profile.minBufferMs.toLong())
        maxBufferUs = Util.msToUs(profile.maxBufferMs.toLong())
        playbackUs = Util.msToUs(profile.playbackMs.toLong())
        rebufferUs = Util.msToUs(profile.rebufferMs.toLong())
        targetBytes = profile.targetBytes
        resumeOverrideUs = C.TIME_UNSET
    }

    fun apply(profile: com.mmhw.csvtv.receiver.LiveBufferPolicy.Profile, targetBytes: Int = 30 * 1024 * 1024) {
        minBufferUs = Util.msToUs(profile.minBufferMs.toLong())
        maxBufferUs = Util.msToUs(profile.maxBufferMs.toLong())
        playbackUs = Util.msToUs(profile.playbackMs.toLong())
        rebufferUs = Util.msToUs(profile.rebufferMs.toLong())
        this.targetBytes = targetBytes
        resumeOverrideUs = C.TIME_UNSET
    }

    constructor(
        profile: com.mmhw.csvtv.receiver.LiveBufferPolicy.Profile,
        targetBytes: Int = 30 * 1024 * 1024
    ) : this(
        PlaybackBufferPolicy.Profile(
            minBufferMs = profile.minBufferMs,
            maxBufferMs = profile.maxBufferMs,
            playbackMs = profile.playbackMs,
            rebufferMs = profile.rebufferMs,
            targetBytes = targetBytes,
            liveOffsetMs = profile.targetOffsetMs
        )
    )

    /**
     * Resume once [bufferedAheadMs] is buffered. Used when the stepped-up target
     * cannot be filled (the source stopped extending the buffer).
     */
    fun relaxResumeTo(bufferedAheadMs: Long) {
        resumeOverrideUs = Util.msToUs(bufferedAheadMs.coerceAtLeast(1L))
    }

    override fun onPrepared(playerId: PlayerId) {
        loadingStates.getOrPut(playerId) { PlayerLoadingState() }.isLoading = false
        syncAllocatorTarget()
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>
    ) {
        if (loadingStates.containsKey(parameters.playerId)) {
            syncAllocatorTarget()
        }
    }

    override fun onStopped(playerId: PlayerId) {
        removePlayer(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        removePlayer(playerId)
    }

    override fun getAllocator(): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = 0L

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val state = loadingStates[parameters.playerId] ?: return false
        syncAllocatorTarget()
        val byteCapReached = allocator.totalBytesAllocated >= targetBytes
        var minUs = minBufferUs
        if (parameters.playbackSpeed > 1f) {
            val scaled = Util.getMediaDurationForPlayoutDuration(minUs, parameters.playbackSpeed)
            minUs = min(scaled, maxBufferUs)
        }
        minUs = max(minUs, 500_000L)
        if (parameters.bufferedDurationUs < minUs) {
            // Fill toward the time target, but never past the RAM byte cap.
            state.isLoading = !byteCapReached
        } else if (parameters.bufferedDurationUs >= maxBufferUs || byteCapReached) {
            state.isLoading = false
        }
        return state.isLoading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(
            parameters.bufferedDurationUs,
            parameters.playbackSpeed
        )
        val overrideUs = resumeOverrideUs
        val targetUs = when {
            overrideUs != C.TIME_UNSET -> overrideUs
            parameters.rebuffering -> rebufferUs
            else -> playbackUs
        }
        val start = targetUs <= 0L || bufferedUs >= targetUs
        if (start && overrideUs != C.TIME_UNSET) {
            resumeOverrideUs = C.TIME_UNSET
        }
        return start
    }

    override fun shouldContinuePreloading(
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long
    ): Boolean = loadingStates.values.none { it.isLoading }

    private fun removePlayer(playerId: PlayerId) {
        if (loadingStates.remove(playerId) != null) {
            appliedTargetBytes = -1
            syncAllocatorTarget()
        }
    }

    private fun syncAllocatorTarget() {
        val desired = targetBytes
        if (desired == appliedTargetBytes) return
        appliedTargetBytes = desired
        if (loadingStates.isEmpty()) {
            allocator.reset()
        } else {
            allocator.setTargetBufferSize(desired)
        }
    }

    private class PlayerLoadingState {
        var isLoading: Boolean = false
    }
}
