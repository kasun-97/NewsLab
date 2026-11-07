package com.kasun.newslab.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kasun.newslab.data.Article
import com.kasun.newslab.data.DesignStyle
import com.kasun.newslab.data.ListLayout
import com.kasun.newslab.data.StoryCluster
import com.kasun.newslab.data.TextUtil
import com.kasun.newslab.ui.theme.nd
import com.kasun.newslab.ui.theme.sourceHue
import kotlinx.coroutines.launch

// The Warm Ledger geometry, shared by every list density:
// 16dp screen gutter + 10dp rail column + 10dp gap = a 36dp text inset,
// and every between-row hairline starts at that inset.
val Gutter = 16.dp
val RailColumn = 10.dp
val RailGap = 10.dp
val TextInset = 36.dp

/** Small-caps section label ("MATCHED ARTICLES · 2", ...). */
@Composable
fun SectionLabel(text: String, color: Color) {
    Text(
        text,
        Modifier.padding(start = Gutter, end = Gutter, top = 16.dp, bottom = 6.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = color,
    )
}

/**
 * Day header for the timeline: the label, a hairline filling the remaining width,
 * and the day's article count trailing.
 */
@Composable
fun DayHeader(label: String, count: Int? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            color = nd.muted,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(nd.border))
        if (count != null) {
            Spacer(Modifier.width(10.dp))
            Text("$count", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = nd.faint)
        }
    }
}

/**
 * Header of a user-owned section — an amber tick and an amber label, because a
 * keyword section exists only because the reader configured it.
 */
@Composable
fun UserSectionHeader(text: String, trailing: String? = null, onTrailingClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = Gutter, end = Gutter, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(nd.tagAccent))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = nd.tagAccent,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing,
                Modifier.let { if (onTrailingClick != null) it.clickable(onClick = onTrailingClick) else it },
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = nd.tagAccent,
            )
        }
    }
}

/**
 * The keyword tag: the one mark that says "a rule of yours fired". An amber fill
 * with ink on top in Warm Ledger; the original red wash in Classic.
 */
@Composable
fun AmberTag(text: String, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .clip(RoundedCornerShape(nd.radiusTag))
            .background(nd.tagBg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = if (compact) 1.dp else 2.dp)
    ) {
        Text(
            text,
            fontSize = if (compact) 10.5.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            color = nd.tagText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The filled amber bookmark marking a saved article — a save is the reader's own. */
@Composable
fun SavedMark(size: Dp = 15.dp) {
    Icon(
        Icons.Filled.Bookmark,
        contentDescription = "Saved",
        modifier = Modifier.size(size),
        tint = nd.brandText,
    )
}

/**
 * Underline filter tab: active is ink with a 2dp amber underline (the underline is
 * the reader's own position — amber marks it), inactive is faint. [count] trails the
 * label in amber when present.
 */
@Composable
fun FilterTab(
    text: String,
    selected: Boolean,
    count: Int? = null,
    fontFamily: FontFamily? = null,
    onClick: () -> Unit,
) {
    // width(IntrinsicSize.Max) pins the column to its label's width; without it the
    // underline's fillMaxWidth collapses to zero inside a horizontally scrolling row,
    // where width constraints are unbounded.
    Column(
        Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.padding(top = 12.dp, bottom = 9.dp)) {
            Text(
                text,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontFamily = fontFamily,
                color = if (selected) nd.ink else nd.faint,
                maxLines = 1,
            )
            if (count != null) {
                Text(
                    " $count",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    color = if (selected) nd.brandText else nd.faint,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) nd.brandStroke else Color.Transparent)
        )
    }
}

/**
 * Toggleable chip for multi-select pickers (dialog group/list pickers). Selection
 * is an amber fill — a chip that is on is the reader's own choice; off is a
 * recessed outline.
 */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(nd.radiusChip)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) nd.brand else nd.chipBg)
            .let { if (selected) it else it.border(1.dp, nd.border, shape) }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) nd.onBrand else nd.muted,
        )
    }
}

/**
 * The 44×24 switch — the one pill left in the app, because here the shape is the
 * affordance. On is an amber track with an ink thumb: a setting turned on is the
 * reader's own choice.
 */
