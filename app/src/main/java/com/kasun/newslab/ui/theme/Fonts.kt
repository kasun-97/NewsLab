package com.kasun.newslab.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.kasun.newslab.R

/** Every weight the UI asks for; Compose snaps anything else to the nearest of these. */
private val WEIGHTS = listOf(
    FontWeight.Normal,
    FontWeight.Medium,
    FontWeight.SemiBold,
    FontWeight.Bold,
    FontWeight.ExtraBold,
)

/**
 * Builds a family from a single variable font file by instancing its `wght` axis once
 * per weight above — one file covers Regular through ExtraBold instead of five static
 * cuts. A face whose axis stops short simply clamps there, which is what the OpenType
 * spec requires of out-of-range values.
 *
 * Other axes are left at the face's own default: Literata's `opsz` (12) and Open Sans'
 * `wdth` (100) are already the right values for body text.
 *
 * Variable axes need API 26, which is this app's minSdk.
 */
@OptIn(ExperimentalTextApi::class)
private fun variableFamily(resId: Int): FontFamily = FontFamily(
    WEIGHTS.map { weight ->
        Font(
            resId = resId,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    }
)

/**
 * The selectable typefaces, both bundled — no downloadable-font provider, so they render
 * identically offline and on every device.
 */
object NdFonts {
    val Literata = variableFamily(R.font.literata)
    val OpenSans = variableFamily(R.font.open_sans)
}
