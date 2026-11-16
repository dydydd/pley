package app.pley.android.ui.phone.player

import android.app.Activity
import android.view.Display
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
