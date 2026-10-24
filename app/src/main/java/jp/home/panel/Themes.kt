package jp.home.panel

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 配色パターン（iPad 版 web/style.css の data-theme と同じ色） */
data class Palette(
    val id: String,
    val name: String,
    val dark: Boolean,
    val primary: Long,
    val onPrimary: Long,
    val container: Long,
    val onContainer: Long,
    val background: Long,
    val surface: Long,
    val surfaceVariant: Long,
    val onSurface: Long,
    val onSurfaceVariant: Long,
)

val PALETTES = listOf(
    Palette("orange", "オレンジ", true, 0xFFFFB74D, 0xFF3E2400, 0xFF5D4020, 0xFFFFE0B2, 0xFF141218, 0xFF1D1B20, 0xFF2B2930, 0xFFE6E1E5, 0xFFCAC4D0),
    Palette("blue", "ブルー", true, 0xFF90CAF9, 0xFF0B2A4A, 0xFF1E3A5F, 0xFFD6E8FF, 0xFF0F1418, 0xFF161D23, 0xFF1F2A33, 0xFFE1E6EC, 0xFFC0CAD4),
    Palette("green", "グリーン", true, 0xFFA5D6A7, 0xFF0B3311, 0xFF1F4D2B, 0xFFD7F5D8, 0xFF0F1511, 0xFF161D18, 0xFF1F2B22, 0xFFE0E6E0, 0xFFC1CCC1),
    Palette("purple", "パープル", true, 0xFFCE93D8, 0xFF3A0E45, 0xFF4A2655, 0xFFF6D9FF, 0xFF151218, 0xFF1D1921, 0xFF2A2430, 0xFFE8E0EA, 0xFFCDC3D1),
    Palette("red", "レッド", true, 0xFFEF9A9A, 0xFF4A0E0E, 0xFF5C2222, 0xFFFFDAD6, 0xFF181212, 0xFF201919, 0xFF302424, 0xFFEDE0DE, 0xFFD6C3C1),
    Palette("teal", "ティール", true, 0xFF80CBC4, 0xFF003732, 0xFF1C4A47, 0xFFCFF5F1, 0xFF0E1515, 0xFF151D1C, 0xFF1D2B2A, 0xFFDFE6E5, 0xFFBFCBC9),
    Palette("mono", "モノクロ", true, 0xFFE0E0E0, 0xFF1A1A1A, 0xFF424242, 0xFFFAFAFA, 0xFF101010, 0xFF181818, 0xFF262626, 0xFFE6E6E6, 0xFFBDBDBD),
    Palette("night", "ナイト（暗め）", true, 0xFFB0855A, 0xFF1F1408, 0xFF3A2817, 0xFFD9BFA0, 0xFF000000, 0xFF0A0A0A, 0xFF141210, 0xFFA89F96, 0xFF8A827A),
    Palette("light_orange", "ライト・オレンジ", false, 0xFFE65100, 0xFFFFFFFF, 0xFFFFE0B2, 0xFF3E2400, 0xFFFFF8F3, 0xFFFFFFFF, 0xFFF3E9E0, 0xFF1F1B16, 0xFF51453A),
    Palette("light_blue", "ライト・ブルー", false, 0xFF1565C0, 0xFFFFFFFF, 0xFFD6E8FF, 0xFF0B2A4A, 0xFFF7F9FC, 0xFFFFFFFF, 0xFFE3EAF2, 0xFF191C20, 0xFF42474E),
    Palette("light_green", "ライト・グリーン", false, 0xFF2E7D32, 0xFFFFFFFF, 0xFFD7F5D8, 0xFF0B3311, 0xFFF6FBF5, 0xFFFFFFFF, 0xFFE2EDE2, 0xFF191D19, 0xFF424942),
)

fun paletteOf(id: String?): Palette = PALETTES.firstOrNull { it.id == id } ?: PALETTES.first()

fun Palette.colorScheme(): ColorScheme {
    val c = { v: Long -> Color(v) }
    return if (dark) {
        darkColorScheme(
            primary = c(primary), onPrimary = c(onPrimary),
            primaryContainer = c(container), onPrimaryContainer = c(onContainer),
            secondaryContainer = c(container), onSecondaryContainer = c(onContainer),
            background = c(background), onBackground = c(onSurface),
            surface = c(surface), onSurface = c(onSurface),
            surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
            surfaceContainerLow = c(surface), surfaceContainer = c(surface), surfaceContainerHigh = c(surfaceVariant),
        )
    } else {
        lightColorScheme(
            primary = c(primary), onPrimary = c(onPrimary),
            primaryContainer = c(container), onPrimaryContainer = c(onContainer),
            secondaryContainer = c(container), onSecondaryContainer = c(onContainer),
            background = c(background), onBackground = c(onSurface),
            surface = c(surface), onSurface = c(onSurface),
            surfaceVariant = c(surfaceVariant), onSurfaceVariant = c(onSurfaceVariant),
            surfaceContainerLow = c(surface), surfaceContainer = c(surface), surfaceContainerHigh = c(surfaceVariant),
        )
    }
}
