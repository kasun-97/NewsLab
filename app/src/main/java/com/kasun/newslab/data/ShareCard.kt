package com.kasun.newslab.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import coil.imageLoader
import com.kasun.newslab.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws the square social card an article is shared as — the "3a · 3:2 band" layout
 * from the NewsLab Social Card design: a 1080×700 photo under a top scrim carrying
 * the wordmark and date, an amber rule, then the category chip, headline and source
 * on ink.
 *
 * Everything is drawn straight onto a Canvas rather than composed and screenshotted:
 * the card has a fixed 1080×1080 geometry that has nothing to do with the device's
 * screen, and this way it renders identically on every phone and off the UI thread.
 */
object ShareCard {

    // ---- The design's own numbers, at its 1080×1080 scale ----

    const val SIZE = 1080

    // The design's band is a fixed 700. Here it is a range the photograph fills out to
    // whatever the type leaves: the floor is what a headline running its full four
    // lines needs, the ceiling what is left when one line has had its due. A fixed
    // band suits the headline it was drawn with and opens a hole above a short one.
    private const val BAND_MIN = 560f
    private const val BAND_MAX = 880f
    private const val SCRIM_HEIGHT = 230
    private const val RULE_HEIGHT = 5
    private const val SIDE_PADDING = 52f
    private const val HEADER_TOP = 44f
    private const val LOGO_SIZE = 52f
    private const val LOGO_RADIUS = 14f
    private const val LOGO_GAP = 14f
    // The design's 24, kept in step with the type: the headline is half again the size
    // it was drawn at, and a gap that doesn't grow with it reads as the words crowding
    // the picture. The band gives up the difference, being whatever is left over.
    private const val BODY_TOP_PADDING = 36f
    private const val BODY_BOTTOM_PADDING = 38f
    private const val CHIP_PADDING_H = 18f
    private const val CHIP_PADDING_V = 10f
    private const val CHIP_TO_HEADLINE = 18f

    // The design's own 46, tightened. That gap is slack — it exists to hold the source
    // line against the bottom edge — and at this type size it is what a third headline
    // line is bought with. Larger text needs relatively less separation anyway.
    private const val HEADLINE_TO_SOURCE = 30f
    private const val SOURCE_SQUARE = 9f
    private const val SOURCE_GAP = 14f

    // Type runs half again as large as the design's own sizes, which are set for a
    // desktop canvas where this is read in a feed at a fraction of the width. Archivo
    // Narrow is what makes that affordable: condensed enough to hold the same words in
    // fewer, larger lines. The cost is still paid in lines — see headlineLayout, which
    // fits as many as the block leaves room for.
    private const val WORDMARK_SIZE = 34f
    private const val DATE_SIZE = 28f
    private const val CHIP_SIZE = 26f
    private const val HEADLINE_SIZE = 64f
    private const val HEADLINE_LINE_HEIGHT = 1.05f
    private const val HEADLINE_MAX_LINES = 4
    private const val SOURCE_SIZE = 31f

    // The text-only card: no band, so the masthead's rule sits under the header with
    // the same 44 above it as below, and the headline takes the frame the photo would
    // have had. Its cap is a headline's worth of lines, not the space available —
    // which at this size would run to ten.
    private const val MASTHEAD_RULE_TOP = HEADER_TOP + LOGO_SIZE + 44f
    private const val TEXT_ONLY_HEADLINE_SIZE = 84f
    private const val TEXT_ONLY_MAX_LINES = 6

    // The bar an image has to clear to be worth putting on the card, well below the
    // band's own width because a publisher's best is often well below it: Daily FT
    // serves 540×500 in its feed and the very same file as the article's og:image, so
    // a bar of 640 took the picture off every one of its cards. At this size the band
    // doubles an image and it reads soft, which is the price of having one at all —
    // what the bar is really for is the 200px thumbnail, which reads as a mistake.
    // Measured from the image's own header, so one too small is never decoded.
    private const val MIN_PHOTO_WIDTH = 480
    private const val MIN_PHOTO_HEIGHT = 270

    private const val INK = 0xFF17140F.toInt()
    private const val AMBER = 0xFFE9A33E.toInt()
    private const val PAPER = 0xFFFFFCF5.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    /** The adaptive icon's 108-unit canvas shows only its central 72 — the same crop a launcher applies. */
    private const val ADAPTIVE_SCALE = 108f / 72f

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy", Locale.US)

