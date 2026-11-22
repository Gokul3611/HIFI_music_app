package com.stash.app.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.app.R
import com.stash.core.ui.theme.StashTheme

/**
 * Floating glass onboarding card prompting new users to sync their Spotify account.
 * Appears gently above the bottom chrome after users have explored the app for a short while (~25s).
 *
 * A single click anywhere on the card or the "Connect" button directly opens the Spotify login page.
 */
@Composable
fun SpotifyOnboardingPrompt(
    onConnectSpotify: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpotifyOnboardingViewModel = hiltViewModel(),
) {
    val showPrompt by viewModel.showPrompt.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = showPrompt,
        enter = fadeIn(animationSpec = tween(350)) + expandVertically(animationSpec = tween(350)),
        exit = fadeOut(animationSpec = tween(200)) + shrinkVertically(animationSpec = tween(200)),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable {
                    viewModel.onConnectClicked()
                    onConnectSpotify()
                },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 3.dp,
            border = BorderStroke(1.dp, StashTheme.extendedColors.glassBorder),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Branded Spotify Icon container
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(StashTheme.extendedColors.spotifyGreen.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(com.stash.core.ui.R.drawable.ic_brand_spotify),
                        contentDescription = null,
                        tint = StashTheme.extendedColors.spotifyGreen,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Copy
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_spotify_prompt_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.onboarding_spotify_prompt_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }

                // Primary Connect Button
                Button(
                    onClick = {
                        viewModel.onConnectClicked()
                        onConnectSpotify()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StashTheme.extendedColors.spotifyGreen,
                        contentColor = Color.White,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_spotify_prompt_action),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Dismiss Button
                IconButton(
                    onClick = viewModel::onDismissClicked,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.onboarding_spotify_prompt_dismiss),
                        tint = StashTheme.extendedColors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
