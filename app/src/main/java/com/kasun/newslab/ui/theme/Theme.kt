package com.kasun.newslab.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kasun.newslab.data.AppFont
import com.kasun.newslab.data.DesignStyle
import com.kasun.newslab.data.FontSize
import com.kasun.newslab.data.ThemeMode

/**
 * Semantic tokens for the app's two design languages, selected in Settings.
 *
 * WARM_LEDGER is ruled paper: warm grounds, hairlines, right angles, and amber
 * reserved for the reader's own intent — a rule that fired, a save, a selection.
 * CLASSIC is the original look: white and cool greys, red keyword accents, a blue
 * selection accent, rounded corners.
 */
data class NdColors(
    val style: DesignStyle,
    /** True when these are the dark-mode cuts; source hues fork on this too. */
    val dark: Boolean,
    /** Ground. */
    val paper: Color,
    /** Raised — alert cards, dialogs, sheets. Value difference only, no shadow. */
    val card: Color,
    /** Recessed — inactive chips, thumbnail placeholders, search fields. */
    val chipBg: Color,
    /** Structural 1dp — card outlines, header/nav separators, chip outlines. */
    val border: Color,
    /** Between-row hairline only — lighter than [border] so a long list doesn't grid up. */
    val rule: Color,
    val ink: Color,
    val muted: Color,
    val faint: Color,
    /** The intent accent as a fill: switch tracks, selected picker chips. */
    val brand: Color,
    /** 2–3dp intent graphics: tab underline, nav rule, section tick, page indicator. */
    val brandStroke: Color,
    /** Small intent text and filled glyphs: counts, the saved bookmark, See all. */
    val brandText: Color,
    /** Row/section tint for "this matched a rule of yours" — felt, not seen. */
    val brandWash: Color,
    /** Text and glyphs sitting on a [brand] fill. */
    val onBrand: Color,
    /** Keyword tag fill — amber in Warm Ledger, the red wash in Classic. */
    val tagBg: Color,
    /** Keyword tag text on [tagBg]. */
    val tagText: Color,
    /** Strong keyword-section accent: the alerts header and its tick. */
    val tagAccent: Color,
    /** Refresh succeeded, feed validated, upward deltas. */
    val positive: Color,
    /** Destruction and failure only — delete, fetch failed, offline. */
    val negative: Color,
    /** Background behind every find-in-page match in the article reader. */
    val findHighlight: Color,
    /** Background behind the currently-focused find-in-page match. */
    val findHighlightActive: Color,
    /** Cards, dialogs, sheets, the photo-forward card's chrome. */
    val radiusCard: Dp,
    /** Filter/select/source chips — square in Warm Ledger, a pill in Classic. */
    val radiusChip: Dp,
    /** The keyword tag. */
    val radiusTag: Dp,
    /** Thumbnails, hero images, text fields, monogram tiles. */
    val radiusThumb: Dp,
)

val LightNd = NdColors(
    style = DesignStyle.WARM_LEDGER,
    dark = false,
    paper = Color(0xFFFAF7EF),
    card = Color(0xFFFFFDF8),
    chipBg = Color(0xFFF0EBE0),
    border = Color(0xFFE2DBCC),
    rule = Color(0xFFEDE7DA),
    ink = Color(0xFF191510),
    muted = Color(0xFF5C5548),
    faint = Color(0xFF7A7263),
    brand = Color(0xFFE9A33E),
    brandStroke = Color(0xFFC9821F),
    brandText = Color(0xFF8A5A11),
    brandWash = Color(0xFFFAEFD9),
    onBrand = Color(0xFF191510),
    tagBg = Color(0xFFE9A33E),
    tagText = Color(0xFF191510),
    tagAccent = Color(0xFF8A5A11),
    positive = Color(0xFF1B6E33),
    negative = Color(0xFFA3241C),
    findHighlight = Color(0xFFF7E7A8),
    findHighlightActive = Color(0xFFF2D566),
    radiusCard = 2.dp,
    radiusChip = 0.dp,
    radiusTag = 0.dp,
    radiusThumb = 0.dp,
)

val DarkNd = LightNd.copy(
    dark = true,
    paper = Color(0xFF14110C),
    card = Color(0xFF1D1913),
    chipBg = Color(0xFF262118),
    border = Color(0xFF322C22),
    rule = Color(0xFF251F17),
    ink = Color(0xFFF2EDE1),
    muted = Color(0xFFADA491),
    faint = Color(0xFF857C6C),
    brand = Color(0xFFE9A33E),
    brandStroke = Color(0xFFE9A33E),
    brandText = Color(0xFFE9A33E),
    brandWash = Color(0xFF2E2413),
    tagAccent = Color(0xFFE9A33E),
    positive = Color(0xFF7FC98D),
    negative = Color(0xFFF0A5A0),
    findHighlight = Color(0xFF4A3A12),
    findHighlightActive = Color(0xFF7A5F17),
)

