package com.stash.feature.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.stash.core.model.AppIcon

/**
 * Manages runtime switching of the launcher app icon via Android `<activity-alias>`.
 */
object AppIconManager {

    /**
     * Enables the `<activity-alias>` corresponding to [targetIcon] and disables all other aliases.
     * Uses [PackageManager.DONT_KILL_APP] to avoid killing the app process during the switch.
     */
    fun setAppIcon(context: Context, targetIcon: AppIcon) {
        val pm = context.packageManager
        val packageName = context.packageName

        for (icon in AppIcon.entries) {
            val componentName = ComponentName(packageName, icon.aliasName)
            val desiredState = if (icon == targetIcon) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

            try {
                val currentState = pm.getComponentEnabledSetting(componentName)
                if (currentState != desiredState) {
                    pm.setComponentEnabledSetting(
                        componentName,
                        desiredState,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            } catch (_: Exception) {
                // Ignore if component is unavailable on custom OEM launchers
            }
        }
    }
}
