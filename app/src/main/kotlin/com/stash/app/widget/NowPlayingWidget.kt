package com.stash.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
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
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import androidx.glance.unit.ColorProvider
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
 * Stash's Hi-Fi home screen widget redesigned with Jetpack Glance Canonical Layouts principles.
 * Adapts across responsive breakpoints (Toolbar layout for horizontal bars, Card layout for compact/square tiles).
 * Maintains ultra-low-power headless playback control without launching heavy Activities.
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
                NowPlayingCanonicalContent(model, serviceRunning)
            }
        }
    }
}

/** The app's Material 3 schemes mapped to Glance color providers. */
private val WidgetColors = ColorProviders(light = StashLightColorScheme, dark = StashDarkColorScheme)

/** Standard Glance Canonical Layout tokens. */
private val CanonicalWidgetPadding = 12.dp
private val CanonicalCardRadius = 24.dp
private val SquareMinHeight = 100.dp
private const val SQUARE_MAX_ASPECT = 1.55f

private fun isSquareAspect(size: DpSize): Boolean =
    size.height >= SquareMinHeight && size.width.value <= size.height.value * SQUARE_MAX_ASPECT

@Composable
private fun NowPlayingCanonicalContent(model: WidgetModel, serviceRunning: Boolean) {
    val context = LocalContext.current
    val size = LocalSize.current
    val buttons = WidgetControls.of(model.state, serviceRunning)
    val state = model.state
    val title = state?.title ?: context.getString(R.string.widget_nothing_playing)
    val artist = state?.artist ?: context.getString(R.string.widget_open_stash)

    Scaffold(
        backgroundColor = GlanceTheme.colors.widgetBackground,
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(CanonicalCardRadius),
    ) {
        if (isSquareAspect(size)) {
            CanonicalConsoleCard(
                title = title,
                artist = artist,
                buttons = buttons,
                accents = model.accents,
                size = size,
            )
        } else {
            CanonicalToolbarCard(
                title = title,
                artist = artist,
                buttons = buttons,
                accents = model.accents,
                size = size,
            )
        }
    }
}

/**
 * Wide / Toolbar Canonical Layout:
 * Presents Hi-Fi audio info and fluid toolbar controls following the Toolbar Canonical pattern.
 */
