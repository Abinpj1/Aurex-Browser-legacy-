package com.aurex.browser

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * A media3 [Player] whose "media" is the page's own <video> element.
 *
 * The built-in player screen (PlayerScreen: gestures, seek bar, speed, lock, aspect, PiP ...) only talks to the
 * [Player] interface. Giving it this adapter instead of an ExoPlayer makes the SAME built-in player UI drive the
 * video that is already playing in the page: no second decoder, no new connection, no resolve, no buffer, no pause.
 * State is read from the page ([refresh], 4x a second); commands go back to the page through PIP_JS ([BrowserState.pipCommand]).
 */
class WebVideoPlayer(private val s: BrowserState) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var posMs = 0L
    private var durMs = 0L
    private var playing = true
    private var ended = false
    private var rate = 1f
    private var buffering = false
    private var muted = false
    private var lockUntil = 0L      // right after a command the page may still report the old state
    private var misses = 0
    /** True while the page holds its video for a quality switch (old sound off, new quality loading). */
    @Volatile var pageSwitching = false
        private set
    /** > 0 while the page does not answer (video gone / page navigated). */
    val missCount: Int get() = misses

    override fun getState(): SimpleBasePlayer.State {
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_TRACKS
        ).build()
        val item = SimpleBasePlayer.MediaItemData.Builder("web-video")
            .setDurationUs(if (durMs > 0) durMs * 1000L else androidx.media3.common.C.TIME_UNSET)
            .setIsSeekable(durMs > 0)
            .setIsDynamic(durMs <= 0)
            .build()
        val tab = s.tabs.getOrNull(s.current)
        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(cmds)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(
                when {
                    ended -> Player.STATE_ENDED
                    buffering -> Player.STATE_BUFFERING
                    else -> Player.STATE_READY
                }
            )
            .setPlaylist(ImmutableList.of(item))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(posMs)
            .setPlaybackParameters(PlaybackParameters(rate))
            .setVolume(if (muted) 0f else 1f)
            .setVideoSize(VideoSize(tab?.videoWidth ?: 0, tab?.videoHeight ?: 0))
            .build()
    }

    private var inFlightSince = 0L
    private var switchUntil = 0L   // a quality switch is running: the page may report 0 / a stale time for a moment

    /** Called when the person picks another quality: position and length stay as they were until the page settles. */
    fun beginSwitch() { switchUntil = SystemClock.uptimeMillis() + 6_000L }

    /**
     * Reads position / duration / play state from the page video. Call on the main thread.
     * Only ONE request is in flight: a busy page (heavy ads / scripts) answers slowly, and a new request every
     * 250 ms piled up behind it, which made the controls lag and the whole page stutter.
     */
    fun refresh() {
        val web = s.tabs.getOrNull(s.current)?.web ?: run { misses++; return }
        val now = SystemClock.uptimeMillis()
        if (inFlightSince != 0L) {
            if (now - inFlightSince < 1500L) return        // previous answer still pending
            misses++                                        // no answer for 1.5 s: count it, then ask again
        }
        inFlightSince = now
        runCatching {
            web.evaluateJavascript("(function(){try{return window.__lqPip.info();}catch(e){return '';}})()") { raw ->
                inFlightSince = 0L
                val o = runCatching { JSONObject(JSONTokener(raw).nextValue() as? String ?: "") }.getOrNull()
                if (o == null) { misses++; return@evaluateJavascript }
                misses = 0
                val newPos = (o.optDouble("t", 0.0) * 1000).toLong().coerceAtLeast(0L)
                val newDur = (o.optDouble("d", 0.0) * 1000).toLong().coerceAtLeast(0L)
                if (SystemClock.uptimeMillis() < switchUntil) {
                    // while the page swaps its stream, time can fall back to 0 and the length can vanish: ignore that
                    if (newDur > 0) durMs = newDur
                    if (kotlin.math.abs(newPos - posMs) < 2_500L || posMs == 0L) posMs = newPos
                } else {
                    posMs = newPos
                    durMs = newDur
                }
                buffering = o.optInt("b") == 1
                pageSwitching = o.optInt("s") == 1
                if (SystemClock.uptimeMillis() >= lockUntil) {
                    ended = o.optInt("e") == 1
                    playing = o.optInt("p") == 1
                    rate = o.optDouble("r", 1.0).toFloat()
                }
                invalidateState()
            }
        }
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()
    private fun lock() { lockUntil = SystemClock.uptimeMillis() + 700L }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        if (playWhenReady) ended = false
        lock()
        s.pipCommand("setPlay($playWhenReady)")
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        posMs = positionMs.coerceAtLeast(0L)
        ended = false
        s.pipCommand("seekAbs(" + String.format(Locale.US, "%.3f", posMs / 1000.0) + ")")
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        rate = playbackParameters.speed
        lock()
        s.pipCommand("rate(" + String.format(Locale.US, "%.2f", rate) + ")")
        return done()
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        muted = volume < 0.01f
        s.pipCommand("mute($muted)")
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> = done()
}
