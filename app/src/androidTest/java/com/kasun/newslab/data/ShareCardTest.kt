package com.kasun.newslab.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the share card at its real size and writes each result to the device's
 * external files directory, so the drawing can be pulled off and looked at — the
 * card is a fixed-geometry design, and whether it matches is a question about
 * pixels, not about assertions.
 *
 *     ./gradlew :app:connectedDebugAndroidTest
 *     adb shell run-as com.kasun.newslab ls files/   (or pull from external files)
 */
@RunWith(AndroidJUnit4::class)
class ShareCardTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val headline =
        "Sri Lanka sentences former police chief and ex-defence secretary to death " +
            "over Easter bombings negligence"

    /** A stand-in news photo — a 3:2 gradient, so the crop and the scrim are both visible. */
    private fun samplePhoto(width: Int = 1500, height: Int = 1000): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (y in 0 until height step 4) {
            val t = y.toFloat() / height
            paint.color = Color.rgb(
                (110 + 70 * t).toInt(),
                (96 + 60 * t).toInt(),
                (78 + 40 * t).toInt(),
            )
            canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 4).toFloat(), paint)
        }
        // A couple of light shapes, so a wrong crop or a missing scrim is obvious.
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(width * 0.3f, height * 0.42f, height * 0.26f, paint)
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.06f, paint)
        return bitmap
    }

    /** The row the amber rule sits on — the foot of the photo band, wherever it settled. */
    private fun bandFoot(card: Bitmap): Int =
        (0 until ShareCard.SIZE).firstOrNull { card.getPixel(540, it) == 0xFFE9A33E.toInt() } ?: -1

    private fun dump(name: String, bitmap: Bitmap) {
        val dir = File(context.getExternalFilesDir(null), "share-card-test").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun rendersTheDesignAtFullSize() {
        val card = ShareCard.render(
            context,
            ShareCard.Content(
                headline = headline,
                source = "Reuters",
                category = "Politics",
                date = "01.08.2026",
                photo = samplePhoto(),
            ),
        )
        dump("card-full", card)
        assertEquals(ShareCard.SIZE, card.width)
        assertEquals(ShareCard.SIZE, card.height)
        // The amber rule closing the band, full-bleed, somewhere in the band's range.
        val foot = bandFoot(card)
        assertTrue("band foot at $foot", foot in 560..880)
        assertEquals(0xFFE9A33E.toInt(), card.getPixel(4, foot))
    }

    /**
     * No photo worth showing means the text-only card: a masthead closed by a rule
     * inset to the type's margins, rather than the band card with an empty band.
     */
    @Test
    fun rendersTheTextOnlyCardWithoutAPhoto() {
        val card = ShareCard.render(
            context,
            ShareCard.Content(
                headline = headline,
                source = "EconomyNext",
                category = "Economy",
                date = "01.08.2026",
                photo = null,
            ),
        )
        dump("card-text-only", card)
        assertEquals(ShareCard.SIZE, card.width)
        // The rule sits under the header, inset — not the band's full-bleed one.
        assertEquals(0xFFE9A33E.toInt(), card.getPixel(540, 142))
        assertEquals(0xFF17140F.toInt(), card.getPixel(20, 142))
        // And nothing occupies the band the photo would have filled.
        assertEquals(0xFF17140F.toInt(), card.getPixel(1060, 400))
    }

    /**
     * A one-line headline needs less of the card, and the photograph takes what it
     * doesn't rather than leaving a hole above the chip.
     */
    @Test
    fun growsTheBandWhenTheHeadlineIsShort() {
        fun cardFor(headline: String, category: String) = ShareCard.render(
            context,
            ShareCard.Content(headline, "Daily FT", category, "01.08.2026", samplePhoto()),
        )

        val short = cardFor("Rupee firms against the dollar", "")
        val long = cardFor(headline, "Politics")
        dump("card-short", short)

        assertTrue(
            "short headline band ${bandFoot(short)} should sit below long headline band ${bandFoot(long)}",
            bandFoot(short) > bandFoot(long),
        )
        // ...but not past the ceiling, or the type would be squeezed off the card.
        assertTrue("band foot at ${bandFoot(short)}", bandFoot(short) <= 880)
    }

    /** Far more headline than fits: it must clamp to four lines, not spill past the card. */
    @Test
    fun clampsAnOverlongHeadline() {
        val card = ShareCard.render(
            context,
            ShareCard.Content(
                headline = List(6) { headline }.joinToString(" "),
                source = "Reuters",
                category = "Politics",
                date = "01.08.2026",
                photo = samplePhoto(),
            ),
        )
        dump("card-overlong", card)
        // The bottom-left corner belongs to the body's padding, so nothing may be
        // drawn there however long the headline runs.
        assertEquals(0xFF17140F.toInt(), card.getPixel(20, ShareCard.SIZE - 12))
    }

    /** The card is written where the share provider can hand it out. */
    @Test
    fun writesIntoTheProvidersDirectory() {
        val bitmap = ShareCard.render(
            context,
            ShareCard.Content("Headline", "Reuters", "Politics", "01.08.2026", null),
        )
        val file = ShareCard.writeToCache(context, bitmap, "article-id")
        assertTrue(file.exists())
        assertTrue(file.length() > 0)
        assertEquals(File(context.cacheDir, ShareCard.SHARE_DIR), file.parentFile)
    }
}
