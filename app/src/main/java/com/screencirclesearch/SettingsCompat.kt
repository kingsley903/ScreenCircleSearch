package com.screencirclesearch

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for the two special permissions the app needs, plus the intents that
 * take the user to the right settings screen.
 */
object SettingsCompat {

    /**
     * True when the user granted "Display over other apps". This is checked
     * every time before an overlay window is added, because the permission can
     * be revoked at any moment.
     */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * True when our AccessibilityService is enabled in the system settings.
     *
     * There is no public API for this, so the list of enabled services is read
     * from Settings.Secure and matched against our component name.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, CircleSearchService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component == expected) return true
        }
        return false
    }

    /** Opens the per-app "Display over other apps" screen. */
    fun overlaySettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Opens the accessibility settings list. Android 11 has no deep link to a
     * single service from a non-system app, so the user picks ours from the
     * list (it is labelled "ScreenCircleSearch").
     */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the app details page (also used to re-grant permissions). */
    fun appDetailsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
}
