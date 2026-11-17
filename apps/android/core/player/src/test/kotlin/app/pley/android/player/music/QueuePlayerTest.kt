package app.pley.android.player.music

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import app.pley.android.model.ItemKind
import app.pley.android.model.ItemSummary
import app.pley.android.model.caps.ClientCaps
import app.pley.android.player.EngineSource
import app.pley.android.player.session.SessionReporter
import app.pley.android.testing.network.FakePleyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The media session's player: the music's whole queue, as a car lists it,
 * with picks from the queue, Shuffle and Play going through the music.
 */
@RunWith(RobolectricTestRunner::class)
class QueuePlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val output = SilentOutput()
    private val playback = MusicPlayback(
        MusicEnvironment(
            api = { FakePleyApi() },
            caps = { ClientCaps() },
            clientId = { "android-test-music" },
            reporter = SessionReporter(scope),
            output = { output },
        ),
        scope,
    )
    private val engine = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
    private val album = (1..3L).map { track(it) }
    private val player = QueuePlayer(engine, playback)

    @After
    fun tearDown() {
        scope.cancel()
        engine.release()
    }

    /** The engine holds track [id], as the output describes the track it plays. */
    private fun engineHolds(id: Long) {
        val metadata = MediaMetadata.Builder().setTitle("Track $id").setExtras(Bundle().apply { putLong(EngineMusicOutput.TRACK_ID, id) }).build()
        engine.setMediaItem(MediaItem.Builder().setUri("http://server/stream").setMediaMetadata(metadata).build())
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theWholeQueueShowsWithTheTrackPlayingAsTheEngineHasIt() {
        playback.play(album, startIndex = 1)
        engineHolds(2)
        player.queueChanged()
        idle()

        assertEquals(3, player.mediaItemCount)
        assertEquals(1, player.currentMediaItemIndex)
        assertEquals("Track 2", player.mediaMetadata.title.toString())
        assertEquals("track:2", player.currentMediaItem?.mediaId)
        assertEquals("Track 3", player.getMediaItemAt(2).mediaMetadata.title.toString())
        assertTrue(player.hasNextMediaItem())
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertTrue(player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE))
        assertFalse(player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))

        // The engine still holding track 2 while track 3 is asked for: the entry is track 3's own.
        playback.jumpTo(2)
        player.queueChanged()
        idle()
        assertEquals("track:3", player.currentMediaItem?.mediaId)
        assertEquals("Track 3", player.mediaMetadata.title.toString())
    }

    @Test
    fun betweenTracksTheQueuesTrackShowsAsLoadingNeverAsNothingPlaying() {
        playback.play(album, startIndex = 1)
        // The engine has nothing loaded yet: the track is asked of the server. A car shows its loading screen for a
        // session with nothing in it, so the session lists the queue and says the track loads.
        player.queueChanged()
        idle()
        assertEquals(3, player.mediaItemCount)
        assertEquals("track:2", player.currentMediaItem?.mediaId)
        assertEquals(Player.STATE_BUFFERING, player.playbackState)
        assertTrue(player.playWhenReady)
        assertEquals(0, player.currentPosition)

        engineHolds(2)
        player.queueChanged()
        idle()
        assertEquals("Track 2", player.mediaMetadata.title.toString())
    }

    @Test
    fun aPickFromTheQueueAndShuffleGoThroughTheMusic() {
        playback.play(album)
        engineHolds(1)
        player.queueChanged()
        idle()

        player.seekTo(2, 0)
        idle()
        assertEquals(2, playback.queueState.value.index)
        player.queueChanged()
        idle()
        assertEquals(2, player.currentMediaItemIndex)

        player.shuffleModeEnabled = true
        idle()
        assertTrue(playback.queueState.value.shuffle)
        player.queueChanged()
        idle()
        assertTrue(player.shuffleModeEnabled)
    }

    @Test
    fun itemsSetFromOutsideChangeNothing() {
        playback.play(album)
        engineHolds(1)
        player.setMediaItem(MediaItem.fromUri("http://elsewhere/other"))
        idle()
        assertEquals(3, playback.queueState.value.tracks.size)
        assertEquals("http://server/stream", engine.currentMediaItem?.localConfiguration?.uri.toString())
    }

    private fun track(id: Long) = ItemSummary(
        id = id,
        libraryId = 3,
        kind = ItemKind.Track,
        title = "Track $id",
        parentId = 50,
        parentTitle = "Deep Water",
        grandparentTitle = "Harbour Lights",
        durationMs = 180_000,
    )

    /** An output that loads and plays nothing. */
    private class SilentOutput : MusicOutput {
        override fun load(source: EngineSource, startMs: Long, play: Boolean, info: TrackInfo) = Unit

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun stop() = Unit

        override fun release() = Unit

        override val positionMs = 0L
        override val durationMs = 0L
        override val bufferedMs = 0L
        override val playWhenReady = false

        override fun setListener(listener: MusicOutput.Listener?) = Unit
    }
}
