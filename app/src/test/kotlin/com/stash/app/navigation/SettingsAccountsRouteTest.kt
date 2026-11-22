package com.stash.app.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SettingsAccountsRoute] parameters and serialization.
 * Verifies that the new [connectSpotify] flag defaults to false and correctly
 * preserves its state across type-safe navigation serialization.
 */
class SettingsAccountsRouteTest {

    @Test
    fun `default route parameters have cloud and connectSpotify false`() {
        val route = SettingsAccountsRoute()
        assertFalse(route.cloud)
        assertFalse(route.connectSpotify)
    }

    @Test
    fun `explicit connectSpotify parameter initializes correctly`() {
        val route = SettingsAccountsRoute(connectSpotify = true)
        assertFalse(route.cloud)
        assertTrue(route.connectSpotify)
    }

    @Test
    fun `connectSpotify flag survives serialization both ways`() {
        val cases = listOf(
            SettingsAccountsRoute(),
            SettingsAccountsRoute(cloud = true),
            SettingsAccountsRoute(connectSpotify = true),
            SettingsAccountsRoute(cloud = true, connectSpotify = true),
        )

        for (route in cases) {
            val json = Json.encodeToString(SettingsAccountsRoute.serializer(), route)
            val decoded = Json.decodeFromString(SettingsAccountsRoute.serializer(), json)
            assertEquals(route, decoded)
        }
    }
}
