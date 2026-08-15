package barilyuk.mobilosignal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Single place that knows the on-disk settings format. */
object Prefs {

    const val FILE = "SIMSelection"

    const val KEY_SELECTED_SLOT = "selected_sim_slot"
    const val KEY_AUTO_START = "AutoStart"
    const val KEY_ICON_BLACK = "RadioChosenBlack"
    const val KEY_PERSISTENT_NOTIFICATION = "persistent_notification"
    const val KEY_DYNAMIC_COLOUR = "dynamic_colour"

    /**
     * Older builds stored the checked RadioButton's `R.id` here. Resource ids are not stable
     * across builds and that RadioGroup no longer exists, so the value is simply discarded: both
     * SIMs are displayed now, and the icon follows the strongest one.
     */
    private const val LEGACY_KEY_SELECTED_SIM = "selected_sim"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selectedSlot(context: Context): Int {
        val prefs = of(context)
        if (prefs.contains(LEGACY_KEY_SELECTED_SIM)) {
            prefs.edit { remove(LEGACY_KEY_SELECTED_SIM) }
        }
        return prefs.getInt(KEY_SELECTED_SLOT, 0)
    }

    fun setSelectedSlot(context: Context, slot: Int) {
        of(context).edit { putInt(KEY_SELECTED_SLOT, slot) }
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_AUTO_START, false)

    fun isIconTextBlack(context: Context): Boolean =
        of(context).getBoolean(KEY_ICON_BLACK, false)

    /** When off the app runs without a foreground service, i.e. only while it is open. */
    fun isPersistentNotificationEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_PERSISTENT_NOTIFICATION, true)

    fun isDynamicColourEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_DYNAMIC_COLOUR, true)
}
