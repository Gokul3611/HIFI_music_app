package com.stash.app.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.spotify.SpotifyAccounts
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manages the timing and visibility of the Spotify onboarding sync prompt.
 *
 * For new users installing the app:
 * - Gives them time to explore the app first (~25 seconds of active use), not popping up immediately.
 * - If they have already connected their Spotify account, or dismissed the prompt previously,
 *   it never prompts them.
 * - Single click marks the prompt as handled and redirects directly to Spotify sign-in.
 */
@HiltViewModel
class SpotifyOnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spotifyAccounts: SpotifyAccounts,
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _showPrompt = MutableStateFlow(false)
    val showPrompt: StateFlow<Boolean> = _showPrompt.asStateFlow()

    init {
        checkAndSchedulePrompt()
    }

    private fun checkAndSchedulePrompt() {
        val alreadyDismissed = prefs.getBoolean(KEY_PROMPT_DISMISSED, false)
        val isConnected = spotifyAccounts.spotifyAuthState.value is AuthState.Connected

        if (alreadyDismissed || isConnected) {
            return
        }

        viewModelScope.launch {
            // Observe auth state changes: if the user connects Spotify anywhere, dismiss the prompt
            launch {
                spotifyAccounts.spotifyAuthState.collect { state ->
                    if (state is AuthState.Connected) {
                        markDismissed()
                        _showPrompt.value = false
                    }
                }
            }

            // Record first launch timestamp to track exploration duration
            val firstLaunchTime = prefs.getLong(KEY_FIRST_LAUNCH_TIME, 0L).let { stored ->
                if (stored == 0L) {
                    val now = System.currentTimeMillis()
                    prefs.edit().putLong(KEY_FIRST_LAUNCH_TIME, now).apply()
                    now
                } else {
                    stored
                }
            }

            val elapsed = System.currentTimeMillis() - firstLaunchTime
            val remainingDelay = (EXPLORATION_DELAY_MS - elapsed).coerceAtLeast(INITIAL_GRACE_PERIOD_MS)

            delay(remainingDelay)

            // Re-validate state after delay before presenting
            if (!prefs.getBoolean(KEY_PROMPT_DISMISSED, false) &&
                spotifyAccounts.spotifyAuthState.value !is AuthState.Connected
            ) {
                _showPrompt.value = true
            }
        }
    }

    fun onConnectClicked() {
        markDismissed()
        _showPrompt.value = false
    }

    fun onDismissClicked() {
        markDismissed()
        _showPrompt.value = false
    }

    private fun markDismissed() {
        prefs.edit().putBoolean(KEY_PROMPT_DISMISSED, true).apply()
    }

    companion object {
        const val PREFS_NAME = "spotify_onboarding_prefs"
        const val KEY_PROMPT_DISMISSED = "spotify_prompt_dismissed"
        const val KEY_FIRST_LAUNCH_TIME = "first_launch_time_ms"

        /** Let the new user explore for 25 seconds before presenting the onboarding sync prompt. */
        const val EXPLORATION_DELAY_MS = 25_000L

        /** Grace period on subsequent launches so the UI renders smoothly before showing prompt. */
        const val INITIAL_GRACE_PERIOD_MS = 3_000L
    }
}
