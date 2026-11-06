package barilyuk.mobilosignal

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Single place that knows the on-disk settings format. */
object Prefs {

    const val FILE = "SIMSelection"

    const val KEY_AUTO_START = "AutoStart"
    const val KEY_ICON_BLACK = "RadioChosenBlack"
    const val KEY_PERSISTENT_NOTIFICATION = "persistent_notification"
    const val KEY_DYNAMIC_COLOUR = "dynamic_colour"
    const val KEY_STATUS_BAR_SIM = "status_bar_sim"

    /** Value of [KEY_STATUS_BAR_SIM] for "follow whichever SIM is receiving better". */
    const val STATUS_BAR_SIM_AUTO = "auto"

    private const val KEY_PHONE_PERMISSION_ASKED = "phone_permission_asked"
    private const val KEY_NOTIFICATION_PROMPT_SHOWN = "notification_prompt_shown"

    /**
     * Keys older builds wrote and nothing reads any more. "selected_sim" held a RadioButton's
     * `R.id`, which is not stable across builds; "selected_sim_slot" was an Int, which a
     * ListPreference could not read, so the status bar choice lives under a new key.
     */
    private val LEGACY_KEYS = listOf("selected_sim", "selected_sim_slot")

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Drops keys left behind by older builds. Cheap when there is nothing to do. */
    fun migrate(context: Context) {
        val prefs = of(context)
        val stale = LEGACY_KEYS.filter { prefs.contains(it) }
        if (stale.isNotEmpty()) prefs.edit { stale.forEach { remove(it) } }
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

    /** The slot whose reading goes into the status bar icon, or null to follow the strongest. */
    fun statusBarSlot(context: Context): Int? =
        of(context).getString(KEY_STATUS_BAR_SIM, STATUS_BAR_SIM_AUTO)?.toIntOrNull()

    /**
     * Whether READ_PHONE_STATE has been requested before. Together with
     * shouldShowRequestPermissionRationale() this tells "never asked" from "denied for good",
     * which Android otherwise reports identically.
     */
    fun wasPhonePermissionAsked(context: Context): Boolean =
        of(context).getBoolean(KEY_PHONE_PERMISSION_ASKED, false)

    fun setPhonePermissionAsked(context: Context) {
        of(context).edit { putBoolean(KEY_PHONE_PERMISSION_ASKED, true) }
    }

    /**
     * Whether the user has already been told why the notification permission matters. After
     * that the app stops asking on launch; the settings screen is where it can be allowed later.
     */
    fun wasNotificationPromptShown(context: Context): Boolean =
        of(context).getBoolean(KEY_NOTIFICATION_PROMPT_SHOWN, false)

    fun setNotificationPromptShown(context: Context) {
        of(context).edit { putBoolean(KEY_NOTIFICATION_PROMPT_SHOWN, true) }
    }
}
