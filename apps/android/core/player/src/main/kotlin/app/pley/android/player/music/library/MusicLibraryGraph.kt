package app.pley.android.player.music.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import app.pley.android.common.AppLanguage
import app.pley.android.data.DataGraph
import app.pley.android.network.NetworkGraph
import app.pley.android.player.R
import app.pley.android.player.music.fetchPicture
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The music library for cars and voice assistants, and its pictures, over the app's data. */
class MusicLibraryGraph(context: Context, data: DataGraph, network: NetworkGraph) {
    private val app = context.applicationContext
    private val source = RepositoryMusicSource(data, network)
    private val fetch: suspend (String) -> ByteArray? = { url -> fetchPicture(network, url) }

    val library = MusicLibrary(source, { look() }) { path -> MusicArt.uri(app, path) }

    /** Whether a profile is signed in now: the library has nothing to show otherwise. */
    fun signedIn(): Boolean = sessions.value != null

    /** [signedIn] as it changes, now first. */
    val signedInChanges: Flow<Boolean> get() = sessions.map { it != null }.distinctUntilChanged()

    private val sessions = data.session.session

    /** The bytes of the server's picture at [path] (a [MusicArt] path); `null` when it can't be had. */
    suspend fun picture(path: String): ByteArray? {
        if (!MusicArt.servable(path)) return null
        val url = try {
            source.pictureUrl(path, ART_WIDTH)
        } catch (_: SignedOutException) {
            return null
        }
        return fetch(url)
    }

    /** The words in the language shown now. */
    private fun look(): MusicLibraryLook {
        val res = AppLanguage.resources(app)
        return object : MusicLibraryLook {
            override val recent = res.getString(R.string.music_library_recent)
            override val artists = res.getString(R.string.music_library_artists)
            override val playlists = res.getString(R.string.music_library_playlists)
            override val albums = res.getString(R.string.music_library_albums)
            override val songs = res.getString(R.string.music_library_songs)
            override val shuffle = res.getString(R.string.music_library_shuffle)

            override fun tracks(count: Int): String = res.getQuantityString(R.plurals.music_library_tracks, count, count)

            override val recentIcon = icon(R.drawable.music_library_recent)
            override val artistsIcon = icon(R.drawable.music_library_artists)
            override val playlistsIcon = icon(R.drawable.music_library_playlists)
            override val shuffleIcon = icon(R.drawable.music_library_shuffle)
        }
    }

    private fun icon(@DrawableRes id: Int): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(app.packageName)
        .appendPath(app.resources.getResourceTypeName(id))
        .appendPath(app.resources.getResourceEntryName(id))
        .build()

    private companion object {
        /** The pictures' width for the car: its grid's tiles and the art behind Now Playing. */
        const val ART_WIDTH = 512
    }
}
