package com.stash.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

        // 1. Enable the new target alias first so the app always has at least one active launcher entry
        val targetCandidates = listOf(
            ComponentName(packageName, targetIcon.aliasName),
            ComponentName(packageName, "$packageName.MainActivityAlias${targetIcon.aliasSuffix}"),
        )
        for (targetComponent in targetCandidates) {
            try {
                pm.setComponentEnabledSetting(
                    targetComponent,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (_: Exception) {
            }
        }

        // 2. Disable all other icon aliases
        for (icon in AppIcon.entries) {
            if (icon == targetIcon) continue
            val candidates = listOf(
                ComponentName(packageName, icon.aliasName),
                ComponentName(packageName, "$packageName.MainActivityAlias${icon.aliasSuffix}"),
            )
            for (component in candidates) {
                try {
                    pm.setComponentEnabledSetting(
                        component,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                } catch (_: Exception) {
                }
            }
        }

        // 3. Re-affirm widget receiver state so AppWidgetService and the launcher's widget picker
        // receive ACTION_PACKAGE_CHANGED for the widget providers, updating the search & picker icons.
        val widgetReceivers = listOf(
            ComponentName(packageName, "$packageName.widget.NowPlayingWidgetReceiver"),
            ComponentName(packageName, "com.stash.app.widget.NowPlayingWidgetReceiver"),
            ComponentName(packageName, "$packageName.widget.NowPlayingSquareWidgetReceiver"),
            ComponentName(packageName, "com.stash.app.widget.NowPlayingSquareWidgetReceiver"),
        )
        for (receiver in widgetReceivers) {
            try {
                pm.setComponentEnabledSetting(
                    receiver,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (_: Exception) {
            }
        }

        // 4. Broadcast update to AppWidgetManager so home screen widgets and system widget services redraw
        try {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            for (receiver in widgetReceivers) {
                val ids = appWidgetManager.getAppWidgetIds(receiver)
                if (ids.isNotEmpty()) {
                    val updateIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                        component = receiver
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(updateIntent)
                }
            }
        } catch (_: Exception) {
        }
    }
}

