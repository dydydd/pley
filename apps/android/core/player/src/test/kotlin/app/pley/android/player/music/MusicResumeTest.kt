@file:OptIn(ExperimentalCoroutinesApi::class)

package app.pley.android.player.music

import app.pley.android.model.ItemKind
import app.pley.android.model.ItemSummary
import app.pley.android.player.MusicQueueState
import app.pley.android.player.MusicRepeat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What music was playing, kept for Play to bring back once the app has
 * left memory: the queue and the place in it, read back by another run.
 */
class MusicResumeTest {
    /** The profile's snapshots; what was written, in order. */
    private val store = HashMap<String, ByteArray>()
    private val written = mutableListOf<String>()

    private fun TestScope.resume() = MusicResume(
        read = { key -> store[key]?.takeIf { it.isNotEmpty() } },
        write = { key, bytes ->
            written += key
            store[key] = bytes
        },
        scope = backgroundScope,
    )

    private val album = (1..12L).map { track(it) }

    @Test
    fun whatWasPlayingIsReadBackByAnotherRunWhereItWas() = runTest {
        val first = resume()
        first.keep(MusicQueueState(album, index = 4, repeat = MusicRepeat.All), positionMs = 61_000)
        runCurrent()

        // Another process: nothing in memory, the store alone.
        val saved = resume().saved()!!
        assertEquals(album, saved.tracks)
        assertEquals(4, saved.index)
        assertEquals(61_000L, saved.positionMs)
        assertEquals(MusicRepeat.All, saved.repeat)
    }

    @Test
    fun theRadiosChannelIsKeptUntilMusicPlaysAgainAndWrittenOnlyWhenItChanges() = runTest {
        val first = resume()
        assertEquals(null, first.savedRadio())
        first.keepRadio("channel:7@favourites")
        first.keepRadio("channel:7@favourites")
        runCurrent()
        assertEquals(1, written.count { it == MusicResume.RADIO })
        // Another process reads it back; the music kept beside it stays.
        first.keep(MusicQueueState(album, index = 1), positionMs = 0)
        runCurrent()
        val second = resume()
        assertEquals("channel:7@favourites", second.savedRadio())
        assertEquals(1, second.saved()!!.index)

        second.keepRadio(null)
        runCurrent()
        assertEquals(null, resume().savedRadio())
        assertEquals(1, resume().saved()!!.index)
    }

    @Test
    fun thePlaceIsWrittenAsItMovesAndTheQueueOnlyWhenItChanges() = runTest {
        val resume = resume()
        resume.keep(MusicQueueState(album, index = 0), positionMs = 0)
        runCurrent()
        resume.keep(MusicQueueState(album.toList(), index = 0), positionMs = 15_000)
        resume.keep(MusicQueueState(album.toList(), index = 1), positionMs = 0)
        runCurrent()
        assertEquals(1, written.count { it == MusicResume.QUEUE })
        assertEquals(1, resume.saved()!!.index)

        resume.keep(MusicQueueState(album.reversed(), index = 1), positionMs = 0)
        runCurrent()
        assertEquals(2, written.count { it == MusicResume.QUEUE })
        assertEquals(11L, resume.saved()!!.tracks[1].id)
    }

    @Test
    fun aQueueStoppedIsForgottenAndTheEmptyOneAProcessStartsWithForgetsNothing() = runTest {
        resume().keep(MusicQueueState(album, index = 2), positionMs = 5_000)
        runCurrent()
        // A new run keeps nothing until music plays: what the last one kept stays.
        val next = resume()
        assertEquals(2, next.saved()?.index)

        next.keep(MusicQueueState(), positionMs = 0)
        runCurrent()
        assertNull(next.saved())
        assertNull(resume().saved())
    }

    @Test
    fun aLongQueueKeepsTheTracksAroundTheOnePlaying() = runTest {
        val long = (1..1_200L).map { track(it) }
        val resume = resume()
        resume.keep(MusicQueueState(long, index = 400), positionMs = 0)
        runCurrent()
        val saved = resume.saved()!!
        assertEquals(MusicResume.MAX_TRACKS, saved.tracks.size)
        // The same track, with some before it for Previous and the rest after.
        assertEquals(401L, saved.tracks[saved.index].id)
        assertEquals(50, saved.index)

        // Still within what was kept: only the place moves.
        resume.keep(MusicQueueState(long, index = 401), positionMs = 0)
        runCurrent()
        assertEquals(402L, resume.saved()!!.let { it.tracks[it.index].id })
        assertEquals(1, written.count { it == MusicResume.QUEUE })
    }

    @Test
    fun aTrackLeftAtItsEndStartsOverAndWhatCantBeReadIsNothing() = runTest {
        val resume = resume()
        resume.keep(MusicQueueState(album, index = 11), positionMs = 179_000)
        runCurrent()
        assertEquals(0L, resume.saved()!!.positionMs)

        store[MusicResume.PLACE] = "99 -4 Sideways".encodeToByteArray()
        val odd = resume.saved()!!
        assertEquals(0, odd.index)
        assertEquals(0L, odd.positionMs)
        assertEquals(MusicRepeat.Off, odd.repeat)

        store[MusicResume.QUEUE] = "not a queue".encodeToByteArray()
        assertNull(resume.saved())
        // A queue of something other than tracks isn't music's.
        store[MusicResume.QUEUE] = store.getValue(MusicResume.PLACE)
        assertTrue(resume.saved() == null)
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
}
