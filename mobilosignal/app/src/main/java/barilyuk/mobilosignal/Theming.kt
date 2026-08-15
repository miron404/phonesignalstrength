package barilyuk.mobilosignal

import android.app.Activity
import android.content.Context
import com.google.android.material.color.DynamicColors

/** Wallpaper based colours (Material You), which the user can switch off. */
object Theming {

    fun isEnabled(context: Context): Boolean = Prefs.isDynamicColourEnabled(context)

    /**
     * Applies the dynamic colour overlay if it is both wanted and available. Call before
     * inflating: the overlay has to be on the theme by the time views are created.
     *
     * @return whether the overlay was requested, so the caller can rebuild itself if the
     * preference changes while it is on screen.
     */
    fun apply(activity: Activity): Boolean {
        val enabled = isEnabled(activity)
        if (enabled) DynamicColors.applyToActivityIfAvailable(activity)
        return enabled
    }
}
