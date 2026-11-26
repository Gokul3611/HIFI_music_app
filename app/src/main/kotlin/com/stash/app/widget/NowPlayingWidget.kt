package com.stash.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import android.graphics.Bitmap
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import com.stash.app.R
import com.stash.core.media.PlaybackSessionBus
import com.stash.core.media.widget.WidgetButtons
import com.stash.core.media.widget.WidgetControls
import com.stash.core.media.widget.WidgetKeyRelay
import com.stash.core.media.widget.WidgetMediaIntents
import com.stash.core.media.widget.WidgetTransport
import com.stash.core.ui.theme.StashDarkColorScheme
import com.stash.core.ui.theme.StashLightColorScheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/** How the widget reaches the app's singletons (Glance builds the widget, not Hilt). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NowPlayingWidgetEntryPoint {
    fun nowPlayingWidgetStore(): NowPlayingWidgetStore
    fun playbackSessionBus(): PlaybackSessionBus
    fun widgetSink(): GlanceNowPlayingWidgetSink
}

/**
 * Stash's Hi-Fi home screen widget: ultra-low-power, bit-perfect audio player interface.
 * Displays only essential metadata (title, artist, Hi-Fi status) and full track controls
 * (Previous, Play/Pause, Next) without loading album art or opening the app activity.
 */
class NowPlayingWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val entryPoint = EntryPointAccessors.fromApplication(context.applicationContext, NowPlayingWidgetEntryPoint::class.java)
        val store = entryPoint.nowPlayingWidgetStore()
        val sessionBus = entryPoint.playbackSessionBus()
        val first = try {
            store.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            WidgetModel.Empty
        }
        provideContent {
            val model by store.models.collectAsState(initial = first)
            val serviceRunning by sessionBus.sessionAlive.collectAsState()
            GlanceTheme(colors = WidgetColors) {
                NowPlayingContent(model, serviceRunning)
            }
        }
    }
}

/** The app's own Material 3 schemes; Glance switches between them with the system's dark mode. */
private val WidgetColors = ColorProviders(light = StashLightColorScheme, dark = StashDarkColorScheme)

/** The widget card's corners: sleek 20 dp radius for a modern Hi-Fi console appearance. */
private val CardRadius = 20.dp

/** Taller than this, and not much wider than tall, and the widget draws its square shape. */
private val SquareMinHeight = 100.dp
private const val SQUARE_MAX_ASPECT = 1.6f

private fun isSquare(size: DpSize): Boolean =
    size.height >= SquareMinHeight && size.width.value <= size.height.value * SQUARE_MAX_ASPECT

@Composable
private fun NowPlayingContent(model: WidgetModel, serviceRunning: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val buttons = WidgetControls.of(model.state, serviceRunning)
    val state = model.state
    val title = state?.title ?: context.getString(R.string.widget_nothing_playing)
    val artist = state?.artist ?: context.getString(R.string.widget_open_stash)

    if (isSquare(size)) {
        SquareCard(title, artist, model.cover, buttons, model.accents)
    } else {
        WideCard(title, artist, model.cover, buttons, model.accents)
    }
}

/** The card background: never launches an activity on tap, keeping playback strictly headless. */
@Composable
private fun cardModifier(): GlanceModifier =
    GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .background(ImageProvider(R.drawable.widget_card), colorFilter = ColorFilter.tint(GlanceTheme.colors.surface))
        .cornerRadius(CardRadius)

@Composable
private fun WideCard(
    title: String,
    artist: String,
    cover: Bitmap?,
    buttons: WidgetButtons?,
    accents: WidgetAccents,
) {
    Row(
        modifier = cardModifier().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(cover = cover, size = 52.dp, cornerRadius = 14.dp)
        Spacer(GlanceModifier.width(12.dp))
        Column(
            modifier = GlanceModifier.defaultWeight().wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HiFiBadge()
            Spacer(GlanceModifier.height(1.dp))
            WidgetText(
                text = title,
                layout = R.layout.widget_text_title,
                light = StashLightColorScheme.onSurface,
                dark = StashDarkColorScheme.onSurface,
            )
            if (artist.isNotEmpty()) {
                Spacer(GlanceModifier.height(1.dp))
                WidgetText(
                    text = artist,
                    layout = R.layout.widget_text_artist,
                    light = StashLightColorScheme.onSurfaceVariant,
                    dark = StashDarkColorScheme.onSurfaceVariant,
                )
            }
        }
        if (buttons != null) {
            Spacer(GlanceModifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PreviousButton(buttons.previous, diameter = 34.dp, tint = GlanceTheme.colors.onSurface)
                Spacer(GlanceModifier.width(6.dp))
                PlayPauseButton(buttons, accents, diameter = 44.dp)
                Spacer(GlanceModifier.width(6.dp))
                NextButton(buttons.next, diameter = 34.dp, tint = GlanceTheme.colors.onSurface)
            }
        }
    }
}

