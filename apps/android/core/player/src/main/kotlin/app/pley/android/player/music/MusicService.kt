package app.pley.android.player.music

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import app.pley.android.common.AppLanguage
import app.pley.android.network.OfflineException
import app.pley.android.player.PlayerGraphOwner
import app.pley.android.player.R
import app.pley.android.player.music.library.MusicArt
import app.pley.android.player.music.library.MusicCallers
import app.pley.android.player.music.library.MusicId
import app.pley.android.player.music.library.MusicLibrary
import app.pley.android.player.music.library.MusicLibraryGraph
import app.pley.android.player.music.library.MusicSelection
import app.pley.android.player.music.library.SignedOutException
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Music in the background (Android 17 plays background audio only from a
 * foreground service): a Media3 media library service, foreground with
 * type mediaPlayback while music plays, which shows the notification and
 * the lock screen's controls. It plays the app's one [MusicPlayback]
 * ([MusicHub]), its whole queue shown ([QueuePlayer]).
 *
 * Android Auto browses and plays Pley's music through it, as does the
 * Google app's Assistant ("play … on Pley"): the [MusicLibrary] (Recent,
 * Artists, Playlists, search), its pictures read through [MusicArt]. Signed
 * out, the car says so and opens Pley on the phone.
 *
 * Only the app itself, the system (its media controls, the notification,
 * Bluetooth and the lock screen), Media3's notification controller and
 * those two Google apps ([MusicCallers]) may connect: no other app drives
 * or reads Pley's music. A remote's Stop key pauses the music ([StopKey]).
 */