@Composable
fun NdSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val track = if (checked) Modifier.background(nd.brand, CircleShape)
    else Modifier.background(nd.chipBg, CircleShape).border(1.dp, nd.border, CircleShape)
    Box(
        Modifier
            .size(width = 44.dp, height = 24.dp)
            .let {
                if (onCheckedChange != null) {
                    it.toggleable(
                        value = checked,
                        role = Role.Switch,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onValueChange = onCheckedChange,
                    )
                } else it
            }
            .then(track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(18.dp)
                .background(if (checked) nd.onBrand else nd.faint, CircleShape)
        )
    }
}

/**
 * The large title every main section opens with. One component for all of them, so the
 * headers share a height and rhythm whichever tab you switch to: the subtitle is always
 * present and always one line, and actions trail on the right at a common size.
 *
 * [leading] sits ahead of the title and shifts it right, so only the Inbox uses it —
 * for the sources drawer, where that arrow has always lived.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (leading == null) Gutter else 4.dp, end = 6.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp,
                color = nd.ink,
            )
            Spacer(Modifier.height(2.dp))
            // Held to one line so a long subtitle can't make one section's header taller
            // than another's and shift everything below it.
            Text(
                subtitle,
                fontSize = 12.sp,
                color = nd.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions()
    }
}

/** Trailing action in a [ScreenHeader]; swaps to a spinner while [busy]. */
@Composable
fun HeaderAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = nd.muted,
    busy: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = nd.brandStroke)
        } else {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp), tint = tint)
        }
    }
}

/**
 * Search bar with a live result count and up/down navigation, used in place of a
 * screen's filter/header row while searching. The host owns the query, the
 * matching list, and the focused index; this just renders the controls.
 */
@Composable
fun ListSearchBar(
    query: String,
    matchCount: Int,
    currentIndex: Int,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Auto-focus a freshly opened (still empty) search, but not on remount —
    // returning from an article with search active shouldn't pop the keyboard
    // over the results being browsed.
    LaunchedEffect(Unit) { if (query.isEmpty()) focusRequester.requestFocus() }

    Column(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(nd.radiusThumb))
                .background(nd.chipBg)
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = nd.faint)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, fontSize = 15.sp, color = nd.faint)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    textStyle = TextStyle(fontSize = 15.sp, color = nd.ink),
                    singleLine = true,
                    cursorBrush = SolidColor(nd.brandStroke),
                )
            }
            if (query.isNotBlank()) {
                Text(
                    if (matchCount == 0) "0/0" else "${currentIndex + 1}/$matchCount",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nd.muted,
                )
                IconButton(onClick = onPrev, enabled = matchCount > 0, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowUp,
                        contentDescription = "Previous result",
                        modifier = Modifier.size(20.dp),
                        tint = if (matchCount > 0) nd.ink else nd.faint,
                    )
                }
                IconButton(onClick = onNext, enabled = matchCount > 0, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Next result",
                        modifier = Modifier.size(20.dp),
                        tint = if (matchCount > 0) nd.ink else nd.faint,
                    )
                }
            }
            IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close search", modifier = Modifier.size(16.dp), tint = nd.muted)
            }
        }
        // The field earns its amber underline once the reader has typed — the query
        // is theirs. Idle search chrome stays neutral.
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (query.isNotBlank()) nd.brandStroke else Color.Transparent)
        )
    }
}

@Composable
fun AddRowButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = nd.muted)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = nd.muted)
    }
}

/** Read-only label/value row used on the statistics screens. */
@Composable
fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = nd.muted)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
    }
}

@Composable
fun EmptyState(refreshing: Boolean, message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (refreshing) {
            CircularProgressIndicator(color = nd.brandStroke)
        } else {
            Text(message, color = nd.muted, fontSize = 14.sp)
        }
    }
}

/**
 * The 3dp source rail — the per-source hue's main appearance. Fills the row's text
 * column height rather than a fixed dp, so it grows with the font-size setting.
 * A read article's rail drops to 32%.
 */