@Composable
private fun CanonicalToolbarCard(
    title: String,
    artist: String,
    buttons: WidgetButtons?,
    accents: WidgetAccents,
    size: DpSize,
) {
    val isCompactHeight = size.height < 64.dp

    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = if (isCompactHeight) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = GlanceModifier.defaultWeight().wrapContentHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!isCompactHeight) {
                HiFiBadge()
                Spacer(GlanceModifier.height(2.dp))
            }
            WidgetText(
                text = title,
                layout = R.layout.widget_text_title,
                light = StashLightColorScheme.onSurface,
                dark = StashDarkColorScheme.onSurface,
            )
            if (artist.isNotEmpty() && !isCompactHeight) {
                Spacer(GlanceModifier.height(2.dp))
                WidgetText(
                    text = artist,
                    layout = R.layout.widget_text_artist,
                    light = StashLightColorScheme.onSurfaceVariant,
                    dark = StashDarkColorScheme.onSurfaceVariant,
                )
            }
        }

        if (buttons != null) {
            Spacer(GlanceModifier.width(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.End,
            ) {
                // For compact width toolbar widgets, only primary action is shown
                if (size.width >= 210.dp) {
                    CanonicalCircleActionButton(
                        iconRes = R.drawable.widget_ic_prev,
                        contentDescription = LocalContext.current.getString(R.string.widget_previous),
                        action = mediaAction(LocalContext.current, buttons.previous),
                        size = 36.dp,
                        iconSize = 22.dp,
                        tint = GlanceTheme.colors.onSurface,
                    )
                    Spacer(GlanceModifier.width(6.dp))
                }

                CanonicalHeroPlayButton(
                    buttons = buttons,
                    accents = accents,
                    diameter = if (isCompactHeight) 40.dp else 44.dp,
                )

                if (size.width >= 210.dp) {
                    Spacer(GlanceModifier.width(6.dp))
                    CanonicalCircleActionButton(
                        iconRes = R.drawable.widget_ic_next,
                        contentDescription = LocalContext.current.getString(R.string.widget_next),
                        action = mediaAction(LocalContext.current, buttons.next),
                        size = 36.dp,
                        iconSize = 22.dp,
                        tint = GlanceTheme.colors.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Square / Console Canonical Layout:
 * Balanced vertical stack with Hi-Fi header, title/artist info, and centered controls.
 */
@Composable
private fun CanonicalConsoleCard(
    title: String,
    artist: String,
    buttons: WidgetButtons?,
    accents: WidgetAccents,
    size: DpSize,
) {
    val isSmallSquare = size.height <= 140.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = if (isSmallSquare) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HiFiBadge()
        Spacer(GlanceModifier.height(if (isSmallSquare) 4.dp else 8.dp))

        Column(
            modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WidgetText(
                text = title,
                layout = R.layout.widget_text_title_square,
                light = StashLightColorScheme.onSurface,
                dark = StashDarkColorScheme.onSurface,
            )
            if (artist.isNotEmpty()) {
                Spacer(GlanceModifier.height(3.dp))
                WidgetText(
                    text = artist,
                    layout = R.layout.widget_text_artist_square,
                    light = StashLightColorScheme.onSurfaceVariant,
                    dark = StashDarkColorScheme.onSurfaceVariant,
                )
            }
        }

        if (buttons != null) {
            Spacer(GlanceModifier.height(if (isSmallSquare) 6.dp else 10.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CanonicalCircleActionButton(
                    iconRes = R.drawable.widget_ic_prev,
                    contentDescription = LocalContext.current.getString(R.string.widget_previous),
                    action = mediaAction(LocalContext.current, buttons.previous),
                    size = if (isSmallSquare) 34.dp else 40.dp,
                    iconSize = if (isSmallSquare) 20.dp else 24.dp,
                    tint = GlanceTheme.colors.onSurface,
                )
                Spacer(GlanceModifier.width(if (isSmallSquare) 10.dp else 16.dp))
                CanonicalHeroPlayButton(
                    buttons = buttons,
                    accents = accents,
                    diameter = if (isSmallSquare) 44.dp else 50.dp,
                )
                Spacer(GlanceModifier.width(if (isSmallSquare) 10.dp else 16.dp))
                CanonicalCircleActionButton(
                    iconRes = R.drawable.widget_ic_next,
                    contentDescription = LocalContext.current.getString(R.string.widget_next),
                    action = mediaAction(LocalContext.current, buttons.next),
                    size = if (isSmallSquare) 34.dp else 40.dp,
                    iconSize = if (isSmallSquare) 20.dp else 24.dp,
                    tint = GlanceTheme.colors.onSurface,
                )
            }
        }
    }
}

/** Standard Canonical circular icon button for playback actions. */
@Composable
private fun CanonicalCircleActionButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    action: Action,
    size: Dp,
    iconSize: Dp,
    tint: ColorProvider,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .clickable(action)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = null,
            modifier = GlanceModifier.size(iconSize),
            colorFilter = ColorFilter.tint(tint),
        )
    }
}

/** Distinct hero Play/Pause action button styled according to track accents. */
@Composable
private fun CanonicalHeroPlayButton(
    buttons: WidgetButtons,
    accents: WidgetAccents,
    diameter: Dp,
) {
    val context = LocalContext.current
    val label = context.getString(if (buttons.showsPause) R.string.widget_pause else R.string.widget_play)
    val buttonBgProvider = androidx.glance.color.ColorProvider(
        day = Color(accents.accentLight),
        night = Color(accents.accentDark),
    )
    val buttonOnBgProvider = androidx.glance.color.ColorProvider(
        day = Color(accents.onAccentLight),
        night = Color(accents.onAccentDark),
    )

    Box(
        modifier = GlanceModifier
            .size(diameter)
            .background(
                ImageProvider(R.drawable.widget_circle),
                colorFilter = ColorFilter.tint(buttonBgProvider),
            )
            .cornerRadius(diameter / 2)
            .clickable(mediaAction(context, buttons.playPause))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(if (buttons.showsPause) R.drawable.widget_ic_pause else R.drawable.widget_ic_play),
            contentDescription = null,
            modifier = GlanceModifier.size(diameter * 0.54f),
            colorFilter = ColorFilter.tint(buttonOnBgProvider),
        )
    }
}

/** Audiophile badge indicating pure bit-perfect playback. */
@Composable
private fun HiFiBadge() {
    WidgetText(
        text = "HI-FI DIRECT",
        layout = R.layout.widget_text_badge,
        light = Color(0xFF0284C7),
        dark = Color(0xFF38BDF8),
    )
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

/** One line of text in the app's custom typography via RemoteViews layout. */
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