    /** Everything the card puts on the page, resolved before any drawing happens. */
    data class Content(
        val headline: String,
        val source: String,
        val category: String,
        val date: String,
        val photo: Bitmap?,
    )

    /**
     * Gathers an article's card content: its best image (the page-scraped one where
     * there is one), the category to put in the chip, and the published date.
     *
     * The photo comes through Coil, so an article already seen in a list or the reader
     * is served from its cache rather than fetched again.
     */
    suspend fun contentFor(context: Context, article: Article): Content = withContext(Dispatchers.IO) {
        val categories = AppDb.get(context).dao().categoriesOnce()
        Content(
            headline = article.title,
            source = article.feedName,
            category = categoryLabel(article, categories),
            date = dateFormat.format(Date(article.publishedAt)),
            photo = bestPhoto(context, article),
        )
    }

    /**
     * The photo the card will carry, or null for the text-only layout.
     *
     * The page's own image is tried first, fetched here and then kept if nothing had
     * fetched it yet, and the feed's is tried after it. Both are judged the same way,
     * by what the image actually measures — under [MIN_PHOTO_WIDTH] it is turned down,
     * whichever it came from, because stretching a thumbnail across a band 1080 wide
     * looks worse than the card that has no band at all.
     *
     * Judging by measurement rather than by source is the point. Ruling the feed's
     * image out as a thumbnail on principle cost every WSJ share its photo: WSJ answers
     * this fetch with a 401 so there is never a page image, while its feed carries the
     * full-size original at 1280 wide.
     *
     * This ignores the high-quality images setting on purpose: that setting governs
     * what browsing a list costs in data, while sharing is one deliberate act on one
     * article.
     */
    private suspend fun bestPhoto(context: Context, article: Article): Bitmap? {
        if (article.id in withoutPhoto) {
            return null
        }
        // One budget over the lookup and the download together — what the person who
        // tapped Share waits on is the total, not whichever half was slow — spent by
        // handing each call only the time the one before it left. Enforced by the
        // client, since a coroutine deadline cannot cut a blocking call short.
        // Whatever the app already holds comes first. Coil downloaded it to show the
        // article, so it costs nothing, needs no network, and — the point — cannot be
        // refused by a host that turns this fetch away, having already let Coil past.
        fromDiskCache(context, article.pageImageUrl)?.let { return it }
        fromDiskCache(context, article.imageUrl)?.let { return it }

        val deadline = android.os.SystemClock.elapsedRealtime() + PHOTO_BUDGET_MS
        val pageUrl = article.pageImageUrl.ifBlank { fetchPageImage(context, article, remaining(deadline)) }
        val photo = loadPhoto(pageUrl, remaining(deadline))
            ?: loadPhoto(article.imageUrl.takeUnless { it == pageUrl }.orEmpty(), remaining(deadline))
        // Nothing usable now means nothing usable for the rest of the session —
        // whether the page had no image, it was too small, or the wait ran out. A
        // second share of the story goes straight to the text-only card rather than
        // spending that time again.
        if (photo == null) withoutPhoto += article.id
        return photo
    }

    /**
     * What's left of the budget, or null once there isn't enough to be worth a call —
     * which also keeps a zero away from OkHttp, where it would mean no deadline at all.
     */
    private fun remaining(deadline: Long): Long? =
        (deadline - android.os.SystemClock.elapsedRealtime()).takeIf { it >= MIN_CALL_MS }

    /** Reads the page's og:image and stores it, so the lists and the reader get it too. */
    private suspend fun fetchPageImage(context: Context, article: Article, deadlineMs: Long?): String {
        if (deadlineMs == null) return ""
        val url = Repository(context).fetchArticleImage(article.link, deadlineMs) ?: return ""
        AppDb.get(context).dao().setPageImageUrl(article.id, url)
        return url
    }

    /**
     * The chip's label: the app's own category, the one the article is filed under in
     * the inbox. Blank when it matched none — the publisher's raw tags were the other
     * candidate and they are mostly "Others" or the section name repeated, so the card
     * goes without a chip instead and gives the headline the room.
     */
    private fun categoryLabel(article: Article, categories: List<Category>): String =
        categories.firstOrNull { article.categoryIds.contains(",${it.id},") }?.name.orEmpty()