@Composable
private fun SourceRail(color: Color, read: Boolean, barWidth: Dp = 3.dp) {
    Box(Modifier.width(RailColumn).fillMaxHeight().padding(top = 3.dp, bottom = 3.dp)) {
        Box(
            Modifier
                .width(barWidth)
                .fillMaxHeight()
                .alpha(if (read) 0.32f else 1f)
                .background(color)
        )
    }
}

/** The stepped three-bar rail marking a story cluster — the identity mark doing work. */
@Composable
private fun ClusterRail(colors: List<Color>, read: Boolean) {
    Row(
        Modifier.width(RailColumn).fillMaxHeight().padding(top = 3.dp, bottom = 3.dp).alpha(if (read) 0.32f else 1f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val heights = listOf(1f, 0.76f, 0.52f)
        colors.take(3).forEachIndexed { index, color ->
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight(heights.getOrElse(index) { 0.52f })
                    .background(color)
            )
        }
    }
}

/** Between-row hairline, starting at the 36dp text inset like every divider. */
@Composable
private fun RowRule() {
    HorizontalDivider(Modifier.padding(start = TextInset), thickness = 1.dp, color = nd.rule)
}

/**
 * Meta-line source name color: muted in Warm Ledger — a clear step down from the
 * ink headline, while the hue lives in the rail alone; the feed's own hue in
 * Classic, as the original design did.
 */
@Composable
private fun sourceNameColor(read: Boolean, color: Long): Color = when {
    read -> nd.faint
    nd.style == DesignStyle.CLASSIC -> sourceHue(color)
    else -> nd.muted
}

// The alerts carousel's type budget: one meta row, a two-line headline and a
// three-line summary. Every page is sized from these rather than a hardcoded dp,
// so the card grows with the app's font-size setting instead of clipping.
private const val ALERT_TITLE_LINES = 2
private const val ALERT_BODY_LINES = 3
private val ALERT_TITLE_LINE = 22.sp
private val ALERT_BODY_LINE = 18.sp

/**
 * Rough height of an alerts page, used only until a real one has been measured.
 * The cards themselves size to their content — reserving two headline lines and
 * three summary lines — because an estimate that came up even a little short got
 * clipped by the card's own rounded-corner clip, losing the third line.
 */
@Composable
fun alertCardHeight(): Dp = with(LocalDensity.current) {
    val meta = 16.sp.toDp() + 6.dp                        // keyword tag and its padding
    val title = ALERT_TITLE_LINE.toDp() * ALERT_TITLE_LINES
    val body = ALERT_BODY_LINE.toDp() * ALERT_BODY_LINES
    // 28dp of card padding, 8dp under the meta row, 5dp between headline and summary,
    // plus a little slack so a tall-ascender font can't crop the final line.
    28.dp + meta + 8.dp + title + 5.dp + body + 8.dp
}


