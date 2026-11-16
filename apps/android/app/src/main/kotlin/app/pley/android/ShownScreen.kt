package app.pley.android

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.os.Bundle
import android.view.Display
import androidx.core.content.ContextCompat

/**
 * The screen the app was last shown on: the phone's own, or a car's, where
 * Android Auto shows Pley while parked. The players open on it, so a video
 * picked on the car's screen plays there, not on the phone in a pocket.
 */
class ShownScreen : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var displayId = Display.DEFAULT_DISPLAY

    /** How to open a window on that screen; `null` for the phone's own. */
    fun launchOptions(): Bundle? {
        val id = displayId
        if (id == Display.DEFAULT_DISPLAY) return null
        return ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()
    }

    override fun onActivityResumed(activity: Activity) {
        displayId = ContextCompat.getDisplayOrDefault(activity).displayId
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
