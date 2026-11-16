package app.pley.android.player.music.library

/**
 * The ids of the music library's nodes, as car screens and voice
 * assistants name them back ([MusicLibrary]): short, readable, and parsed
 * strictly. A track carries where it was picked from, so playing it
 * plays on through its album or playlist.
 */
sealed interface MusicId {
    val text: String

    data object Root : MusicId {
        override val text = "root"
    }

    /** Recently added albums, of every music library. */
    data object Recent : MusicId {
        override val text = "recent"
    }

    /** The artists, or with several music libraries, the libraries. */
    data object Artists : MusicId {
        override val text = "artists"
    }

    data object Playlists : MusicId {
        override val text = "playlists"
    }

    /** The artists of one music library, when there are several. */
    data class LibraryArtists(val libraryId: Long) : MusicId {
        override val text get() = "artists:$libraryId"
    }

    /** An artist's albums, with Shuffle above them. */
    data class Artist(val id: Long) : MusicId {
        override val text get() = "artist:$id"
    }

    /** All of an artist's tracks, shuffled. */
    data class ShuffleArtist(val id: Long) : MusicId {
        override val text get() = "shuffle:$id"
    }

    /** An album: its tracks, and played, all of them. */
    data class Album(val id: Long) : MusicId {
        override val text get() = "album:$id"
    }

    /** A music playlist: its tracks, and played, all of them. */
    data class Playlist(val id: Long) : MusicId {
        override val text get() = "playlist:$id"
    }

    /** A track, picked from its [from] (an album or a playlist), or alone (a search: its album plays on). */
    data class Track(val id: Long, val from: MusicId? = null) : MusicId {
        override val text get() = if (from == null) "track:$id" else "track:$id@${from.text}"
    }

    companion object {
        /** The id [text] names; `null` for anything else. */
        fun parse(text: String): MusicId? {
            when (text) {
                Root.text -> return Root
                Recent.text -> return Recent
                Artists.text -> return Artists
                Playlists.text -> return Playlists
            }
            val track = text.removePrefix("track:")
            if (track != text) {
                val at = track.indexOf('@')
                if (at < 0) return track.toIdOrNull()?.let { Track(it) }
                val id = track.substring(0, at).toIdOrNull() ?: return null
                val from = parse(track.substring(at + 1))
                return if (from is Album || from is Playlist) Track(id, from) else null
            }
            val colon = text.indexOf(':')
            if (colon < 0) return null
            val id = text.substring(colon + 1).toIdOrNull() ?: return null
            return when (text.substring(0, colon)) {
                "artists" -> LibraryArtists(id)
                "artist" -> Artist(id)
                "shuffle" -> ShuffleArtist(id)
                "album" -> Album(id)
                "playlist" -> Playlist(id)
                else -> null
            }
        }

        /** A positive id, digits only. */
        private fun String.toIdOrNull(): Long? =
            if (isEmpty() || length > 19 || any { it !in '0'..'9' }) null else toLongOrNull()?.takeIf { it > 0 }
    }
}