/** Bordered card used in the pinned YOUR KEYWORDS section. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlertCard(
    article: Article,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    showImage: Boolean = true,
    highlighted: Boolean = false,
    onSourceClick: (() -> Unit)? = null,
    onKeywordClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(nd.radiusCard)
    Box(Modifier.padding(horizontal = Gutter, vertical = 5.dp)) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .border(
                    if (highlighted) 2.dp else 1.dp,
                    if (highlighted) nd.findHighlightActive else nd.border,
                    shape,
                )
                .background(nd.card)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AmberTag(article.matchedLabels.ifBlank { "alert" }, onClick = onKeywordClick)
                Spacer(Modifier.width(8.dp))
                Text(
                    article.feedName,
                    Modifier.let { if (onSourceClick != null) it.clickable(onClick = onSourceClick) else it },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = sourceNameColor(article.isRead, article.feedColor),
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                if (article.isSaved) {
                    SavedMark(size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(TextUtil.timeAgo(article.publishedAt), fontSize = 12.5.sp, color = nd.faint)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text(
                        article.title,
                        fontSize = 16.sp,
                        fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (article.isRead) nd.muted else nd.ink,
                        lineHeight = ALERT_TITLE_LINE,
                        // Always two lines tall, so a one-line headline doesn't pull the
                        // summary up and make the carousel jitter as you swipe.
                        minLines = ALERT_TITLE_LINES,
                        maxLines = ALERT_TITLE_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    // Rendered even when blank, and always three lines tall: the card
                    // takes its height from this, so every page measures the same.
                    Text(
                        article.summary,
                        fontSize = 13.sp,
                        color = nd.muted,
                        minLines = ALERT_BODY_LINES,
                        maxLines = ALERT_BODY_LINES,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = ALERT_BODY_LINE,
                    )
                }
                if (showImage && article.displayImageUrl.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    AsyncImage(
                        model = article.displayImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(nd.radiusThumb))
                            .background(nd.chipBg),
                    )
                }
            }
        }
    }
}

/** Timeline row: source rail, meta line, headline, 56dp thumbnail — ruled, not carded. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleRow(
    article: Article,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    showAlertChip: Boolean = true,
    showImage: Boolean = true,
    highlighted: Boolean = false,
    onSourceClick: (() -> Unit)? = null,
    onKeywordClick: (() -> Unit)? = null,
) {
    val matched = showAlertChip && article.matchedLabels.isNotBlank()
    val rowBg = when {
        highlighted -> nd.findHighlight
        matched -> nd.brandWash
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Row(Modifier.height(IntrinsicSize.Min).padding(horizontal = Gutter, vertical = 12.dp)) {
            SourceRail(sourceHue(article.feedColor), read = article.isRead)
            Spacer(Modifier.width(RailGap))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        article.feedName,
                        Modifier.let { if (onSourceClick != null) it.clickable(onClick = onSourceClick) else it },
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = sourceNameColor(article.isRead, article.feedColor),
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        TextUtil.timeAgo(article.publishedAt),
                        fontSize = 12.5.sp,
                        color = nd.faint,
                    )
                    if (matched) {
                        Spacer(Modifier.width(6.dp))
                        AmberTag(article.matchedLabels, compact = true, onClick = onKeywordClick)
                    }
                    Spacer(Modifier.weight(1f))
                    if (article.isSaved) {
                        SavedMark()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    article.title,
                    fontSize = 16.sp,
                    fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (article.isRead) nd.muted else nd.ink,
                    lineHeight = 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (showImage && article.displayImageUrl.isNotBlank()) {
                Spacer(Modifier.width(RailGap))
                AsyncImage(
                    model = article.displayImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .alpha(if (article.isRead) 0.55f else 1f)
                        .clip(RoundedCornerShape(nd.radiusThumb))
                        .background(nd.chipBg),
                )
            }
        }
        RowRule()
    }
}

/**
 * Compact, headline-only row: 2dp rail, no thumbnail, no meta line — nine rows a
 * screen against seven. The keyword tag and timestamp trail the headline instead
 * of spending a line of their own.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleCompactRow(
    article: Article,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    showAlertChip: Boolean = true,
    highlighted: Boolean = false,
    onSourceClick: (() -> Unit)? = null,
    onKeywordClick: (() -> Unit)? = null,
) {
    val matched = showAlertChip && article.matchedLabels.isNotBlank()
    val rowBg = when {
        highlighted -> nd.findHighlight
        matched -> nd.brandWash
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Row(Modifier.height(IntrinsicSize.Min).padding(horizontal = Gutter, vertical = 9.dp)) {
            SourceRail(sourceHue(article.feedColor), read = article.isRead, barWidth = 2.dp)
            Spacer(Modifier.width(RailGap))
            Text(
                article.title,
                Modifier.weight(1f),
                fontSize = 15.sp,
                fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = if (article.isRead) nd.muted else nd.ink,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (matched) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.padding(top = 3.dp)) {
                    AmberTag(article.matchedLabels, compact = true, onClick = onKeywordClick)
                }
            }
            if (article.isSaved) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.padding(top = 3.dp)) { SavedMark(size = 14.dp) }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                TextUtil.timeAgo(article.publishedAt),
                Modifier.padding(top = 3.dp),
                fontSize = 11.5.sp,
                color = nd.faint,
                maxLines = 1,
            )
        }
        RowRule()
    }
}

/** Renders an article in the timeline, card, or compact layout per [layout]. */
@Composable
fun ArticleListItem(
    layout: ListLayout,
    article: Article,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSaved: () -> Unit,
    showAlertChip: Boolean = true,
    showImage: Boolean = true,
    highlighted: Boolean = false,
    onSourceClick: (() -> Unit)? = null,
    onKeywordClick: (() -> Unit)? = null,
) {
    when (layout) {
        ListLayout.CARD -> ArticleCard(
            article = article,
            onOpen = onOpen,
            onLongPress = onLongPress,
            onToggleSaved = onToggleSaved,
            showAlertChip = showAlertChip,
            showImage = showImage,
            highlighted = highlighted,
            onSourceClick = onSourceClick,
            onKeywordClick = onKeywordClick,
        )
        ListLayout.COMPACT -> ArticleCompactRow(
            article = article,
            onOpen = onOpen,
            onLongPress = onLongPress,
            showAlertChip = showAlertChip,
            highlighted = highlighted,
            onSourceClick = onSourceClick,
            onKeywordClick = onKeywordClick,
        )
        ListLayout.TIMELINE -> ArticleRow(
            article = article,
            onOpen = onOpen,
            onLongPress = onLongPress,
            showAlertChip = showAlertChip,
            showImage = showImage,
            highlighted = highlighted,
            onSourceClick = onSourceClick,
            onKeywordClick = onKeywordClick,
        )
    }
}

