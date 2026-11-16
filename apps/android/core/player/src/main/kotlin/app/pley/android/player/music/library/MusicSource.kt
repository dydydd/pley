package app.pley.android.player.music.library

import app.pley.android.data.DataGraph
import app.pley.android.data.Loadable
import app.pley.android.model.ItemSummary
import app.pley.android.model.Library
import app.pley.android.model.LibraryKind
import app.pley.android.model.LibrarySort
import app.pley.android.model.PlaylistDetail
import app.pley.android.model.PlaylistSummary
import app.pley.android.network.ApiException
import app.pley.android.network.NetworkGraph
import app.pley.android.network.PleyApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** No one is signed in: the car asks for Pley to be opened on the phone. */
class SignedOutException : IllegalStateException("Signed out")

/** What the music library is read from: the server signed in to now, as the profile sees it. */
interface MusicSource {
    /** The music libraries, in the profile's order. */
    suspend fun libraries(): List<Library>

    /** The albums added last to [libraryId], newest first. */
    suspend fun recentlyAdded(libraryId: Long): List<ItemSummary>

    /** [limit] artists of [libraryId] from [offset], by name. */
    suspend fun artists(libraryId: Long, offset: Int, limit: Int): List<ItemSummary>

    /** An artist's albums, an album's tracks. */
    suspend fun children(itemId: Long): List<ItemSummary>

    /** An artist, album or track; `null` when the profile can't see it. */
    suspend fun item(itemId: Long): ItemSummary?

    /** The profile's playlists, by title. */
    suspend fun playlists(): List<PlaylistSummary>

    suspend fun playlist(playlistId: Long): PlaylistDetail

    /** Artists, albums and tracks matching [query], closest first. */
    suspend fun search(query: String): List<ItemSummary>

    /** The server address of a picture's path ([PleyApi.url]), [width] pixels wide. */
    fun pictureUrl(path: String, width: Int): String
}

/**
 * [MusicSource] over the app's repositories, which keep what they last
 * read: a car out of signal browses what was seen before. The server's
 * answer is waited for briefly; what was saved is used when it is slow.
 */
class RepositoryMusicSource(private val data: DataGraph, private val network: NetworkGraph) : MusicSource {
    override suspend fun libraries(): List<Library> =
        data.libraries.libraries().settled().filter { it.kind == LibraryKind.Music }

    override suspend fun recentlyAdded(libraryId: Long): List<ItemSummary> =
        data.hubs.library(libraryId).settled().hubs.firstOrNull { it.key.startsWith(RECENTLY_ADDED) }?.items.orEmpty()

    override suspend fun artists(libraryId: Long, offset: Int, limit: Int): List<ItemSummary> =
        api().libraryItems(libraryId, sort = LibrarySort.Title, offset = offset, limit = limit).items

    override suspend fun children(itemId: Long): List<ItemSummary> = data.items.children(itemId).settled()

    override suspend fun item(itemId: Long): ItemSummary? = try {
        data.items.detail(itemId).settled().toSummary()
    } catch (e: ApiException) {
        if (e.status == 404) null else throw e
    }

    override suspend fun playlists(): List<PlaylistSummary> = data.playlists.playlists().settled()

    override suspend fun playlist(playlistId: Long): PlaylistDetail = data.playlists.playlist(playlistId).settled()

    override suspend fun search(query: String): List<ItemSummary> = data.search.search(query).settled().items

    override fun pictureUrl(path: String, width: Int): String = api().url(path, width)

    private fun api(): PleyApi {
        val session = data.session.session.value ?: throw SignedOutException()
        return network.api(session.issuer, session.me?.id)
    }

    /**
     * The server's answer, or after [FRESH_WAIT_MS] what was saved; a
     * failure with nothing saved throws it.
     */
    private suspend fun <T> Flow<Loadable<T>>.settled(): T {
        if (data.session.session.value == null) throw SignedOutException()
        var saved: T? = null
        val fresh = withTimeoutOrNull(FRESH_WAIT_MS) {
            first {
                if (it is Loadable.Ready && !it.fresh) saved = it.value
                (it is Loadable.Ready && it.fresh) || it is Loadable.Failed
            }
        }
        return when (fresh) {
            is Loadable.Ready -> fresh.value
            is Loadable.Failed -> fresh.stale ?: saved ?: throw fresh.error
            else -> saved ?: when (val late = first { it !is Loadable.Loading }) {
                is Loadable.Ready -> late.value
                is Loadable.Failed -> late.stale ?: throw late.error
                Loadable.Loading -> error("Still loading")
            }
        }
    }

    private companion object {
        /** A library's recently added row ("recently_added:<library id>"). */
        const val RECENTLY_ADDED = "recently_added"

        /** How long the server's answer is waited for when something is saved. */
        const val FRESH_WAIT_MS = 2_500L
    }
}