@OptIn(markerClass = [UnstableApi::class])
class MusicService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var player: QueuePlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What follows the session made last (its queue, the sign-in): ended with it. */
    private var following: Job? = null
    private val owner: PlayerGraphOwner? get() = application as? PlayerGraphOwner

    // The notification's words, Media3's included, and the car's, in the language shown.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        sessionOrMake()
    }

    // Who connects is decided in onConnect: a car's bind arrives here as Media3's stand-in for every
    // MediaBrowserCompat client, its real package checked only once it asks for the library's root.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = sessionOrMake()

    /**
     * The session over the process's music, made again after a stop that
     * no car outlived. A car can connect with nothing playing (it browses
     * first), so the session is made whenever the app's music is: the
     * graph is made for it if Android started the service in a process
     * without one.
     */
    private fun sessionOrMake(): MediaLibrarySession? {
        session?.let { return it }
        val graph = owner?.playerGraph
        // Asking for it makes the app's music, which puts its playback in MusicHub.
        graph?.music
        val playback = MusicHub.playback ?: return null
        val queue = queuePlayer(playback, graph?.musicLibrary?.library) ?: return null
        val made = MediaLibrarySession.Builder(this, queue, Callback(playback, graph?.musicLibrary))
            .setId(SESSION_ID)
            .apply { openApp()?.let(::setSessionActivity) }
            .build()
        session = made
        player = queue
        following = scope.launch {
            launch { playback.queueState.collect { player?.queueChanged() } }
            graph?.musicLibrary?.let { library -> launch { library.signedInChanges.drop(1).collect { tellCars(made, it) } } }
        }
        playback.onStopped = { stopped(made, playback, graph?.musicLibrary?.library) }
        return made
    }

    /**
     * The session's player over the music's output: one per output, kept
     * across the service's runs (each listens to the output for as long as
     * it lives, and the output outlives the service).
     */
    private fun queuePlayer(playback: MusicPlayback, library: MusicLibrary?): QueuePlayer? {
        val output = playback.output() as? EngineMusicOutput ?: return null
        made?.let { (over, player) -> if (over === output) return player }
        val player = if (library != null) QueuePlayer(output.player, playback) { library.queueItem(it) } else QueuePlayer(output.player, playback)
        made = output to player
        return player
    }

    /**
     * The music stopped (its output went with it). A car still connected
     * keeps the session, on a new output; otherwise the session goes and
     * the service stops, as it does on a phone.
     */
    private fun stopped(made: MediaLibrarySession, playback: MusicPlayback, library: MusicLibrary?) {
        if (session !== made) return
        val carStays = made.connectedControllers.any { it.packageName == MusicCallers.ANDROID_AUTO }
        val next = if (carStays) queuePlayer(playback, library) else null
        if (next != null) {
            made.player = next
            player = next
            return
        }
        end(made, playback)
    }

    /** The session goes, and the service stops: nothing plays, and no car holds it. */
    private fun end(made: MediaLibrarySession, playback: MusicPlayback) {
        if (session !== made) return
        playback.onStopped = null
        following?.cancel()
        following = null
        session = null
        player = null
        made.release()
        stopSelf()
    }

    override fun onDestroy() {
        MusicHub.playback?.onStopped = null
        scope.cancel()
        session?.release()
        session = null
        player = null
        super.onDestroy()
    }

    /**
     * Tapping the notification opens the app as its icon does: the app's
     * task comes back as it was, with no second window over it (on a TV,
     * MainActivity hands over to the TV's window already open).
     */
    private fun openApp(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Whether [controller] may connect: the app, the system's controllers, Media3's notification, Android Auto and the Google app. */
    private fun allowed(controller: MediaSession.ControllerInfo, session: MediaSession?): Boolean =
        controller.packageName == packageName ||
            (session != null && session.isMediaNotificationController(controller)) ||
            controller.isTrusted ||
            isCar(controller.packageName)

    private fun isCar(packageName: String): Boolean = packageName != this.packageName && MusicCallers.allowed(packageManager, packageName)

    /** Signed out, each car shows why it plays nothing, and a button opening Pley on the phone; signed in again, it clears. */
    private fun tellCars(made: MediaLibrarySession, signedIn: Boolean) {
        // Cars read the platform session's state, which Media3 makes from its notification controller's.
        made.setPlaybackException(made.mediaNotificationControllerInfo ?: return, if (signedIn) null else signedOutError())
        made.notifyChildrenChanged(MusicId.Root.text, 0, null)
    }

    private fun signedOutError(): PlaybackException {
        val res = AppLanguage.resources(this)
        val extras = Bundle().apply {
            putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, res.getString(R.string.music_library_open_pley))
            openApp()?.let { putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, it) }
        }
        return PlaybackException(res.getString(R.string.music_library_signed_out), null, PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED, extras)
    }

    /** The session's answers: who may connect, the library, and what a car or the Assistant asks to play. */
    private inner class Callback(
        private val playback: MusicPlayback,
        private val graph: MusicLibraryGraph?,
    ) : MediaLibrarySession.Callback {
        private val stop = StopKey(playback::pause)
        private val searches = HashMap<String, List<MediaItem>>()

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (!allowed(controller, session)) return MediaSession.ConnectionResult.reject()
            if (isCar(controller.packageName)) {
                // The car reads the library's pictures from the app (MusicArt): it may read them, and only them.
                runCatching {
                    grantUriPermission(
                        controller.packageName,
                        Uri.parse("content://${MusicArt.authority(this@MusicService)}"),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                    )
                }
            }
            return super.onConnect(session, controller)
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            val library = graph ?: return
            if (isCar(controller.packageName) && !library.signedIn()) tellCars(session as MediaLibrarySession, signedIn = false)
        }

        /** The last car gone with nothing playing: the session and the service go, as after a stop. */
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (!isCar(controller.packageName) || playback.active) return
            if (session.connectedControllers.none { it != controller && isCar(it.packageName) }) end(session as MediaLibrarySession, playback)
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
            stop.handle(intent) || super.onMediaButtonEvent(session, controllerInfo, intent)

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val library = graph?.library ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED))
            // Lists by default; the grids of albums and playlists say so themselves.
            val extras = Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            }
            return Futures.immediateFuture(LibraryResult.ofItem(library.root(), LibraryParams.Builder().setExtras(extras).build()))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            answer {
                val id = MusicId.parse(mediaId) ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                library().item(id)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
            val id = MusicId.parse(parentId) ?: return@answer LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(library().children(id, page, pageSize), params)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
            answer {
                val found = try {
                    library().search(query.take(MAX_QUERY))
                } catch (e: Exception) {
                    // A car waits for this, failed too: an empty result.
                    if (e !is CancellationException) session.notifySearchResultChanged(browser, query, 0, params)
                    throw e
                }
                searches.clear()
                searches[query] = found
                session.notifySearchResultChanged(browser, query, found.size, params)
                LibraryResult.ofVoid()
            }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = answer {
            val found = searches[query] ?: library().search(query.take(MAX_QUERY))
            val size = pageSize.coerceIn(1, MusicLibrary.MAX_PAGE)
            LibraryResult.ofItemList(found.drop(page * size).take(size), params)
        }

        /**
         * A car picked something to play, or the Assistant heard what to
         * play: the music starts here, and the session is told its queue.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val picked = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            if (picked == null || graph == null) return Futures.immediateFailedFuture(UnsupportedOperationException())
            scope.launch {
                try {
                    val selection = selectionFor(picked) ?: throw UnsupportedOperationException("Nothing to play")
                    playback.play(selection.tracks, selection.startIndex, selection.shuffle)
                    val queue = playback.queueState.value
                    val items = queue.tracks.map { library().queueItem(it) }
                    future.set(MediaSession.MediaItemsWithStartPosition(items, queue.index, C.TIME_UNSET))
                } catch (e: CancellationException) {
                    future.cancel(false)
                    throw e
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        /**
         * Play from the system's media controls or a car with nothing
         * loaded: the queue the music still holds, where it was. A process
         * started afresh holds none, and offers nothing to resume.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val queue = playback.queueState.value
            if (queue.tracks.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            val items = queue.tracks.map { graph?.library?.queueItem(it) ?: QueuePlayer.plainItem(it) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, queue.index, playback.position().positionMs))
        }

        // A queue isn't added to from outside: what a car picks replaces it (onSetMediaItems).
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFailedFuture(UnsupportedOperationException())

        private suspend fun selectionFor(item: MediaItem): MusicSelection? {
            val query = item.requestMetadata.searchQuery
            return when {
                item.mediaId.isNotEmpty() -> MusicId.parse(item.mediaId)?.let { library().selection(it) }
                query != null -> library().selectionFor(query.take(MAX_QUERY))
                else -> null
            }
        }

        private fun library(): MusicLibrary = graph?.library ?: throw UnsupportedOperationException()

        /** [block]'s result, a failure as the car shows it: signed out, offline, or anything else. */
        private fun <T : Any> answer(block: suspend () -> LibraryResult<T>): ListenableFuture<LibraryResult<T>> {
            val future = SettableFuture.create<LibraryResult<T>>()
            scope.launch {
                val result = try {
                    block()
                } catch (e: CancellationException) {
                    future.cancel(false)
                    throw e
                } catch (e: Exception) {
                    errorOf(e)
                }
                future.set(result)
            }
            return future
        }

        private fun <T : Any> errorOf(e: Exception): LibraryResult<T> {
            val res = AppLanguage.resources(this@MusicService)
            return when (e) {
                is SignedOutException -> LibraryResult.ofError<T>(SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, res.getString(R.string.music_library_signed_out)))
                is OfflineException -> LibraryResult.ofError<T>(SessionError(SessionError.ERROR_IO, res.getString(R.string.music_library_offline)))
                else -> LibraryResult.ofError<T>(SessionError(SessionError.ERROR_UNKNOWN, res.getString(R.string.music_library_failed)))
            }
        }
    }

    private companion object {
        const val SESSION_ID = "pley.music"

        /** The session's player and the output it shows, kept for the process's life (queuePlayer). */
        var made: Pair<EngineMusicOutput, QueuePlayer>? = null

        /** The longest search a car or the Assistant sends that is used. */
        const val MAX_QUERY = 200
    }
}

/**
 * The Stop key of a TV remote or a headset, as the Samsung app has it: the
 * music pauses and keeps its queue, where Media3 would stop it and forget
 * the queue (Now Playing would leave the rail). The notification swiped
 * away comes as Stop too, marked as its dismissal: that still ends the music.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class StopKey(private val pause: () -> Unit) {
    /** Whether [intent], a media button, is the Stop key, handled here. */
    fun handle(intent: Intent): Boolean {
        val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
        if (key.keyCode != KeyEvent.KEYCODE_MEDIA_STOP) return false
        if (intent.getBooleanExtra(MediaNotification.NOTIFICATION_DISMISSED_EVENT_KEY, false)) return false
        // The key's release and its repeats are handled too, and change nothing.
        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) pause()
        return true
    }
}

/**
 * The process's music, for the service: the player graph puts its
 * [MusicPlayback] here when it makes it. A service the system starts in a
 * process without one (after process death) has nothing to play.
 */
object MusicHub {
    @Volatile
    var playback: MusicPlayback? = null
}