/**
 * Photo-forward density: a rail stub and meta line, a full-gutter-width 16:9 photo,
 * headline, summary, and an action row. Not a boxed card — the same ruled paper as
 * every other density, separated by a full-width border.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleCard(
    article: Article,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSaved: () -> Unit,
    showAlertChip: Boolean = true,
    showImage: Boolean = true,
    highlighted: Boolean = false,
    onSourceClick: (() -> Unit)? = null,
    onKeywordClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val share = rememberArticleShare()
    val matched = showAlertChip && article.matchedLabels.isNotBlank()
    val rowBg = when {
        highlighted -> nd.findHighlight
        matched -> nd.brandWash
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(rowBg)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        Column(Modifier.padding(start = Gutter, end = Gutter, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 13.dp)
                        .alpha(if (article.isRead) 0.32f else 1f)
                        .background(sourceHue(article.feedColor))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    article.feedName,
                    Modifier
                        .weight(1f, fill = false)
                        .let { if (onSourceClick != null) it.clickable(onClick = onSourceClick) else it },
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = sourceNameColor(article.isRead, article.feedColor),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(TextUtil.timeAgo(article.publishedAt), fontSize = 12.5.sp, color = nd.faint, maxLines = 1)
                if (matched) {
                    Spacer(Modifier.width(6.dp))
                    AmberTag(article.matchedLabels, compact = true, onClick = onKeywordClick)
                }
            }
            if (showImage && article.displayImageUrl.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = article.displayImageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .alpha(if (article.isRead) 0.55f else 1f)
                        .clip(RoundedCornerShape(nd.radiusThumb))
                        .background(nd.chipBg),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                article.title,
                fontSize = 16.sp,
                fontWeight = if (article.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = if (article.isRead) nd.muted else nd.ink,
                lineHeight = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (article.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    article.summary,
                    fontSize = 13.sp,
                    color = nd.muted,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            // Action row: each action its own tap target, so tapping it consumes the
            // press rather than opening the article. Pulled 10dp left so the first
            // glyph aligns optically with the text edge.
            Row(
                Modifier.offset(x = (-10).dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                CardIconButton(
                    icon = if (article.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = if (article.isSaved) "Unsave" else "Save",
                    tint = if (article.isSaved) nd.brandText else nd.muted,
                    onClick = onToggleSaved,
                )
                CardIconButton(
                    icon = Icons.Outlined.Share,
                    contentDescription = "Share",
                    tint = nd.muted,
                    onClick = { share(article) },
                )
                CardIconButton(
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "Open link",
                    tint = nd.muted,
                    onClick = { openLink(context, article.link) },
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = nd.border)
    }
}

/** Compact icon-only action used on the card's action row. */
@Composable
private fun CardIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(width = 44.dp, height = 40.dp)
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp), tint = tint)
    }
}