    /**
     * The image as Coil already stored it, or null if it never fetched this one. The
     * cache holds the response body as it arrived, so this is the full-size original
     * the article was displayed from, not the thumbnail it was displayed at, and it
     * meets the same size check as anything downloaded here.
     */
    private fun fromDiskCache(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return try {
            val cache = context.imageLoader.diskCache ?: return null
            cache.openSnapshot(url)?.use { decodeForBand(it.data.toFile().readBytes()) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Downloads and decodes the photo. Fetched here rather than through Coil: Coil's
     * fetcher blocks its thread for the download, so no deadline set out here could
     * cut one short, and this needs the image's dimensions before its pixels anyway.
     */
    private suspend fun loadPhoto(url: String, deadlineMs: Long?): Bitmap? {
        if (url.isBlank() || deadlineMs == null) return null
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", Http.USER_AGENT)
                    .build()
                Http.bounded(deadlineMs).newCall(request).execute().use { response ->
                    val body = response.body
                    if (!response.isSuccessful || body == null) return@use null
                    if (body.contentLength() > MAX_PHOTO_BYTES) return@use null
                    decodeForBand(body.byteStream().readBytes())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A card without its photo still says what the story is; it falls back
                // to the text-only layout rather than the share failing outright.
                null
            }
        }
    }

    /**
     * Decodes [bytes] no larger than the band needs, and only if the image can fill it:
     * the size is read from the header first, so one too small is turned away without
     * being decoded at all and a 4000px press photo is never held at full size.
     */
    private fun decodeForBand(bytes: ByteArray): Bitmap? {
        val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, header)
        if (header.outWidth < MIN_PHOTO_WIDTH || header.outHeight < MIN_PHOTO_HEIGHT) return null
        // Halved against the tallest the band can grow to, so a photo is never
        // downsampled below what a card that gives it the room would ask for.
        var sample = 1
        while (header.outWidth / (sample * 2) >= SIZE && header.outHeight / (sample * 2) >= BAND_MAX) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /**
     * Renders [content] to a 1080×1080 bitmap. Pure drawing — safe to call off the
     * main thread.
     *
     * Two layouts: the design's band card when there's a photo worth showing, and a
     * text-only one when there isn't — masthead, a rule under it, and the headline
     * given the whole frame at the size that space allows.
     */
    fun render(context: Context, content: Content): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(INK)

        val bold = ResourcesCompat.getFont(context, R.font.archivo_narrow_bold) ?: Typeface.DEFAULT_BOLD
        val semiBold = ResourcesCompat.getFont(context, R.font.archivo_narrow_semibold) ?: Typeface.DEFAULT_BOLD

        if (content.photo != null) {
            // The type is laid out first and the photograph takes what it doesn't
            // need. The design's band is a fixed height, which is right for the
            // headline it was drawn with and leaves a hole above a one-line one —
            // this way that space goes to the picture instead of standing empty.
            //
            // Measured against the deepest the body could ever be, so the line count
            // doesn't depend on a band height that depends on the line count.
            val body = layOutBody(
                content, bold, semiBold,
                headlineSize = HEADLINE_SIZE,
                maxLines = HEADLINE_MAX_LINES,
                available = SIZE - BODY_BOTTOM_PADDING - BAND_MIN - BODY_TOP_PADDING,
            )
            val band = (SIZE - BODY_BOTTOM_PADDING - body.height - BODY_TOP_PADDING)
                .coerceIn(BAND_MIN, BAND_MAX)
            drawBand(context, canvas, content, bold, semiBold, band)
            drawBody(canvas, content, body, contentTop = band + BODY_TOP_PADDING, centreBlock = false)
        } else {
            drawMasthead(context, canvas, content, bold, semiBold)
            val contentTop = MASTHEAD_RULE_TOP + RULE_HEIGHT + BODY_TOP_PADDING
            val body = layOutBody(
                content, bold, semiBold,
                headlineSize = TEXT_ONLY_HEADLINE_SIZE,
                maxLines = TEXT_ONLY_MAX_LINES,
                available = SIZE - BODY_BOTTOM_PADDING - contentTop,
            )
            drawBody(canvas, content, body, contentTop, centreBlock = true)
        }
        return bitmap
    }

    /** The header on bare ink, closed by a rule inset to the type's own margins. */
    private fun drawMasthead(
        context: Context,
        canvas: Canvas,
        content: Content,
        bold: Typeface,
        semiBold: Typeface,
    ) {
        drawHeader(context, canvas, content, bold, semiBold, onPhoto = false)
        canvas.drawRect(
            SIDE_PADDING,
            MASTHEAD_RULE_TOP,
            SIZE - SIDE_PADDING,
            MASTHEAD_RULE_TOP + RULE_HEIGHT,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER },
        )
    }

