package com.ridesharefarecalc.overlay

import android.content.Context

object NavPreference {
    const val WAZE_PACKAGE = "com.waze"
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    private const val PREFS_NAME = "fare_overlay_prefs"
    private const val KEY_PREFERRED_APP = "preferred_nav_app"

    fun getPreferredPackage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PREFERRED_APP, WAZE_PACKAGE) ?: WAZE_PACKAGE
    }

    fun setPreferredPackage(context: Context, packageName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED_APP, packageName)
            .apply()
    }

    /** Preferred package first, then the other known nav app as a fallback. */
    fun candidatePackages(context: Context): List<String> {
        val preferred = getPreferredPackage(context)
        val fallback = if (preferred == WAZE_PACKAGE) GOOGLE_MAPS_PACKAGE else WAZE_PACKAGE
        return listOf(preferred, fallback)
    }
}