/**
 * A collapsed cluster of the same story from several sources, shown as a swipeable
 * pager over each source's own take (headline, image, time). The stepped rail —
 * the identity mark — leads with the current source's hue; the source chips below
 * double as the page indicator, and tapping a chip jumps the pager to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoryClusterCard(
    cluster: StoryCluster,
    showImage: Boolean,
    onOpen: (Article) -> Unit,
    onLongPress: (Article) -> Unit,
) {
    val sources = cluster.sources
    val anySaved = cluster.members.any { it.isSaved }
    val pagerState = rememberPagerState { sources.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(state = pagerState) { page ->
            val source = sources[page]
            // The current page's hue leads the stepped rail; the other carrying
            // sources trail in their own hues.
            val railColors = buildList {
                add(sourceHue(source.feedColor))
                sources.forEachIndexed { index, other ->
                    if (index != page) add(sourceHue(other.feedColor))
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .combinedClickable(onClick = { onOpen(source) }, onLongClick = { onLongPress(source) })
                    .padding(start = Gutter, end = Gutter, top = 12.dp, bottom = 2.dp),
            ) {
                ClusterRail(railColors, read = source.isRead)
                Spacer(Modifier.width(RailGap))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            source.feedName,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = sourceNameColor(source.isRead, source.feedColor),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(TextUtil.timeAgo(source.publishedAt), fontSize = 12.5.sp, color = nd.faint)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.border(1.dp, nd.border).padding(horizontal = 5.dp, vertical = 1.dp)) {
                            Text(
                                when {
                                    // Same story from one source more than once — a
                                    // report count, not a source count.
                                    sources.size == 1 -> "${cluster.size} REPORTS"
                                    page == 0 -> "+${sources.size - 1} SOURCES"
                                    else -> "${page + 1} OF ${sources.size}"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                color = nd.muted,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        if (anySaved) {
                            SavedMark(size = 14.dp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        source.title,
                        fontSize = 16.sp,
                        fontWeight = if (source.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = if (source.isRead) nd.muted else nd.ink,
                        lineHeight = 22.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showImage && source.displayImageUrl.isNotBlank()) {
                    Spacer(Modifier.width(RailGap))
                    AsyncImage(
                        model = source.displayImageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .alpha(if (source.isRead) 0.55f else 1f)
                            .clip(RoundedCornerShape(nd.radiusThumb))
                            .background(nd.chipBg),
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = TextInset, end = Gutter, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sources.forEachIndexed { index, source ->
                val current = index == pagerState.currentPage
                val chipShape = RoundedCornerShape(nd.radiusChip)
                Row(
                    Modifier
                        .clip(chipShape)
                        .let {
                            if (current) it.background(nd.card).border(1.dp, nd.border, chipShape) else it
                        }
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } }
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(5.dp).background(sourceHue(source.feedColor)))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        source.feedName,
                        fontSize = 11.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (current) nd.ink else nd.faint,
                        maxLines = 1,
                    )
                }
            }
        }
        RowRule()
    }
}

/** Removable chip for an active source filter: hue dot, name, dismiss. */
@Composable
fun SourceChip(text: String, color: Long, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(nd.radiusChip)
    Row(
        Modifier
            .clip(shape)
            .background(nd.card)
            .border(1.dp, nd.border, shape)
            .padding(start = 10.dp, top = 5.dp, bottom = 5.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(sourceHue(color)))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove $text filter",
                modifier = Modifier.size(14.dp),
                tint = nd.muted,
            )
        }
    }
}

/** Removable chip for a keyword-alert filter, styled like the keyword tag itself. */
@Composable
fun KeywordChip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(nd.radiusChip))
            .background(nd.tagBg)
            .padding(start = 10.dp, top = 5.dp, bottom = 5.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = nd.tagText, maxLines = 1)
        Spacer(Modifier.width(2.dp))
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove $text filter",
                modifier = Modifier.size(14.dp),
                tint = nd.tagText,
            )
        }
    }
}
