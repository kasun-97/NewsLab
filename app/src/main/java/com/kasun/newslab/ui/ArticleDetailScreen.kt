package com.kasun.newslab.ui

import android.app.SearchManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.Article
import com.kasun.newslab.data.ArticleExtractor
import com.kasun.newslab.data.TextUtil
import com.kasun.newslab.ui.theme.nd
import com.kasun.newslab.ui.theme.sourceHue
import com.kasun.newslab.ui.theme.toFontFamily
import kotlinx.coroutines.launch

/** A single find-in-page hit: a character range within the title or the summary. */
private data class FindMatch(val inTitle: Boolean, val start: Int, val end: Int)

/** In-app reader for a single article, fetched from the feed's own RSS data. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleDetailScreen(vm: MainViewModel, article: Article, onBack: () -> Unit) {
    val categories by vm.categories.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val share = rememberArticleShare()
    val pageImages by vm.alwaysUsePageImages.collectAsState()
    // The Settings font applies to the article itself and nothing else — not the source
    // line, category chips or buttons around it, and not any other screen.
    val readingFont = vm.fontFamily.collectAsState().value.toFontFamily()
    val context = LocalContext.current
    val view = LocalView.current
    val textToolbar = remember(view) { ArticleTextToolbar(view) }
    val matchedCategories = categories.filter { article.categoryIds.contains(",${it.id},") }

    LaunchedEffect(article.id, showImages, pageImages) {
        if (showImages) vm.ensureArticleImage(article, highQuality = pageImages)
    }

    // ---- Full-text reader state ----
    val scope = rememberCoroutineScope()
    // Some feeds already syndicate the whole article as the summary; extracting the
    // page would just re-render what's shown, so the reader offers nothing extra.
    val rssComplete = remember(article.summary) { rssLooksComplete(article.summary) }
    // Plain remember (not saveable): the body can be large, and it's persisted to the
    // DB anyway, so the cached-read effect below restores it after a config change.
    var fullText by remember(article.id) { mutableStateOf<String?>(null) }
    var textStatus by remember(article.id) { mutableStateOf(FullTextStatus.COLLAPSED) }
    // Show a cached body straight away (also the offline path for saved articles);
    // uncached articles wait for the explicit "Read full article" tap.
    LaunchedEffect(article.id) {
        if (!rssComplete && fullText == null) {
            vm.cachedArticleText(article)?.let { fullText = it; textStatus = FullTextStatus.LOADED }
        }
    }
    fun loadFullText() {
        textStatus = FullTextStatus.LOADING
        scope.launch {
            textStatus = when (val result = vm.loadArticleText(article)) {
                is ArticleExtractor.Result.Success -> {
                    fullText = result.text
                    FullTextStatus.LOADED
                }
                ArticleExtractor.Result.Empty -> FullTextStatus.EMPTY
                ArticleExtractor.Result.Error -> FullTextStatus.ERROR
                ArticleExtractor.Result.Blocked -> FullTextStatus.BLOCKED
            }
        }
    }

    // ---- Find-in-page state ----
    var findActive by rememberSaveable(article.id) { mutableStateOf(false) }
    var findQuery by rememberSaveable(article.id) { mutableStateOf("") }
    var currentMatch by rememberSaveable(article.id) { mutableStateOf(0) }
    val findFocus = remember { FocusRequester() }
    val titleRequester = remember { BringIntoViewRequester() }
    val summaryRequester = remember { BringIntoViewRequester() }
    var titleLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var summaryLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    val summaryText = article.summary.ifBlank { "No summary provided by this feed." }
    val matches = remember(article.title, article.summary, findQuery) {
        val q = findQuery.trim()
        if (q.isBlank()) {
            emptyList()
        } else {
            buildList {
                fun scan(text: String, inTitle: Boolean) {
                    var i = text.indexOf(q, ignoreCase = true)
                    while (i >= 0) {
                        add(FindMatch(inTitle, i, i + q.length))
                        i = text.indexOf(q, startIndex = i + q.length, ignoreCase = true)
                    }
                }
                scan(article.title, true)
                scan(article.summary, false)
            }
        }
    }
    // Keep the active index valid as the match set changes under it.
    val safeMatch = currentMatch.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    val findHi = nd.findHighlight
    val findHiActive = nd.findHighlightActive
    val titleText = remember(article.title, matches, safeMatch, findHi, findHiActive) {
        buildHighlighted(article.title, matches, inTitle = true, safeMatch, findHi, findHiActive)
    }
    val summaryAnnotated = remember(summaryText, matches, safeMatch, findHi, findHiActive) {
        buildHighlighted(summaryText, matches, inTitle = false, safeMatch, findHi, findHiActive)
    }

    // Scroll the focused match into view whenever it (or the layout) changes.
    LaunchedEffect(safeMatch, matches, titleLayout, summaryLayout) {
        val m = matches.getOrNull(safeMatch) ?: return@LaunchedEffect
        val layout = (if (m.inTitle) titleLayout else summaryLayout) ?: return@LaunchedEffect
        val requester = if (m.inTitle) titleRequester else summaryRequester
        val offset = m.start.coerceIn(0, (layout.layoutInput.text.length - 1).coerceAtLeast(0))
        val box = runCatching { layout.getBoundingBox(offset) }.getOrNull() ?: return@LaunchedEffect
        runCatching { requester.bringIntoView(box) }
    }

    fun closeFind() {
        findActive = false
        findQuery = ""
        currentMatch = 0
    }

    // While find is open, Back closes it instead of leaving the article.
    BackHandler(enabled = findActive) { closeFind() }

    Column(Modifier.fillMaxSize()) {
        if (findActive) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = nd.faint)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (findQuery.isEmpty()) {
                        Text("Find in article", fontSize = 15.sp, color = nd.faint)
                    }
                    BasicTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            currentMatch = 0
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(findFocus),
                        textStyle = TextStyle(fontSize = 15.sp, color = nd.ink),
                        singleLine = true,
                        cursorBrush = SolidColor(nd.ink),
                    )
                }
                if (findQuery.isNotBlank()) {
                    Text(
                        if (matches.isEmpty()) "0/0" else "${safeMatch + 1}/${matches.size}",
                        fontSize = 12.sp,
                        color = nd.faint,
                    )
                }
                IconButton(
                    onClick = { if (matches.isNotEmpty()) currentMatch = (safeMatch - 1 + matches.size) % matches.size },
                    enabled = matches.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Previous match",
                        modifier = Modifier.size(20.dp),
                        tint = if (matches.isEmpty()) nd.faint else nd.ink,
                    )
                }
                IconButton(
                    onClick = { if (matches.isNotEmpty()) currentMatch = (safeMatch + 1) % matches.size },
                    enabled = matches.isNotEmpty(),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Next match",
                        modifier = Modifier.size(20.dp),
                        tint = if (matches.isEmpty()) nd.faint else nd.ink,
                    )
                }
                IconButton(onClick = { closeFind() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close find", modifier = Modifier.size(18.dp), tint = nd.ink)
                }
            }
            LaunchedEffect(Unit) { findFocus.requestFocus() }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = nd.ink)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { findActive = true }) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = "Find in article",
                        modifier = Modifier.size(20.dp),
                        tint = nd.muted,
                    )
                }
                IconButton(onClick = { vm.toggleSaved(article) }) {
                    Icon(
                        if (article.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (article.isSaved) "Unsave" else "Save",
                        tint = if (article.isSaved) nd.brandText else nd.muted,
                    )
                }
                IconButton(onClick = { share(article) }) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(21.dp),
                        tint = nd.muted,
                    )
                }
                IconButton(onClick = { openLink(context, article.link) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open original", tint = nd.muted)
                }
            }
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(14.dp))

            if (showImages && article.displayImageUrl.isNotBlank()) {
                AsyncImage(
                    model = article.displayImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 10f)
                        .clip(RoundedCornerShape(nd.radiusThumb))
                        .background(nd.chipBg),
                )
                Spacer(Modifier.height(14.dp))
            }

            // One meta line: the amber tag (if a rule fired), the source rail stub,
            // the source name, and the timestamp.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (article.matchedLabels.isNotBlank()) {
                    AmberTag(article.matchedLabels)
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.size(width = 3.dp, height = 13.dp).background(sourceHue(article.feedColor)))
                Spacer(Modifier.width(6.dp))
                Text(article.feedName, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = nd.muted)
                Spacer(Modifier.width(6.dp))
                Text(TextUtil.timeAgo(article.publishedAt), fontSize = 12.5.sp, color = nd.faint)
            }
            Spacer(Modifier.height(12.dp))

            // Custom toolbar so a text selection offers Search/Share alongside Copy —
            // Compose's built-in selection toolbar only ever offers Copy.
            CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
                SelectionContainer {
                    Column {
                        Text(
                            titleText,
                            modifier = Modifier.bringIntoViewRequester(titleRequester),
                            onTextLayout = { titleLayout = it },
                            fontFamily = readingFont,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                            lineHeight = 28.sp,
                            color = nd.ink,
                        )

                        if (matchedCategories.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                matchedCategories.forEach { category ->
                                    Box(
                                        Modifier
                                            .border(1.dp, nd.border, RoundedCornerShape(nd.radiusChip))
                                            .padding(horizontal = 9.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            category.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = nd.muted,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))

                        Text(
                            summaryAnnotated,
                            modifier = Modifier.bringIntoViewRequester(summaryRequester),
                            onTextLayout = { summaryLayout = it },
                            fontFamily = readingFont,
                            fontSize = 17.sp,
                            color = if (article.summary.isBlank()) nd.faint else nd.ink,
                            lineHeight = 28.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            when (textStatus) {
                // Hidden when the feed already carried the full article.
                FullTextStatus.COLLAPSED -> if (!rssComplete) ReadFullArticleButton { loadFullText() }
                FullTextStatus.LOADING -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = nd.muted)
                    Spacer(Modifier.width(10.dp))
                    Text("Extracting article…", fontSize = 14.sp, color = nd.muted)
                }
                FullTextStatus.LOADED -> fullText?.let { body ->
                    HorizontalDivider(color = nd.border, thickness = 1.dp)
                    Spacer(Modifier.height(16.dp))
                    CompositionLocalProvider(LocalTextToolbar provides textToolbar) {
                        SelectionContainer {
                            Column {
                                body.split("\n\n").forEach { paragraph ->
                                    Text(
                                        paragraph,
                                        fontFamily = readingFont,
                                        fontSize = 17.sp,
                                        color = nd.ink,
                                        lineHeight = 28.sp,
                                    )
                                    Spacer(Modifier.height(14.dp))
                                }
                            }
                        }
                    }
                }
                FullTextStatus.EMPTY -> Text(
                    "Couldn't find the article text on this page. Open the original to read it.",
                    fontSize = 14.sp,
                    color = nd.faint,
                    lineHeight = 20.sp,
                )
                FullTextStatus.BLOCKED -> Text(
                    "This site asks readers not to extract its articles. Open the original to read it.",
                    fontSize = 14.sp,
                    color = nd.faint,
                    lineHeight = 20.sp,
                )
                FullTextStatus.ERROR -> Column {
                    Text(
                        "Couldn't load the article. Check your connection and try again.",
                        fontSize = 14.sp,
                        color = nd.faint,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    ReadFullArticleButton(label = "Retry") { loadFullText() }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Read the full story on ${article.feedName}  →",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = nd.brandText,
                modifier = Modifier
                    .clickable { openLink(context, article.link) }
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The three-state control that kicks off (or retries) full-text extraction. */
private enum class FullTextStatus { COLLAPSED, LOADING, LOADED, EMPTY, ERROR, BLOCKED }

