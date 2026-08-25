package com.peernet.wifiextender.host

import android.content.Context
import com.peernet.wifiextender.discovery.HostIdentity
import com.peernet.wifiextender.wifi.GroupCredentialsPolicy

/**
 * The passphrase this phone shares with, and whether the user chose it.
 *
 * Kept separate from [GroupCredentialsPolicy] so the rules stay unit-testable:
 * this half is only storage.
 *
 * The passphrase is **stable by design**. Regenerating it per share is what
 * forced the user to retype it on the client every single time - Android had
 * saved credentials that no longer matched anything.
 */
object HostCredentials {

    private const val PREFS = "peernet_group"
    private const val KEY_PASSPHRASE = "passphrase"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The passphrase to host with: the user's if they set one, otherwise the
     * derived default.
     *
     * A stored value that fails validation is ignored rather than trusted - it
     * would otherwise make every future share fail with an opaque framework
     * error and no way for the user to recover except clearing app data.
     */
    fun passphrase(context: Context): String {
        val stored = prefs(context).getString(KEY_PASSPHRASE, null)
        if (!stored.isNullOrEmpty() && GroupCredentialsPolicy.isValid(stored)) return stored
        return GroupCredentialsPolicy.derivePassphrase(HostIdentity.id(context))
    }

    /** True when the passphrase in use came from the user, not from derivation. */
    fun isCustom(context: Context): Boolean {
        val stored = prefs(context).getString(KEY_PASSPHRASE, null)
        return !stored.isNullOrEmpty() && GroupCredentialsPolicy.isValid(stored)
    }

    /**
     * Stores a user-chosen passphrase, or clears it back to the derived default
     * when [value] is blank.
     *
     * Returns the rejection reason, or null on success.
     */
    fun setPassphrase(context: Context, value: String): String? {
        if (value.isBlank()) {
            prefs(context).edit().remove(KEY_PASSPHRASE).apply()
            return null
        }
        GroupCredentialsPolicy.rejection(value)?.let { return it }
        prefs(context).edit().putString(KEY_PASSPHRASE, value).apply()
        return null
    }

    fun networkName(context: Context): String =
        GroupCredentialsPolicy.networkName(HostIdentity.id(context))
}
