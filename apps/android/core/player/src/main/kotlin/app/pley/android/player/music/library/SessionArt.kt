package app.pley.android.player.music.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The covers the media session shows for what plays (the notification,
 * the lock screen, the system's media controls and a car's Now Playing),
 * kept once read. The session says what plays the moment the track
 * changes, with its cover only if it has it then; otherwise the cover
 * follows in a second update, and a car draws Now Playing without one in
 * between. A cover kept here is there at once: the next track of an album
 * has its album's, and [warm] reads the covers of the tracks around the
 * one playing ahead of them.
 *
 * It reads the library's pictures ([MusicArt]'s addresses) straight from
 * the server, as the app is allowed to; anything else goes to [other].
 * Main thread only.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class SessionArt(
    private val scope: CoroutineScope,
    /** The server path an address names, `null` when it isn't one of the library's ([MusicArt.pathOf]). */
    private val pathOf: (Uri) -> String?,
    /** The picture's bytes ([MusicLibraryGraph.picture]). */
    private val picture: suspend (path: String) -> ByteArray?,
    private val other: BitmapLoader,
    private val decode: (ByteArray) -> Bitmap? = ::decodeCover,
) : BitmapLoader {
    private val kept = LruCache<Uri, Bitmap>(KEPT)
    private val reading = HashMap<Uri, ListenableFuture<Bitmap>>()

    override fun supportsMimeType(mimeType: String): Boolean = other.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = other.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        kept.get(uri)?.let { return Futures.immediateFuture(it) }
        reading[uri]?.let { return it }
        val path = pathOf(uri) ?: return other.loadBitmap(uri)
        val future = SettableFuture.create<Bitmap>()
        reading[uri] = future
        scope.launch {
            val bitmap = try {
                picture(path)?.let { bytes -> withContext(Dispatchers.Default) { decode(bytes) } }
            } catch (e: CancellationException) {
                reading.remove(uri)
                future.cancel(false)
                throw e
            } catch (_: Exception) {
                null
            }
            reading.remove(uri)
            if (bitmap != null) {
                kept.put(uri, bitmap)
                future.set(bitmap)
            } else {
                // Not kept: asked again the next time it is shown.
                future.setException(IOException("No cover"))
            }
        }
        return future
    }

    /** Reads [uris]' covers ahead of their tracks; those kept or on their way already cost nothing. */
    fun warm(uris: List<Uri?>) {
        for (uri in uris) if (uri != null && pathOf(uri) != null) loadBitmap(uri)
    }

    /** Forgets every cover: another profile's are not this one's. */
    fun clear() = kept.evictAll()

    private companion object {
        /** The covers kept: the one playing, those around it, and a few albums back. */
        const val KEPT = 8
    }
}

/** A cover's picture, no larger than a car's Now Playing shows it; `null` when it isn't a picture. */
private fun decodeCover(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_PX) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** The server sends covers 512 px wide (MusicLibraryGraph); a larger one is halved until under twice that. */
private const val MAX_COVER_PX = 512
