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

    /**
     * Older builds stored the checked RadioButton's `R.id` here. Resource ids are not stable
     * across builds, so the value is migrated once on a best-effort basis and then dropped.
     */
    private const val LEGACY_KEY_SELECTED_SIM = "selected_sim"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun selectedSlot(context: Context): Int {
        val prefs = of(context)
        if (!prefs.contains(KEY_SELECTED_SLOT) && prefs.contains(LEGACY_KEY_SELECTED_SIM)) {
            val legacy = prefs.getInt(LEGACY_KEY_SELECTED_SIM, 0)
            val slot = if (legacy == R.id.radioSim2) 1 else 0
            prefs.edit {
                putInt(KEY_SELECTED_SLOT, slot)
                remove(LEGACY_KEY_SELECTED_SIM)
            }
            return slot
        }
        return prefs.getInt(KEY_SELECTED_SLOT, 0)
    }

    fun setSelectedSlot(context: Context, slot: Int) {
        of(context).edit { putInt(KEY_SELECTED_SLOT, slot) }
    }

    fun isAutoStartEnabled(context: Context): Boolean =
        of(context).getBoolean(KEY_AUTO_START, false)

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        of(context).edit { putBoolean(KEY_AUTO_START, enabled) }
    }

    fun isIconTextBlack(context: Context): Boolean =
        of(context).getBoolean(KEY_ICON_BLACK, false)

    fun setIconTextBlack(context: Context, black: Boolean) {
        of(context).edit { putBoolean(KEY_ICON_BLACK, black) }
    }
}
