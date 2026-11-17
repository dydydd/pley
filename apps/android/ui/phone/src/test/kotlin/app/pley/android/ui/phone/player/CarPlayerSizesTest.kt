package app.pley.android.ui.phone.player

import androidx.compose.ui.test.junit4.createComposeRule
import app.pley.android.player.PlayerPhase
import app.pley.android.player.PlayerUiState
import app.pley.android.player.QualityChoice
import app.pley.android.player.TrackChoice
import app.pley.android.player.subtitles.CaptionLook
import app.pley.android.testing.player.session.FakePlaybackController
import app.pley.android.ui.phone.theme.PhoneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The video player on each of the screens Android Auto runs on ([CarScreenChecks]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarPlayerSizesTest {
    @get:Rule
    val compose = createComposeRule()

    private val host = object : PlayerHost {
        override fun back() = Unit

        override val pipSupported = false

        override fun enterPip() = Unit

        override fun volume() = 0.5f

        override fun setVolume(level: Float) = Unit

        override fun brightness() = 0.5f

        override fun setBrightness(level: Float) = Unit
    }

    private fun check(screen: String) {
        val fake = FakePlaybackController(
            PlayerUiState(
                phase = PlayerPhase.Paused,
                itemId = 20_001,
                title = "Fixture Show",
                subtitle = "S1 E1 — Pilot",
                positionMs = 30_000,
                durationMs = 600_000,
                method = "Direct play",
                audio = listOf(TrackChoice(1, "English (AAC Stereo)", true), TrackChoice(2, "French (EAC3 5.1)", false)),
                subtitles = listOf(TrackChoice(null, "Off", false), TrackChoice(3, "English (SRT)", true)),
                quality = "Original",
                qualities = listOf(QualityChoice("original", "Original", null, true)),
            ),
        )
        compose.setContent {
            PhoneTheme {
                CarSized(car = true) {
                    PhonePlayerScreen(fake, host, PictureState(), CaptionLook(), SubtitleChoice(), {}, inPip = false)
                }
            }
        }
        CarScreenChecks.save(compose, "player-$screen")
        CarScreenChecks.controls(
            compose,
            screen,
            listOf("Back", "Playback settings", "Back 10 seconds", "Play", "Forward 10 seconds", "Position", "Subtitles", "Audio", "Next episode"),
        )
    }

    @Test
    @Config(qualifiers = CarScreenChecks.SMALL)
    fun smallLandscape() = check("small")

    @Test
    @Config(qualifiers = CarScreenChecks.WIDE)
    fun wideLandscape() = check("wide")

    @Test
    @Config(qualifiers = CarScreenChecks.PORTRAIT)
    fun portrait() = check("portrait")

    @Test
    @Config(qualifiers = CarScreenChecks.DENSE)
    fun dense() = check("dense")
}
