package com.peernet.wifiextender.discovery

import android.content.Context

/** Persistent per-install host identity shared by advertiser and link server. */
object HostIdentity {

    private const val PREFS = "peernet_host_identity"
    private const val KEY_HOST_ID = "host_id"

    fun id(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOST_ID, null)
            ?: java.util.UUID.randomUUID().toString().replace("-", "").take(16).also {
                prefs.edit().putString(KEY_HOST_ID, it).apply()
            }
    }

    fun shortId(context: Context): String = id(context).takeLast(4)
}
