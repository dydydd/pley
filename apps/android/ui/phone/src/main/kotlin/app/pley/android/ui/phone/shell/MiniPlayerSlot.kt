package app.pley.android.ui.phone.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pley.android.designsystem.PleyColors
import app.pley.android.designsystem.PleyShape
import app.pley.android.designsystem.icon.Icon
import app.pley.android.designsystem.icon.PleyIcons
import app.pley.android.designsystem.image.ArtworkImage
import app.pley.android.player.MusicPosition
import app.pley.android.player.NowPlaying
import app.pley.android.screens.PleyKey
import app.pley.android.ui.phone.R
import app.pley.android.ui.phone.kit.PleyIconButton
import app.pley.android.ui.phone.music.TrackArt
import app.pley.android.ui.phone.music.TrackChange
import app.pley.android.ui.phone.music.rememberMusicControl
import app.pley.android.ui.phone.music.rememberMusicPosition
import app.pley.android.ui.phone.music.trackWords
import app.pley.android.ui.phone.player.CarGap
import app.pley.android.ui.phone.player.CarSized
import app.pley.android.ui.phone.player.LocalCarScreen
import app.pley.android.ui.phone.player.onCarScreen

/**
 * The mini player (design spec §3.6): a 72 dp bar above the navigation
 * bar while music plays: the 56 dp art, the title and "Artist · Album",
 * Play/Pause and Next, and a 2 dp [accent][PleyColors.Accent] line along
 * its top for how far the track has played (drawn, never recomposed).
 * Tapping it opens Now Playing; it hides while Now Playing shows. As the
 * track changes, the art and the words give way to the next one's as Now
 * Playing's do.
 */
@Composable
internal fun MiniPlayerSlot(nowPlaying: NowPlaying?, modifier: Modifier = Modifier, car: Boolean = onCarScreen()) {
    if (nowPlaying == null) return
    // On a car's screen, car-sized: 64 dp buttons, no text under 24 sp.
    CarSized(car) { MiniPlayer(nowPlaying, modifier) }
}

@Composable
private fun MiniPlayer(nowPlaying: NowPlaying, modifier: Modifier) {
    val navigator = LocalPhoneNavigator.current
    val onNowPlaying = (navigator as? PhoneNavigation)?.stack()?.lastOrNull() == PleyKey.NowPlaying
    if (onNowPlaying) return
    val music = rememberMusicControl()
    val position by rememberMusicPosition({ music?.position() ?: MusicPosition() }, nowPlaying.playing, key = nowPlaying.itemId to nowPlaying.index)
    val art = remember(nowPlaying.artUrl) { nowPlaying.artUrl?.let { ArtworkImage(listOf(it)) } }
    // What Play/Pause will do, a track still starting too.
    val playing = nowPlaying.wantsPlay
    // On a car's screen: 112 dp there, clear of its edges, its buttons apart and its title the larger line.
    val car = LocalCarScreen.current
    Row(
        modifier
            .fillMaxWidth()
            .height(if (car) 84.dp else 72.dp)
            .background(PleyColors.Surface2)
            .drawBehind {
                // How far along: 2 dp of accent over the surface's top edge.
                val h = 2.dp.toPx()
                val p = position
                val f = if (p.durationMs > 0) p.fraction else 0f
                drawLine(PleyColors.Surface4, Offset(0f, h / 2f), Offset(size.width, h / 2f), h)
                if (f > 0f) drawLine(PleyColors.Accent, Offset(0f, h / 2f), Offset(size.width * f, h / 2f), h)
            }
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.shell_now_playing)) { navigator.open(PleyKey.NowPlaying) }
            .padding(start = if (car) CarGap else 8.dp, end = if (car) CarGap else 4.dp)
            .testTag(MINI_PLAYER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(PleyShape.TilePhone).background(PleyColors.Surface3), contentAlignment = Alignment.Center) {
            if (art != null) {
                TrackArt(art, nowPlaying.album ?: nowPlaying.title, Modifier.matchParentSize())
            } else {
                Icon(PleyIcons.Music, contentDescription = null, tint = PleyColors.Muted)
            }
        }
        Spacer(Modifier.width(12.dp))
        TrackChange(trackWords(nowPlaying), Modifier.weight(1f), Alignment.CenterStart) { words ->
            Column(Modifier.fillMaxWidth()) {
                Text(words.title, style = if (car) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall, color = PleyColors.Fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (words.byline.isNotEmpty()) {
                    Text(words.byline, style = MaterialTheme.typography.bodySmall, color = PleyColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        PleyIconButton(
            if (playing) PleyIcons.Pause else PleyIcons.Play,
            stringResource(if (playing) R.string.music_pause else R.string.music_play),
            onClick = { music?.toggle() },
        )
        if (car) Spacer(Modifier.width(CarGap))
        PleyIconButton(
            PleyIcons.SkipForward,
            stringResource(R.string.music_next),
            onClick = { music?.next() },
            enabled = nowPlaying.hasNext,
        )
    }
}

/** The mini player's test tag. */
internal const val MINI_PLAYER = "pley.phone.mini_player"
