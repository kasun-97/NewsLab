package com.kasun.newslab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.data.FeedDiscovery
import com.kasun.newslab.ui.theme.nd
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Something another app handed over through the share sheet. [url] is null when the
 * shared text held no web link, which is still worth telling the reader about rather
 * than opening the app as if nothing had been asked of it.
 */
data class IncomingShare(val url: String?)

/** Pulls a usable web link out of shared text. Pure, so it is unit-tested directly. */
object SharedLink {

    // Deliberately loose: the candidate is validated by OkHttp's parser afterwards,
    // which is the same parser every later fetch of this URL goes through.
    private val URL_PATTERN = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    // Sentence punctuation that sticks to a link pasted at the end of a sentence.
    private const val TRAILING = ".,;:!?'\"…"

    /**
     * The first http(s) URL in [text], else in [subject]. Apps disagree on where the
     * link goes: browsers put it in the text, some readers put the headline there and
     * the link in the subject, and many append it to a sentence of their own.
     */
    fun extract(text: String?, subject: String?): String? = firstUrl(text) ?: firstUrl(subject)

    /** The host without a leading `www.`, the name a link falls back to. */
    fun hostLabel(url: String): String =
        url.toHttpUrlOrNull()?.host?.removePrefix("www.") ?: url

    private fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        // Returned in OkHttp's canonical form so an upper-case "HTTPS://" doesn't trip
        // the case-sensitive scheme checks further down the add-feed path.
        return URL_PATTERN.findAll(text)
            .mapNotNull { trimTrailing(it.value).toHttpUrlOrNull()?.toString() }
            .firstOrNull()
    }

    /**
     * Drops trailing punctuation, and a closing bracket only when it has no partner
     * inside the URL: "(see https://x.org/a)" loses it, a Wikipedia "Foo_(bar)" keeps it.
     */
    private fun trimTrailing(raw: String): String {
        var url = raw
        while (url.isNotEmpty()) {
            val last = url.last()
            val unbalanced = (last == ')' && url.count { it == '(' } < url.count { it == ')' }) ||
                (last == ']' && url.count { it == '[' } < url.count { it == ']' })
            if (last !in TRAILING && !unbalanced) break
            url = url.dropLast(1)
        }
        return url
    }
}

private const val STAGE_CHOOSE = "choose"
private const val STAGE_DISCOVERING = "discovering"
private const val STAGE_FOUND = "found"
private const val STAGE_SAVING_LINK = "saving_link"

/**
 * What to do with a link shared into the app: follow its site as a source, or keep the
 * page in Links. The stage and discovery results are saveable, and the network work runs
 * in effects keyed on the stage, so a rotation mid-search picks the search back up
 * instead of leaving the dialog stuck on a spinner whose coroutine was cancelled.
 *
 * [onAddSource] and [onSaveLink] are expected to close the dialog.
 */
@Composable
fun ShareTargetDialog(
    url: String,
    linksEnabled: Boolean,
    isSubscribed: (String) -> Boolean,
    onDiscover: suspend (String) -> List<FeedDiscovery.DiscoveredFeed>,
    onFetchTitle: suspend (String) -> String?,
    onAddSource: (name: String, url: String) -> Unit,
    onSaveLink: (title: String, url: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var stage by rememberSaveable { mutableStateOf(STAGE_CHOOSE) }
    var found by rememberSaveable(
        stateSaver = listSaver<List<FeedDiscovery.DiscoveredFeed>, String>(
            save = { feeds -> feeds.flatMap { listOf(it.name, it.url) } },
            restore = { flat -> flat.chunked(2).map { (name, feedUrl) -> FeedDiscovery.DiscoveredFeed(name, feedUrl) } },
        ),
    ) { mutableStateOf(emptyList()) }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val host = SharedLink.hostLabel(url)

    LaunchedEffect(stage) {
        when (stage) {
            STAGE_DISCOVERING -> {
                found = try {
                    onDiscover(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                selected = 0
                stage = STAGE_FOUND
            }
            STAGE_SAVING_LINK -> {
                val title = try {
                    onFetchTitle(url)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                // Many sites refuse non-browser requests; the host still makes a
                // recognisable label, and the reader can rename it in Links.
                onSaveLink(title?.takeIf { it.isNotBlank() } ?: host, url)
            }
        }
    }

    val choice = found.getOrNull(selected)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shared link") },
        text = {
            Column {
                Text(host, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(url, fontSize = 11.sp, color = nd.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                when (stage) {
                    STAGE_CHOOSE -> Text(
                        if (linksEnabled) "Follow this site as a source, or keep the page in Links."
                        else "Follow this site as a source?",
                        fontSize = 13.sp,
                        color = nd.muted,
                    )
                    STAGE_DISCOVERING -> Progress("Looking for a feed on $host…")
                    STAGE_SAVING_LINK -> Progress("Fetching the page title…")
                    STAGE_FOUND -> when {
                        found.isEmpty() -> Text("No feed found on $host", fontSize = 13.sp, color = nd.muted)
                        found.size == 1 -> {
                            Text("Found: ${found[0].name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                            Text(found[0].url, fontSize = 11.sp, color = nd.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        else -> {
                            Text("${found.size} feeds found — pick one", fontSize = 13.sp, color = nd.muted)
                            Spacer(Modifier.height(4.dp))
                            found.forEachIndexed { index, feed ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { selected = index }
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = index == selected,
                                        onClick = { selected = index },
                                        colors = RadioButtonDefaults.colors(selectedColor = nd.brandStroke),
                                    )
                                    Column {
                                        Text(feed.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                                        Text(feed.url, fontSize = 11.sp, color = nd.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                if (stage == STAGE_FOUND && choice != null && isSubscribed(choice.url)) {
                    Spacer(Modifier.height(8.dp))
                    Text("Already in your sources", fontSize = 12.sp, color = nd.muted)
                }
            }
        },
        confirmButton = {
            when {
                stage == STAGE_CHOOSE -> Row {
                    if (linksEnabled) {
                        TextButton(onClick = { stage = STAGE_SAVING_LINK }) { Text("Save to Links") }
                    }
                    TextButton(onClick = { stage = STAGE_DISCOVERING }) { Text("Add as source") }
                }
                stage == STAGE_FOUND && choice != null -> TextButton(
                    onClick = { onAddSource(choice.name, choice.url) },
                    enabled = !isSubscribed(choice.url),
                ) { Text("Add source") }
                // Nothing to subscribe to, but the page itself can still be kept.
                stage == STAGE_FOUND && linksEnabled -> TextButton(onClick = { stage = STAGE_SAVING_LINK }) {
                    Text("Save to Links")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Progress(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = nd.muted)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, color = nd.muted)
    }
}