val ClassicLightNd = NdColors(
    style = DesignStyle.CLASSIC,
    dark = false,
    paper = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    chipBg = Color(0xFFF2F3F5),
    border = Color(0xFFE8EAED),
    rule = Color(0xFFF0F1F3),
    ink = Color(0xFF14171A),
    muted = Color(0xFF66707B),
    faint = Color(0xFF98A1AB),
    brand = Color(0xFF2563EB),
    brandStroke = Color(0xFF2563EB),
    brandText = Color(0xFF2563EB),
    brandWash = Color(0xFFF3F6FC),
    onBrand = Color(0xFFFFFFFF),
    tagBg = Color(0xFFFBEAE8),
    tagText = Color(0xFFB3261E),
    tagAccent = Color(0xFF8F2020),
    positive = Color(0xFF1E8E3E),
    negative = Color(0xFFB3261E),
    findHighlight = Color(0xFFFFF1A8),
    findHighlightActive = Color(0xFFFFC24D),
    radiusCard = 12.dp,
    radiusChip = 50.dp,
    radiusTag = 5.dp,
    radiusThumb = 8.dp,
)

val ClassicDarkNd = ClassicLightNd.copy(
    dark = true,
    paper = Color(0xFF101214),
    card = Color(0xFF15181B),
    chipBg = Color(0xFF1F2328),
    border = Color(0xFF272C31),
    rule = Color(0xFF1C2024),
    ink = Color(0xFFE8EAED),
    muted = Color(0xFF9AA3AD),
    faint = Color(0xFF6B7480),
    brand = Color(0xFF8AB4F8),
    brandStroke = Color(0xFF8AB4F8),
    brandText = Color(0xFF8AB4F8),
    brandWash = Color(0xFF181D26),
    onBrand = Color(0xFF14171A),
    tagBg = Color(0xFF3A201D),
    tagText = Color(0xFFF2B8B5),
    tagAccent = Color(0xFFE79A94),
    positive = Color(0xFF81C995),
    negative = Color(0xFFF2B8B5),
    findHighlight = Color(0xFF4A3F16),
    findHighlightActive = Color(0xFF8A6A1E),
)

val LocalNd = staticCompositionLocalOf { LightNd }

/** Shorthand accessor used across the UI: `nd.ink`, `nd.border`, ... */
val nd: NdColors
    @Composable get() = LocalNd.current

// Warm Ledger source hues live on the cool half of the wheel so a source can never
// be mistaken for an amber signal. The light cut is what's stored per feed; the
// dark cut is resolved at render time.
private val SOURCE_HUE_DARK = mapOf(
    0xFF2F5AA8L to Color(0xFF93B4E8), // slate
    0xFF14666BL to Color(0xFF79C4C8), // teal
    0xFF3B3E9EL to Color(0xFFA2A5EA), // indigo
    0xFF6E3B86L to Color(0xFFC39ADA), // plum
    0xFF9B2B57L to Color(0xFFE795B0), // rose
    0xFF3F6B4AL to Color(0xFF96C0A0), // sage
    0xFF1F4E7AL to Color(0xFF8AB2D4), // steel
)

// Feeds saved under the old vivid palette keep their stored value; in Warm Ledger
// each folds onto the nearest new hue so existing installs land in the new scheme
// without a migration. Old amber deliberately leaves the warm half of the wheel.
private val LEGACY_HUE = mapOf(
    0xFF2563EBL to 0xFF2F5AA8L, // blue -> slate
    0xFF16A34AL to 0xFF3F6B4AL, // green -> sage
    0xFF7C3AEDL to 0xFF3B3E9EL, // purple -> indigo
    0xFFD97706L to 0xFF6E3B86L, // amber -> plum
    0xFFDB2777L to 0xFF9B2B57L, // pink -> rose
    0xFF0891B2L to 0xFF14666BL, // teal -> teal
)

/**
 * Resolves a feed's stored accent for the current design and mode. Classic shows
 * the stored hue as-is in both modes, exactly as the original design did.
 */