@Composable
private fun SquareCard(
    title: String,
    artist: String,
    cover: Bitmap?,
    buttons: WidgetButtons?,
    accents: WidgetAccents,
) {
    Column(
        modifier = cardModifier().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverThumbnail(cover = cover, size = 76.dp, cornerRadius = 18.dp)
        Spacer(GlanceModifier.height(8.dp))
        Column(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HiFiBadge()
            Spacer(GlanceModifier.height(2.dp))
            WidgetText(
                text = title,
                layout = R.layout.widget_text_title_square,
                light = StashLightColorScheme.onSurface,
                dark = StashDarkColorScheme.onSurface,
            )
            if (artist.isNotEmpty()) {
                Spacer(GlanceModifier.height(2.dp))
                WidgetText(
                    text = artist,
                    layout = R.layout.widget_text_artist_square,
                    light = StashLightColorScheme.onSurfaceVariant,
                    dark = StashDarkColorScheme.onSurfaceVariant,
                )
            }
        }
        if (buttons != null) {
            Spacer(GlanceModifier.height(8.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviousButton(buttons.previous, diameter = 38.dp, tint = GlanceTheme.colors.onSurface)
                Spacer(GlanceModifier.width(14.dp))
                PlayPauseButton(buttons, accents, diameter = 48.dp)
                Spacer(GlanceModifier.width(14.dp))
                NextButton(buttons.next, diameter = 38.dp, tint = GlanceTheme.colors.onSurface)
            }
        }
    }
}

/** Minimalist album artwork thumbnail with squircle rounding or elegant fallback icon. */
@Composable
private fun CoverThumbnail(cover: Bitmap?, size: Dp, cornerRadius: Dp) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(cornerRadius),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            Image(
                provider = ImageProvider(cover),
                contentDescription = "Cover",
                modifier = GlanceModifier
                    .fillMaxSize()
                    .cornerRadius(cornerRadius),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(ImageProvider(R.drawable.widget_circle), colorFilter = ColorFilter.tint(GlanceTheme.colors.surfaceVariant))
                    .cornerRadius(cornerRadius),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.widget_cover_placeholder),
                    contentDescription = null,
                    modifier = GlanceModifier.size(size * 0.45f),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
    }
}

/** Audiophile badge indicating pure direct audio playback. */
@Composable
private fun HiFiBadge() {
    WidgetText(
        text = "HI-FI DIRECT",
        layout = R.layout.widget_text_badge,
        light = Color(0xFF0284C7),
        dark = Color(0xFF38BDF8),
    )
}

/** Previous track button: sends KEYCODE_MEDIA_PREVIOUS directly to the service. */
@Composable
private fun PreviousButton(transport: WidgetTransport, diameter: Dp, tint: androidx.glance.unit.ColorProvider) {
    val context = LocalContext.current
    val label = context.getString(R.string.widget_previous)
    Box(
        modifier = GlanceModifier
            .size(diameter)
            .cornerRadius(diameter / 2)
            .clickable(mediaAction(context, transport))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_ic_prev),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** Play / pause hero button: a distinct circular disc in the track's accent color. */
@Composable
private fun PlayPauseButton(buttons: WidgetButtons, accents: WidgetAccents, diameter: Dp) {
    val context = LocalContext.current
    val label = context.getString(if (buttons.showsPause) R.string.widget_pause else R.string.widget_play)
    Box(
        modifier = GlanceModifier
            .size(diameter)
            .background(
                ImageProvider(R.drawable.widget_circle),
                colorFilter = ColorFilter.tint(ColorProvider(day = Color(accents.accentLight), night = Color(accents.accentDark))),
            )
            .cornerRadius(diameter / 2)
            .clickable(mediaAction(context, buttons.playPause))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (buttons.showsPause) R.drawable.widget_ic_pause else R.drawable.widget_ic_play),
            contentDescription = null,
            modifier = GlanceModifier.size(diameter * 0.55f),
            colorFilter = ColorFilter.tint(ColorProvider(day = Color(accents.onAccentLight), night = Color(accents.onAccentDark))),
        )
    }
}

/** Next track button: sends KEYCODE_MEDIA_NEXT directly to the service. */
@Composable
private fun NextButton(transport: WidgetTransport, diameter: Dp, tint: androidx.glance.unit.ColorProvider) {
    val context = LocalContext.current
    val label = context.getString(R.string.widget_next)
    Box(
        modifier = GlanceModifier
            .size(diameter)
            .cornerRadius(diameter / 2)
            .clickable(mediaAction(context, transport))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(R.drawable.widget_ic_next),
            contentDescription = null,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/**
 * A media-button tap: starts the service in the foreground when stopped,
 * or relays through NowPlayingWidgetKeyReceiver while running.
 * Never starts an activity!
 */
private fun mediaAction(context: Context, transport: WidgetTransport): Action =
    if (transport.foreground) {
        actionStartService(WidgetMediaIntents.mediaButton(context, transport), isForegroundService = true)
    } else {
        actionSendBroadcast(WidgetKeyRelay.intent(context, NowPlayingWidgetKeyReceiver::class.java, transport))
    }

/** One line of text in the app's own custom fonts via RemoteViews layout. */
@Composable
private fun WidgetText(text: String, @LayoutRes layout: Int, light: Color, dark: Color) {
    val context = LocalContext.current
    val views = RemoteViews(context.packageName, layout).apply {
        setTextViewText(R.id.widget_text, text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorInt(R.id.widget_text, "setTextColor", light.toArgb(), dark.toArgb())
        } else {
            setTextColor(R.id.widget_text, (if (context.isNightMode()) dark else light).toArgb())
        }
    }
    AndroidRemoteViews(views, GlanceModifier.fillMaxWidth().wrapContentHeight())
}

private fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
