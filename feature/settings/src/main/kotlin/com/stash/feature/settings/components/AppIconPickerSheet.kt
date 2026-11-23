package com.stash.feature.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.stash.core.model.AppIcon
import com.stash.core.ui.components.sheetBarsFollow
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.settings.R

/**
 * Visual thumbnail preview for an [AppIcon] variant.
 *
 * Renders the custom dark minimalist vinyl grooved squircle and the
 * beamed double musical noteheads tinted in the variant's accent color.
 */
@Composable
fun AppIconThumbnail(
    icon: AppIcon,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    isSelected: Boolean = false,
) {
    val cornerRadius = size * 0.22f
    val shape = RoundedCornerShape(cornerRadius)
    val accentColor = Color(icon.accentHex)
    val bgColor = Color(icon.bgHex)

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(bgColor)
            .border(
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) accentColor else accentColor.copy(alpha = 0.25f),
                ),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val s = minOf(w, h) / 108f

            // 1. Vinyl grooves: concentric circles centered at (54, 54)
            val centerOffset = Offset(54f * s, 54f * s)
            val grooveStroke = Stroke(width = maxOf(1f, 0.8f * s))
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = 40f * s,
                center = centerOffset,
                style = grooveStroke,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = 32f * s,
                center = centerOffset,
                style = grooveStroke,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.05f),
                radius = 24f * s,
                center = centerOffset,
                style = grooveStroke,
            )

            // 2. Beamed double notehead glyph (matching ic_launcher_foreground.xml)
            // Left notehead
            drawOval(
                color = accentColor,
                topLeft = Offset((36f - 9f) * s, (75f - 6f) * s),
                size = Size(18f * s, 12f * s),
            )
            // Right notehead
            drawOval(
                color = accentColor,
                topLeft = Offset((72f - 9f) * s, (69f - 6f) * s),
                size = Size(18f * s, 12f * s),
            )

            // Stems and beams
            val stemStroke = Stroke(
                width = 6.6f * s,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )
            // Left stem: (45, 75) -> (45, 33)
            drawLine(
                color = accentColor,
                start = Offset(45f * s, 75f * s),
                end = Offset(45f * s, 33f * s),
                strokeWidth = stemStroke.width,
                cap = stemStroke.cap,
            )
            // Right stem: (81, 69) -> (81, 27)
            drawLine(
                color = accentColor,
                start = Offset(81f * s, 69f * s),
                end = Offset(81f * s, 27f * s),
                strokeWidth = stemStroke.width,
                cap = stemStroke.cap,
            )
            // Top beam: (45, 33) -> (81, 27)
            drawLine(
                color = accentColor,
                start = Offset(45f * s, 33f * s),
                end = Offset(81f * s, 27f * s),
                strokeWidth = stemStroke.width,
                cap = stemStroke.cap,
            )
            // Lower beam: (45, 45) -> (81, 39)
            drawLine(
                color = accentColor,
                start = Offset(45f * s, 45f * s),
                end = Offset(81f * s, 39f * s),
                strokeWidth = stemStroke.width,
                cap = stemStroke.cap,
            )
        }
    }
}

/**
 * Bottom sheet displaying all available [AppIcon] themes with rich visual
 * preview thumbnails, titles, descriptions, and current selection indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppIconPickerSheet(
    currentIcon: AppIcon,
    onSelect: (AppIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = StashTheme.extendedColors.elevatedSurface,
        properties = sheetBarsFollow(StashTheme.extendedColors.elevatedSurface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_app_icon_sheet_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = SettingsRowPadH + 5.dp, vertical = 6.dp),
            )
            Text(
                text = stringResource(R.string.settings_app_icon_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SettingsRowPadH + 5.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(AppIcon.entries, key = { it.id }) { icon ->
                    val isSelected = icon == currentIcon
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(icon)
                                onDismiss()
                            }
                            .padding(horizontal = SettingsRowPadH + 5.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconThumbnail(
                            icon = icon,
                            size = 52.dp,
                            isSelected = isSelected,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = icon.title,
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = icon.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color(icon.accentHex),
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            ),
                        )
                    }
                }
            }
        }
    }
}
