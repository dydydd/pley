package app.pley.android.ui.phone.player

import android.app.Activity
import android.view.Display
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Whether a window shows on a screen other than the device's own: the
 * car's, where Android Auto shows Pley while parked, or another display
 * the phone drives. Such a screen keeps its orientation, has no picture
 * in picture, and a car covers its windows as it moves off: the players
 * don't turn it, don't shrink, and pause as they are covered.
 */
object OtherScreen {
    fun shows(activity: Activity): Boolean = ContextCompat.getDisplayOrDefault(activity).displayId != Display.DEFAULT_DISPLAY
}

/** The player draws for a car's screen ([CarSized]): fewer, larger controls. */
internal val LocalCarScreen = staticCompositionLocalOf { false }

/**
 * [content] as a car's screen wants it when [car] (Google's car app
 * quality criteria): a third larger, so the player's 48 dp buttons are
 * 64 dp there, and no text under 24 sp. A screen arm's length away, used
 * at a glance, with no room for a phone's small print.
 */
@Composable
internal fun CarSized(car: Boolean, content: @Composable () -> Unit) {
    if (!car) {
        content()
        return
    }
    val density = LocalDensity.current
    val type = MaterialTheme.typography
    val larger = remember(density) { Density(density.density * CAR_SCALE, density.fontScale) }
    val readable = remember(type) { type.atLeast((CAR_TEXT.value / CAR_SCALE).sp) }
    CompositionLocalProvider(LocalDensity provides larger, LocalCarScreen provides true) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = readable, shapes = MaterialTheme.shapes, content = content)
    }
}

/** How much larger the player draws on a car's screen: 48 dp to 64. */
internal const val CAR_SCALE = 64f / 48f

/** The smallest text on a car's screen. */
private val CAR_TEXT = 24.sp

/** How far the car's controls keep from the screen's edges and from each other, once scaled: 24 dp. */
internal val CarGap = (24f / CAR_SCALE).dp

/** Every style at [min] at least, its line height grown with it. */
private fun Typography.atLeast(min: TextUnit): Typography {
    fun TextStyle.grown(): TextStyle {
        if (!fontSize.isSpecified || fontSize.value >= min.value) return this
        val ratio = if (lineHeight.isSpecified) lineHeight.value / fontSize.value else 1.25f
        return copy(fontSize = min, lineHeight = (min.value * ratio).sp)
    }
    return Typography(
        displayLarge = displayLarge.grown(),
        displayMedium = displayMedium.grown(),
        displaySmall = displaySmall.grown(),
        headlineLarge = headlineLarge.grown(),
        headlineMedium = headlineMedium.grown(),
        headlineSmall = headlineSmall.grown(),
        titleLarge = titleLarge.grown(),
        titleMedium = titleMedium.grown(),
        titleSmall = titleSmall.grown(),
        bodyLarge = bodyLarge.grown(),
        bodyMedium = bodyMedium.grown(),
        bodySmall = bodySmall.grown(),
        labelLarge = labelLarge.grown(),
        labelMedium = labelMedium.grown(),
        labelSmall = labelSmall.grown(),
    )
}
