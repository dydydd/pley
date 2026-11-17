package app.pley.android.player.music

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import app.pley.android.model.ItemSummary
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The player the media session shows (notification, lock screen, the
 * system's media controls, Bluetooth buttons, Android Auto): the output's
 * one-track player, shown as [playback]'s whole queue, so a car lists
 * what comes next and picks from it. Play, Pause, Previous, Next, a pick
 * from the queue and Shuffle go through [playback]: after the queue's
 * end, Play starts its last track again. What a car asks to play is
 * started by the session's callback (MusicService), so items set here
 * change nothing; nor can the queue be edited from outside.
 *
 * [describe] gives a track of the queue its title, artist, album and
 * art, as the car lists it. Call [queueChanged] when [playback]'s queue
 * changes.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class QueuePlayer(
    player: Player,
    private val playback: MusicPlayback,
    private val describe: (track: ItemSummary) -> MediaItem = { plainItem(it) },
) : ForwardingSimpleBasePlayer(player) {
    /** Shows [playback]'s queue as it is now. */
    fun queueChanged() = invalidateState()

    override fun getState(): State {
        val base = super.getState()
        val queue = playback.queueState.value
        val commands = base.availableCommands.buildUpon().addAll(*QUEUE_COMMANDS).removeAll(*NOT_OURS).build()
        val state = base.buildUpon()
            .setAvailableCommands(commands)
            .setShuffleModeEnabled(queue.shuffle)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
        if (queue.tracks.isEmpty()) return state.build()
        val index = base.currentMediaItemIndex
        val window = if (!base.timeline.isEmpty && index in 0 until base.timeline.windowCount) base.timeline.getWindow(index, Timeline.Window()) else null
        val current = queue.tracks.getOrNull(queue.index)
        // Whether the output plays the queue's track now; not while the next one is asked of the server and loads.
        val loaded = window != null && current != null && window.mediaItem.mediaMetadata.extras?.getLong(EngineMusicOutput.TRACK_ID, -1) == current.id
        val playlist = queue.tracks.mapIndexed { i, track ->
            val item = describe(track)
            val data = MediaItemData.Builder(Uid(i, track.id))
            if (i == queue.index && loaded && window != null) {
                // The track the output plays: its own item, duration and tracks, with the art's address for cars.
                val metadata = base.currentMetadata.buildUpon().setArtworkUri(item.mediaMetadata.artworkUri).build()
                data.setMediaItem(window.mediaItem.buildUpon().setMediaId(item.mediaId).setMediaMetadata(metadata).build())
                    .setMediaMetadata(metadata)
                    .setTracks(base.currentTracks)
                    .setDurationUs(window.durationUs)
                    .setIsSeekable(window.isSeekable)
                    .setDefaultPositionUs(window.defaultPositionUs)
            } else {
                // Another track, or the current one while it loads.
                data.setMediaItem(item).setDurationUs(track.durationMs?.takeIf { it > 0 }?.let { it * 1000 } ?: C.TIME_UNSET)
            }
            data.build()
        }
        state.setPlaylist(playlist).setCurrentMediaItemIndex(queue.index.coerceIn(0, playlist.lastIndex))
        // Between tracks, and while a failed one is tried again, the output has nothing loaded, or an error: the session
        // says the queue's track is loading, playing as it will, so a car doesn't show its own loading screen for nothing
        // playing at each change. A track that failed for good says so.
        val now = playback.nowPlaying.value
        val starting = now != null && now.error == null && (now.loading || !loaded || base.playerError != null)
        if (starting && now != null) {
            state.setPlaybackState(Player.STATE_BUFFERING)
                .setPlayerError(null)
                .setPlayWhenReady(now.playing || now.loading, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            if (!loaded) state.setContentPositionMs(0)
        }
        return state.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) playback.play() else playback.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val current = playback.queueState.value.index
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> playback.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> playback.previous()
            else -> when {
                mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != current -> playback.jumpTo(mediaItemIndex)
                else -> playback.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        playback.setShuffle(shuffleModeEnabled)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playback.stop()
        return Futures.immediateVoidFuture()
    }

    // The output prepares its tracks itself (MusicPlayback): one prepared from outside would be the last one again.
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = Futures.immediateVoidFuture()

    /** A queue entry: the same track can be in the queue twice. */
    private data class Uid(val position: Int, val trackId: Long)

    companion object {
        private val QUEUE_COMMANDS = intArrayOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            // What a car picks to play goes through the session's callback (MusicService).
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
        )

        /** The queue plays once, at its speed. */
        private val NOT_OURS = intArrayOf(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SPEED_AND_PITCH)

        /** A track with its title, artist and album only. */
        fun plainItem(track: ItemSummary): MediaItem = MediaItem.Builder()
            .setMediaId("track:${track.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.grandparentTitle)
                    .setAlbumTitle(track.parentTitle)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build(),
            )
            .build()
    }
}
