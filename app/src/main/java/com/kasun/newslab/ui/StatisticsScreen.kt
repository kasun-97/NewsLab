package com.kasun.newslab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.TextUtil
import com.kasun.newslab.ui.theme.nd
import com.kasun.newslab.ui.theme.sourceHue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** On-disk footprint of the app's own persisted data (article DB) and Coil's image cache. */
private data class StorageStats(val databaseBytes: Long, val imageCacheBytes: Long)

private suspend fun measureStorage(context: android.content.Context): StorageStats =
    withContext(Dispatchers.IO) {
        // Room defaults to WAL journal mode, which can split committed data across
        // sidecar files that don't exist until the first checkpoint.
        val dbFile = context.getDatabasePath("newslab.db")
        val walBytes = File(dbFile.path + "-wal").let { if (it.exists()) it.length() else 0L }
        val shmBytes = File(dbFile.path + "-shm").let { if (it.exists()) it.length() else 0L }
        val dbBytes = (if (dbFile.exists()) dbFile.length() else 0L) + walBytes + shmBytes

        // Coil 2.x's default disk cache directory; a fresh install or "show images"
        // turned off the whole time means this simply doesn't exist yet.
        val cacheDir = File(context.cacheDir, "image_cache")
        val cacheBytes = if (cacheDir.exists()) {
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
        StorageStats(dbBytes, cacheBytes)
    }

/** Read-only tallies over the whole library: counts, per-source breakdown, refresh history. */
@Composable
fun StatisticsScreen(vm: MainViewModel, onBack: () -> Unit, listState: LazyListState) {
    val feeds by vm.feeds.collectAsState()
    val categories by vm.categories.collectAsState()
    val rules by vm.rules.collectAsState()
    val feedArticleCounts by vm.feedArticleCounts.collectAsState()
    val articleStats by vm.articleStats.collectAsState()
    val lastRefreshAt by vm.lastRefreshAt.collectAsState()

    val countsByFeed = remember(feedArticleCounts) { feedArticleCounts.associateBy { it.feedId } }
    val sortedFeeds = remember(feeds, countsByFeed) {
        feeds.sortedByDescending { (countsByFeed[it.id]?.let { c -> c.unread + c.readCount }) ?: 0 }
    }

    val context = LocalContext.current
    var storage by remember { mutableStateOf<StorageStats?>(null) }
    LaunchedEffect(Unit) { storage = measureStorage(context) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = nd.ink)
            }
            Text("Statistics", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = nd.ink)
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item { StatRow("Total articles", "${articleStats.total}") }
            item { StatRow("Unread", "${articleStats.unread}") }
            item { StatRow("Saved", "${articleStats.saved}") }
            item { StatRow("Keyword alerts", "${articleStats.alertMatches}") }
            item { StatRow("Sources", "${feeds.size}") }
            item { StatRow("Categories", "${categories.size}") }
            item { StatRow("Keyword rules", "${rules.size}") }
            item {
                StatRow("Last refreshed", if (lastRefreshAt == 0L) "Never" else TextUtil.timeAgo(lastRefreshAt))
            }
            item { SectionLabel("STORAGE", nd.muted) }
            item { StatRow("Database", storage?.let { TextUtil.formatBytes(it.databaseBytes) } ?: "…") }
            item { StatRow("Image cache", storage?.let { TextUtil.formatBytes(it.imageCacheBytes) } ?: "…") }
            if (sortedFeeds.isNotEmpty()) {
                item { SectionLabel("BY SOURCE", nd.muted) }
                items(sortedFeeds, key = { it.id }) { feed ->
                    val total = countsByFeed[feed.id]?.let { it.unread + it.readCount } ?: 0
                    SourceStatRow(feed.name, feed.color, total)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SourceStatRow(name: String, color: Long, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(sourceHue(color)))
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            Modifier.weight(1f),
            fontSize = 13.5.sp,
            color = nd.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$count", fontSize = 13.sp, color = nd.faint)
    }
}
