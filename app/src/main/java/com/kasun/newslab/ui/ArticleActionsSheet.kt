package com.kasun.newslab.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.kasun.newslab.data.Article
import com.kasun.newslab.data.ShareCard
import com.kasun.newslab.ui.theme.nd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Long-press menu for an article: save, share, mark read/unread, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleActionsSheet(
    article: Article,
    onDismiss: () -> Unit,
    onToggleSaved: () -> Unit,
    onToggleRead: () -> Unit,
    onDelete: () -> Unit,
) {
    val share = rememberArticleShare()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 12.dp)) {
            ActionRow(
                icon = if (article.isSaved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                label = if (article.isSaved) "Unsave" else "Save",
                onClick = { onToggleSaved(); onDismiss() },
            )
            ActionRow(
                icon = Icons.Outlined.Share,
                label = "Share",
                onClick = {
                    share(article)
                    onDismiss()
                },
            )
            ActionRow(
                icon = if (article.isRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.MarkEmailRead,
                label = if (article.isRead) "Mark as unread" else "Mark as read",
                onClick = { onToggleRead(); onDismiss() },
            )
            ActionRow(
                icon = Icons.Outlined.Delete,
                label = "Delete",
                tint = nd.negative,
                onClick = { confirmDelete = true },
            )
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this article?",
            body = "This can't be undone.",
            confirmLabel = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
                onDismiss()
            },
        )
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, tint: Color = nd.ink, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}

/**
 * A share action safe to fire from a menu that closes itself. Drawing the card takes
 * a moment — its photo first, then the render — and a scope remembered inside the
 * long-press sheet would be cancelled the instant that sheet dismissed, taking the
 * half-built card with it. The activity's own scope outlives it.
 */
@Composable
fun rememberArticleShare(): (Article) -> Unit {
    val context = LocalContext.current
    val activityScope = (context as? ComponentActivity)?.lifecycleScope
    val fallbackScope = rememberCoroutineScope()
    return remember(context, activityScope, fallbackScope) {
        { article -> (activityScope ?: fallbackScope).launch { shareArticle(context, article) } }
    }
}

/**
 * Renders [article] as its share card and opens the system share sheet with it.
 *
 * The intent carries both the image and the usual [shareText]: a card says nothing
 * to a target that only takes text, and a link is what most people actually want
 * pasted alongside the picture. Should the card fail to build — no photo to be had,
 * no room in the cache — the share degrades to the plain text it has always been
 * rather than not happening.
 */
suspend fun shareArticle(context: Context, article: Article) {
    val card = runCatching {
        val content = ShareCard.contentFor(context, article)
        val bitmap = withContext(Dispatchers.Default) { ShareCard.render(context, content) }
        val file = withContext(Dispatchers.IO) { ShareCard.writeToCache(context, bitmap, article.id) }
        FileProvider.getUriForFile(context, "${context.packageName}.sharecards", file)
    }.getOrNull()

    val intent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_SUBJECT, article.title)
        putExtra(Intent.EXTRA_TEXT, shareText(article))
        if (card == null) {
            type = "text/plain"
        } else {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, card)
            // Some targets read the grant off the clip data rather than the extra,
            // so the card is attached both ways.
            clipData = ClipData.newUri(context.contentResolver, article.title, card)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, null))
}

private const val SHARE_PARAGRAPH_MAX_CHARS = 400

/** Cuts at the last full word within maxChars and marks the cut with an ellipsis. */
private fun truncateAtWord(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val cut = text.substring(0, maxChars)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace > 0) cut.substring(0, lastSpace) else cut) + "…"
}

/**
 * The article's first paragraph, then the link — and nothing else. The source and the
 * headline used to open this, in bold, back when the share was text alone; the card
 * carries both now, and repeating them under a picture of them reads as a mistake.
 *
 * A card that fails to build falls back to this same text, which then arrives without
 * its headline. That is the lesser of the two: an odd-looking share now and then,
 * against every good one saying everything twice.
 */
private fun shareText(article: Article): String {
    val paragraph = truncateAtWord(article.summary.substringBefore("\n\n").trim(), SHARE_PARAGRAPH_MAX_CHARS)
    return buildString {
        if (paragraph.isNotBlank()) {
            append(paragraph)
            append("\n")
        }
        append(article.link)
    }
}