    // ---- Photo band ----

    private fun drawBand(
        context: Context,
        canvas: Canvas,
        content: Content,
        bold: Typeface,
        semiBold: Typeface,
        bandHeight: Float,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        content.photo?.let { drawCropped(canvas, it, RectF(0f, 0f, SIZE.toFloat(), bandHeight)) }

        // Top scrim, so the wordmark reads over a bright photo.
        paint.shader = LinearGradient(
            0f, 0f, 0f, SCRIM_HEIGHT.toFloat(),
            intArrayOf(colorOf(INK, 0.9f), colorOf(INK, 0.62f), colorOf(INK, 0f)),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SCRIM_HEIGHT.toFloat(), paint)
        paint.shader = null

        // The amber rule closing the band.
        paint.color = AMBER
        canvas.drawRect(0f, bandHeight - RULE_HEIGHT, SIZE.toFloat(), bandHeight, paint)

        drawHeader(context, canvas, content, bold, semiBold, onPhoto = true)
    }

    /**
     * Logo, wordmark and date. [onPhoto] adds the design's drop shadow, which earns
     * its keep over a photograph and would only thicken the type on bare ink.
     */
    private fun drawHeader(
        context: Context,
        canvas: Canvas,
        content: Content,
        bold: Typeface,
        semiBold: Typeface,
        onPhoto: Boolean,
    ) {
        drawLogo(context, canvas, SIDE_PADDING, HEADER_TOP)

        // Wordmark and date, both centred on the logo tile.
        val centreY = HEADER_TOP + LOGO_SIZE / 2f
        val wordmark = textPaint(bold, size = WORDMARK_SIZE, color = WHITE, letterSpacing = 0.16f)
        val date = textPaint(semiBold, size = DATE_SIZE, color = WHITE, letterSpacing = 0.06f)
        if (onPhoto) {
            wordmark.withShadow()
            date.withShadow()
        }
        canvas.drawText(
            "NEWS LAB",
            SIDE_PADDING + LOGO_SIZE + LOGO_GAP,
            baselineFor(wordmark, centreY),
            wordmark,
        )
        date.textAlign = Paint.Align.RIGHT
        canvas.drawText(content.date, SIZE - SIDE_PADDING, baselineFor(date, centreY), date)
    }