private const val RSS_FULLTEXT_MIN_CHARS = 1500
private val TRUNCATION_MARKERS =
    listOf("…", "...", "[…]", "[...]", "read more", "continue reading")

/**
 * Whether the RSS summary already looks like the complete article rather than an
 * excerpt: long enough, and not ending in a "read more" / ellipsis truncation
 * marker (which betrays a cut-off excerpt however long it is).
 */
private fun rssLooksComplete(summary: String): Boolean {
    val text = summary.trim()
    if (text.length < RSS_FULLTEXT_MIN_CHARS) return false
    return TRUNCATION_MARKERS.none { text.endsWith(it, ignoreCase = true) }
}

@Composable
private fun ReadFullArticleButton(label: String = "Read full article", onClick: () -> Unit) {
    // A ruled row, not a pill: hairline above and below, full width, like the mock's
    // "Read the full text here" expander.
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        HorizontalDivider(color = nd.border, thickness = 1.dp)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null, modifier = Modifier.size(19.dp), tint = nd.muted)
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(20.dp), tint = nd.muted)
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)
    }
}

/** Plain text with a background span behind each find match; the focused match gets the stronger color. */
private fun buildHighlighted(
    text: String,
    matches: List<FindMatch>,
    inTitle: Boolean,
    currentMatch: Int,
    highlight: Color,
    highlightActive: Color,
): AnnotatedString = buildAnnotatedString {
    append(text)
    matches.forEachIndexed { i, m ->
        if (m.inTitle == inTitle && m.end <= text.length) {
            addStyle(SpanStyle(background = if (i == currentMatch) highlightActive else highlight), m.start, m.end)
        }
    }
}