@Composable
fun sourceHue(stored: Long): Color {
    if (nd.style == DesignStyle.CLASSIC) return Color(stored)
    val canonical = LEGACY_HUE[stored] ?: stored
    return if (nd.dark) {
        SOURCE_HUE_DARK[canonical] ?: Color(canonical)
    } else {
        Color(canonical)
    }
}

/** Maps a user-chosen font preference to its bundled family, or the device's own default. */
fun AppFont.toFontFamily(): FontFamily = when (this) {
    AppFont.SYSTEM -> FontFamily.Default
    AppFont.LITERATA -> NdFonts.Literata
    AppFont.OPEN_SANS -> NdFonts.OpenSans
}

/** Multiplier applied on top of the device's own font-scale accessibility setting. */
fun FontSize.toScale(): Float = when (this) {
    FontSize.SMALL -> 0.9f
    FontSize.DEFAULT -> 1f
    FontSize.LARGE -> 1.15f
    FontSize.EXTRA_LARGE -> 1.3f
}

@Composable
fun NewsLabTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    designStyle: DesignStyle = DesignStyle.WARM_LEDGER,
    uiFont: AppFont = AppFont.SYSTEM,
    fontSize: FontSize = FontSize.DEFAULT,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = when (designStyle) {
        DesignStyle.WARM_LEDGER -> if (dark) DarkNd else LightNd
        DesignStyle.CLASSIC -> if (dark) ClassicDarkNd else ClassicLightNd
    }
    // The surfaceContainer* slots feed ModalBottomSheet, AlertDialog, DropdownMenu
    // and the pull-to-refresh indicator; left at Material's baseline they render
    // purple-tinted chrome that clashes with the flat nd palette. The inverse*
    // slots feed Snackbars, with the action in the intent accent.
    val scheme = if (dark) {
        darkColorScheme(
            background = colors.paper,
            surface = colors.paper,
            // Feeds TextButton content, focused text-field outlines and progress
            // indicators; holds 4.5:1 on paper in both designs and modes.
            primary = colors.brandText,
            error = colors.negative,
            outline = colors.border,
            onBackground = colors.ink,
            onSurface = colors.ink,
            onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.card,
            surfaceContainerLow = colors.card,
            surfaceContainer = colors.card,
            surfaceContainerHigh = colors.card,
            surfaceContainerHighest = colors.card,
            inverseSurface = if (designStyle == DesignStyle.WARM_LEDGER) Color(0xFF2B251C) else Color(0xFF23272B),
            inverseOnSurface = colors.ink,
            inversePrimary = colors.brand,
        )
    } else {
        lightColorScheme(
            background = colors.paper,
            surface = colors.paper,
            primary = colors.brandText,
            error = colors.negative,
            outline = colors.border,
            onBackground = colors.ink,
            onSurface = colors.ink,
            onSurfaceVariant = colors.muted,
            surfaceContainerLowest = colors.card,
            surfaceContainerLow = colors.card,
            surfaceContainer = colors.card,
            surfaceContainerHigh = colors.card,
            surfaceContainerHighest = colors.card,
            inverseSurface = colors.ink,
            inverseOnSurface = if (designStyle == DesignStyle.WARM_LEDGER) Color(0xFFF5F1E8) else Color(0xFFF1F3F4),
            inversePrimary = colors.brand,
        )
    }
    // Warm Ledger is ruled paper — hairlines and right angles, 2dp at most on any
    // Material component. Classic keeps the original rounded chrome.
    val shapes = if (designStyle == DesignStyle.WARM_LEDGER) {
        Shapes(
            extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(2.dp),
            medium = RoundedCornerShape(2.dp),
            large = RoundedCornerShape(2.dp),
            extraLarge = RoundedCornerShape(2.dp),
        )
    } else {
        Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp),
        )
    }
    // Every Text() in this app inherits the ambient LocalTextStyle (== typography.bodyLarge)
    // rather than naming its own family, so this one line carries the interface font
    // everywhere. It's the *interface* font: ArticleDetailScreen names the separate
    // reading font on the article's own text, which overrides this for those Texts only.
    val typography = Typography(bodyLarge = Typography().bodyLarge.copy(fontFamily = uiFont.toFontFamily()))
    // Most Text() calls hardcode their own fontSize in sp, so — unlike the font family
    // above — scaling Typography wouldn't reach them. Scaling the ambient Density's
    // fontScale instead affects every sp value app-wide, layered on top of whatever
    // font-size accessibility setting the device itself already applies.
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, fontSize) {
        Density(baseDensity.density, baseDensity.fontScale * fontSize.toScale())
    }
    CompositionLocalProvider(LocalNd provides colors, LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