    /**
     * The launcher icon, drawn as the design's rounded tile: the amber background
     * colour, then the mark's vector over it at the adaptive-icon crop.
     */
    private fun drawLogo(context: Context, canvas: Canvas, left: Float, top: Float) {
        val tile = RectF(left, top, left + LOGO_SIZE, top + LOGO_SIZE)
        val saved = canvas.save()
        canvas.clipPath(android.graphics.Path().apply {
            addRoundRect(tile, LOGO_RADIUS, LOGO_RADIUS, android.graphics.Path.Direction.CW)
        })
        canvas.drawColor(ResourcesCompat.getColor(context.resources, R.color.ic_launcher_background, null))
        ResourcesCompat.getDrawable(context.resources, R.drawable.ic_launcher_foreground, null)?.let { mark ->
            val inset = LOGO_SIZE * (ADAPTIVE_SCALE - 1f) / 2f
            mark.setBounds(
                (tile.left - inset).toInt(),
                (tile.top - inset).toInt(),
                (tile.right + inset).toInt(),
                (tile.bottom + inset).toInt(),
            )
            mark.draw(canvas)
        }
        canvas.restoreToCount(saved)

        // The design's hairline ring, drawn inside the tile so it isn't clipped away.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = colorOf(0xFFF5F1E8.toInt(), 0.3f)
        }
        canvas.drawRoundRect(RectF(tile).apply { inset(1f, 1f) }, LOGO_RADIUS, LOGO_RADIUS, ring)
    }

    // ---- Type block ----

    /** The type block, measured before anything is drawn, so the band can take the rest. */
    private class Body(
        val chipText: String,
        val chipPaint: TextPaint,
        val chipHeight: Float,
        val chipGap: Float,
        val headline: StaticLayout,
        val sourcePaint: TextPaint,
    ) {
        /** Everything from the top of the chip to the foot of the source line. */
        val height: Float
            get() = chipHeight + chipGap + headline.height + HEADLINE_TO_SOURCE + sourcePaint.fontSpacing
    }

    /**
     * Measures the chip, headline and source line for a body [available] pixels deep.
     * Nothing is drawn: the band card settles its own height from what this returns.
     */
    private fun layOutBody(
        content: Content,
        bold: Typeface,
        semiBold: Typeface,
        headlineSize: Float,
        maxLines: Int,
        available: Float,
    ): Body {
        // An article filed under nothing gets no chip at all, rather than a filler
        // one: the publisher's own tags are mostly "Others" and say less than the
        // space they take.
        val chipText = content.category.uppercase(Locale.getDefault())
        val chipPaint = textPaint(bold, size = CHIP_SIZE, color = INK, letterSpacing = 0.18f)
        val chipHeight = if (chipText.isBlank()) 0f else chipPaint.fontSpacing + CHIP_PADDING_V * 2
        val chipGap = if (chipText.isBlank()) 0f else CHIP_TO_HEADLINE
        val sourcePaint = textPaint(semiBold, size = SOURCE_SIZE, color = PAPER, letterSpacing = 0f)
        val headlinePaint = textPaint(bold, size = headlineSize, color = PAPER, letterSpacing = -0.03f)
        return Body(
            chipText = chipText,
            chipPaint = chipPaint,
            chipHeight = chipHeight,
            chipGap = chipGap,
            headline = headlineLayout(
                content.headline, headlinePaint, (SIZE - SIDE_PADDING * 2).toInt(),
                available = available - chipHeight - chipGap - HEADLINE_TO_SOURCE - sourcePaint.fontSpacing,
                size = headlineSize,
                maxLines = maxLines,
            ),
            sourcePaint = sourcePaint,
        )
    }

    /**
     * Draws [body] between [contentTop] and the card's bottom padding. The source line
     * is always pinned to the bottom; what varies is the block above it — held down
     * against that line on the band card, as the design's `margin-top:auto` does, and
     * centred on the text-only one, where bottom-aligning would strand the upper half.
     *
     * On the band card there is nothing to hold down: the band has already taken the
     * slack, so the block starts exactly at [contentTop].
     */
    private fun drawBody(canvas: Canvas, content: Content, body: Body, contentTop: Float, centreBlock: Boolean) {
        val left = SIDE_PADDING
        val sourceTop = SIZE - BODY_BOTTOM_PADDING - body.sourcePaint.fontSpacing
        val slack = (sourceTop - HEADLINE_TO_SOURCE - contentTop - body.chipHeight - body.chipGap -
            body.headline.height).coerceAtLeast(0f)
        val blockTop = contentTop + if (centreBlock) slack / 2f else slack

        if (body.chipText.isNotBlank()) {
            drawChip(canvas, body.chipText, body.chipPaint, left, blockTop)
        }

        val saved = canvas.save()
        canvas.translate(left, blockTop + body.chipHeight + body.chipGap)
        body.headline.draw(canvas)
        canvas.restoreToCount(saved)

        drawSourceLine(canvas, content.source, body.sourcePaint, left, sourceTop)
    }

    /**
     * The headline, clamped to whatever [available] height the rest of the block
     * leaves it. Line count is derived rather than fixed: the type is larger than the
     * design's, so the longest headlines run a line fewer — which reads better on a
     * phone than more, smaller lines — and can't overrun the card's edge whatever
     * size the layout asks for.
     */
    private fun headlineLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        available: Float,
        size: Float,
        maxLines: Int,
    ): StaticLayout {
        val lineHeight = size * HEADLINE_LINE_HEIGHT
        val lines = (available / lineHeight).toInt().coerceIn(1, maxLines)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            // A multiplier against the face's own spacing, since StaticLayout has no
            // way to set a line box directly.
            .setLineSpacing(0f, lineHeight / paint.fontSpacing)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
    }

    private fun drawChip(canvas: Canvas, text: String, paint: TextPaint, left: Float, top: Float) {
        val width = paint.measureText(text) + CHIP_PADDING_H * 2
        val height = paint.fontSpacing + CHIP_PADDING_V * 2
        canvas.drawRect(
            left, top, left + width, top + height,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER },
        )
        canvas.drawText(text, left + CHIP_PADDING_H, baselineFor(paint, top + height / 2f), paint)
    }

    private fun drawSourceLine(canvas: Canvas, source: String, paint: TextPaint, left: Float, top: Float) {
        val centreY = top + paint.fontSpacing / 2f
        val square = RectF(
            left,
            centreY - SOURCE_SQUARE / 2f,
            left + SOURCE_SQUARE,
            centreY + SOURCE_SQUARE / 2f,
        )
        canvas.drawRect(square, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AMBER })
        canvas.drawText(
            "Source: $source",
            left + SOURCE_SQUARE + SOURCE_GAP,
            baselineFor(paint, centreY),
            paint,
        )
    }

    // ---- Drawing helpers ----

    private fun textPaint(face: Typeface, size: Float, color: Int, letterSpacing: Float) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face
            textSize = size
            this.color = color
            this.letterSpacing = letterSpacing
        }

    private fun TextPaint.withShadow() = apply { setShadowLayer(12f, 0f, 2f, colorOf(0, 0.75f)) }

    /** The baseline that centres a line of [paint]'s text on [centreY]. */
    private fun baselineFor(paint: Paint, centreY: Float): Float =
        centreY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

    /** Scales [bitmap] to cover [target] and centre-crops the overflow, like CSS object-fit:cover. */
    private fun drawCropped(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val scale = maxOf(target.width() / bitmap.width, target.height() / bitmap.height)
        val cropWidth = (target.width() / scale).coerceAtMost(bitmap.width.toFloat())
        val cropHeight = (target.height() / scale).coerceAtMost(bitmap.height.toFloat())
        val source = Rect(
            ((bitmap.width - cropWidth) / 2f).toInt(),
            ((bitmap.height - cropHeight) / 2f).toInt(),
            ((bitmap.width + cropWidth) / 2f).toInt(),
            ((bitmap.height + cropHeight) / 2f).toInt(),
        )
        canvas.drawBitmap(
            bitmap, source, target,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
        )
    }

    private fun colorOf(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    // ---- Output ----

    /**
     * Writes [bitmap] into the cache directory the share provider exposes and returns
     * the file. One card per article id, so re-sharing the same story overwrites its
     * own file instead of piling up.
     */
    fun writeToCache(context: Context, bitmap: Bitmap, articleId: String): File {
        val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        prune(dir)
        val file = File(dir, "card-${articleId.hashCode().toUInt().toString(16)}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /**
     * Drops cards from earlier sessions. They can't be deleted at share time — the
     * receiving app reads the file after the chooser closes, whenever it gets round
     * to it — so they're cleared on the next share instead.
     */
    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - CARD_KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    const val SHARE_DIR = "share-cards"
    private const val CARD_KEEP_MS = 60 * 60 * 1000L

    /**
     * The whole wait a share is allowed before it gives up on the photo. Short on
     * purpose: the page lookup is a 64 KB peek and the image a couple of hundred, so
     * anything healthy lands well inside it, and what's left is a stalled host that
     * would only have kept the chooser waiting.
     *
     * Handed to the calls themselves rather than wrapped around them: both block, and
     * no deadline outside a blocking call can cut it short.
     */
    private const val PHOTO_BUDGET_MS = 4_000L

    /** Below this there isn't enough budget left for a call to be worth starting. */
    private const val MIN_CALL_MS = 300L

    /** Articles this session's shares have already found have no usable photo. */
    private val withoutPhoto = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Nothing larger is worth pulling down for a band 1080 wide. */
    private const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
}