/**
 * Compose's built-in text-selection toolbar (AndroidTextToolbar) only ever shows
 * Copy — unlike a native TextView, it doesn't query ACTION_PROCESS_TEXT handlers,
 * so Share/Search never appear. This backs the same ActionMode API with a fixed
 * Copy/Search/Share menu instead. showMenu() isn't handed the selected text, so
 * Search/Share piggyback on onCopyRequested (which puts it on the clipboard) and
 * then read it straight back off the clipboard.
 */
private class ArticleTextToolbar(private val view: View) : TextToolbar {
    private var actionMode: ActionMode? = null
    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        actionMode?.finish()
        actionMode = view.startActionMode(
            // Callback2 (not plain Callback) so onGetContentRect can be overridden —
            // without it a TYPE_FLOATING ActionMode ignores the selection and anchors
            // itself to the top of the screen instead.
            object : ActionMode.Callback2() {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    if (onCopyRequested != null) menu.add(0, ID_COPY, 0, "Copy")
                    menu.add(0, ID_SEARCH, 1, "Search")
                    menu.add(0, ID_SHARE, 2, "Share")
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                    when (item.itemId) {
                        ID_COPY -> onCopyRequested?.invoke()
                        ID_SEARCH -> {
                            onCopyRequested?.invoke()
                            searchClipboardText(view.context)
                        }
                        ID_SHARE -> {
                            onCopyRequested?.invoke()
                            shareClipboardText(view.context)
                        }
                        else -> return false
                    }
                    mode.finish()
                    return true
                }

                override fun onDestroyActionMode(mode: ActionMode) {
                    if (actionMode === mode) {
                        actionMode = null
                        status = TextToolbarStatus.Hidden
                    }
                }

                override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                    outRect.set(
                        rect.left.toInt(),
                        rect.top.toInt(),
                        rect.right.toInt(),
                        rect.bottom.toInt(),
                    )
                }
            },
            ActionMode.TYPE_FLOATING,
        )
        status = TextToolbarStatus.Shown
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        actionMode?.finish()
        actionMode = null
    }

    private companion object {
        const val ID_COPY = 1
        const val ID_SEARCH = 2
        const val ID_SHARE = 3
    }
}

private fun clipboardText(context: Context): String {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val clip = manager.primaryClip?.takeIf { it.itemCount > 0 } ?: return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

private fun searchClipboardText(context: Context) {
    val text = clipboardText(context)
    if (text.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, text))
    } catch (_: Exception) {
        // No web-search handler on this device; nothing more we can do.
    }
}

private fun shareClipboardText(context: Context) {
    val text = clipboardText(context)
    if (text.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
        // No share target available.
    }
}
