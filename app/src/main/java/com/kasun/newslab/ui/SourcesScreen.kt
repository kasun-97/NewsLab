package com.kasun.newslab.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.Feed
import com.kasun.newslab.data.FeedDiscovery
import com.kasun.newslab.data.FeedGroup
import com.kasun.newslab.data.FeedHealth
import com.kasun.newslab.data.OpmlManager
import com.kasun.newslab.data.TextUtil
import com.kasun.newslab.ui.theme.nd
import com.kasun.newslab.ui.theme.sourceHue
import kotlinx.coroutines.launch

/** Manage RSS sources, organized into groups a source can belong to more than one of. */
@Composable
fun SourcesScreen(vm: MainViewModel, onBack: () -> Unit, listState: LazyListState) {
    val feeds by vm.feeds.collectAsState()
    val feedGroups by vm.feedGroups.collectAsState()
    val feedGroupMembers by vm.feedGroupMembers.collectAsState()

    val feedIdsByGroup = remember(feedGroupMembers) {
        feedGroupMembers.groupBy({ it.groupId }, { it.feedId }).mapValues { it.value.toSet() }
    }
    val groupIdsByFeed = remember(feedGroupMembers) {
        feedGroupMembers.groupBy({ it.feedId }, { it.groupId }).mapValues { it.value.toSet() }
    }

    val context = LocalContext.current
    // OPML is XML; a broad picker is used for import because its MIME type varies.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri -> if (uri != null) vm.exportOpml(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importOpml(uri) }

    // Saveable ids (not entity objects) so open dialogs survive rotation.
    var showAddFeed by rememberSaveable { mutableStateOf(false) }
    var showAddGroup by rememberSaveable { mutableStateOf(false) }
    var editFeedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteFeedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteGroupId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Sources that have been failing for a day or more. Recomputed when the feed list
    // changes, which every refresh does, so a fixed source drops out on its own.
    val problemFeeds = remember(feeds) { FeedHealth.problems(feeds, System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    // Plain remember, like the discovery choices in FeedDialog: the search dies with the
    // composition on rotation anyway, so there is nothing to restore its answer into.
    var searchingFeedIds by remember { mutableStateOf(emptySet<Long>()) }
    var replaceOffer by remember { mutableStateOf<Pair<Long, FeedDiscovery.DiscoveredFeed>?>(null) }
    var pickOffer by remember { mutableStateOf<Pair<Long, List<FeedDiscovery.DiscoveredFeed>>?>(null) }
    val findAgain: (Feed) -> Unit = { feed ->
        scope.launch {
            searchingFeedIds = searchingFeedIds + feed.id
            try {
                when (val outcome = vm.rediscoverFeed(feed)) {
                    is FeedHealth.Rediscovery.One -> replaceOffer = feed.id to outcome.feed
                    is FeedHealth.Rediscovery.Several -> pickOffer = feed.id to outcome.feeds
                    // Nothing to choose from; the ViewModel has already said so.
                    else -> Unit
                }
            } finally {
                searchingFeedIds = searchingFeedIds - feed.id
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = nd.ink)
            }
            Text("Sources", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = nd.ink)
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (problemFeeds.isNotEmpty()) {
                item(key = "problems") { SectionLabel("PROBLEMS", nd.negative) }
                items(problemFeeds, key = { "problem-${it.id}" }) { feed ->
                    ProblemRow(
                        feed = feed,
                        searching = feed.id in searchingFeedIds,
                        onEdit = { editFeedId = feed.id },
                        onFindAgain = { findAgain(feed) },
                    )
                }
                item(key = "problems-end") { HorizontalDivider(color = nd.border, thickness = 1.dp) }
            }
            item { AddRowButton("Add source") { showAddFeed = true } }
            item { AddRowButton("Add group") { showAddGroup = true } }
            item {
                SourceActionRow(Icons.Outlined.Download, "Import from OPML") {
                    importLauncher.launch(arrayOf("*/*"))
                }
            }
            item {
                SourceActionRow(Icons.Outlined.Upload, "Export to OPML") {
                    exportLauncher.launch(OpmlManager.exportFileName())
                }
            }
            item { HorizontalDivider(color = nd.border, thickness = 1.dp) }
            if (feedGroups.isEmpty()) {
                items(feeds, key = { "feed-${it.id}" }) { feed ->
                    FeedRow(
                        feed = feed,
                        onEdit = { editFeedId = feed.id },
                        onToggle = { vm.toggleFeed(feed) },
                        onToggleMute = { vm.toggleFeedMuted(feed) },
                        onDelete = { confirmDeleteFeedId = feed.id },
                    )
                }
            } else {
                feedGroups.forEach { group ->
                    item(key = "group-${group.id}") {
                        GroupHeader(
                            group = group,
                            onRename = { editGroupId = group.id },
                            onDelete = { confirmDeleteGroupId = group.id },
                        )
                    }
                    items(
                        feeds.filter { it.id in (feedIdsByGroup[group.id] ?: emptySet()) },
                        key = { "feed-${group.id}-${it.id}" },
                    ) { feed ->
                        FeedRow(
                            feed = feed,
                            onEdit = { editFeedId = feed.id },
                            onToggle = { vm.toggleFeed(feed) },
                            onToggleMute = { vm.toggleFeedMuted(feed) },
                            onDelete = { confirmDeleteFeedId = feed.id },
                        )
                    }
                }
                val ungrouped = feeds.filter { it.id !in groupIdsByFeed }
                if (ungrouped.isNotEmpty()) {
                    item(key = "group-none") { SectionLabel("UNGROUPED", nd.muted) }
                    items(ungrouped, key = { "feed-${it.id}" }) { feed ->
                        FeedRow(
                            feed = feed,
                            onEdit = { editFeedId = feed.id },
                            onToggle = { vm.toggleFeed(feed) },
                            onToggleMute = { vm.toggleFeedMuted(feed) },
                            onDelete = { confirmDeleteFeedId = feed.id },
                        )
                    }
                }
            }
        }
    }

    if (showAddFeed) {
        FeedDialog(
            groups = feedGroups,
            onDiscover = { vm.discoverFeeds(it) },
            onDismiss = { showAddFeed = false },
            onConfirm = { name, url, groupIds ->
                vm.addFeed(name, url, groupIds)
                showAddFeed = false
            },
        )
    }
    feeds.firstOrNull { it.id == editFeedId }?.let { feed ->
        FeedDialog(
            title = "Edit source",
            initialName = feed.name,
            initialUrl = feed.url,
            groups = feedGroups,
            initialGroupIds = groupIdsByFeed[feed.id] ?: emptySet(),
            onDismiss = { editFeedId = null },
            onConfirm = { name, url, groupIds ->
                vm.updateFeed(feed, name, url, groupIds)
                editFeedId = null
            },
        )
    }
    if (showAddGroup) {
        FeedGroupDialog(
            title = "Add group",
            onDismiss = { showAddGroup = false },
            onConfirm = { name ->
                vm.addFeedGroup(name)
                showAddGroup = false
            },
        )
    }
    feedGroups.firstOrNull { it.id == editGroupId }?.let { group ->
        FeedGroupDialog(
            title = "Rename group",
            initialName = group.name,
            onDismiss = { editGroupId = null },
            onConfirm = { name ->
                vm.renameFeedGroup(group, name)
                editGroupId = null
            },
        )
    }
    feeds.firstOrNull { it.id == confirmDeleteFeedId }?.let { feed ->
        ConfirmDialog(
            title = "Remove ${feed.name}?",
            body = "Its unsaved articles will be removed as well.",
            onDismiss = { confirmDeleteFeedId = null },
            onConfirm = {
                vm.deleteFeed(feed)
                confirmDeleteFeedId = null
            },
        )
    }
    replaceOffer?.let { (feedId, found) ->
        feeds.firstOrNull { it.id == feedId }?.let { feed ->
            ConfirmDialog(
                title = "Use the new address?",
                body = "${feed.name} will be fetched from ${found.url} instead of ${feed.url}.",
                confirmLabel = "Replace",
                onDismiss = { replaceOffer = null },
                onConfirm = {
                    vm.replaceFeedUrl(feed, found.url, groupIdsByFeed[feed.id] ?: emptySet())
                    replaceOffer = null
                },
            )
        }
    }
    pickOffer?.let { (feedId, choices) ->
        feeds.firstOrNull { it.id == feedId }?.let { feed ->
            FeedPickDialog(
                feed = feed,
                choices = choices,
                onDismiss = { pickOffer = null },
                onPick = { choice ->
                    vm.replaceFeedUrl(feed, choice.url, groupIdsByFeed[feed.id] ?: emptySet())
                    pickOffer = null
                },
            )
        }
    }
    feedGroups.firstOrNull { it.id == confirmDeleteGroupId }?.let { group ->
        ConfirmDialog(
            title = "Delete \"${group.name}\"?",
            body = "Its sources aren't removed, just no longer filed under this group.",
            confirmLabel = "Delete",
            onDismiss = { confirmDeleteGroupId = null },
            onConfirm = {
                vm.deleteFeedGroup(group)
                confirmDeleteGroupId = null
            },
        )
    }
}

/** A tappable row with a leading icon, matching [AddRowButton] but for arbitrary actions. */
@Composable
private fun SourceActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = nd.muted)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = nd.muted)
    }
}

/**
 * A source in the PROBLEMS section: why it fails, how long it has been since it last
 * worked, and a way to look for its feed again. Tapping the row opens the usual edit
 * dialog, for when the new address is already known.
 */
@Composable
private fun ProblemRow(feed: Feed, searching: Boolean, onEdit: () -> Unit, onFindAgain: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(sourceHue(feed.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                feed.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = nd.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                feed.lastError,
                fontSize = 12.sp,
                color = nd.negative,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // lastErrorAt moves with every failed attempt, so on its own it only says the
            // source failed recently. The last good fetch is what says how long it's been broken.
            val worked =
                if (feed.lastFetchedAt == 0L) "Never fetched"
                else "Last worked ${TextUtil.timeAgo(feed.lastFetchedAt)}"
            Text(
                "$worked · failed ${TextUtil.timeAgo(feed.lastErrorAt)}",
                fontSize = 12.sp,
                color = nd.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onFindAgain, enabled = !searching) {
            Text(if (searching) "Searching…" else "Find feed again", fontSize = 13.sp)
        }
    }
}

/** Several feeds turned up on the site: let the user say which one the source should follow. */
@Composable
private fun FeedPickDialog(
    feed: Feed,
    choices: List<FeedDiscovery.DiscoveredFeed>,
    onDismiss: () -> Unit,
    onPick: (FeedDiscovery.DiscoveredFeed) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a feed for ${feed.name}") },
        text = {
            Column {
                choices.forEach { choice ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(choice) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(choice.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                        Text(
                            choice.url,
                            fontSize = 12.sp,
                            color = nd.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = nd.ink) } },
    )
}

@Composable
private fun FeedRow(
    feed: Feed,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onToggleMute: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(sourceHue(feed.color)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    feed.name,
                    Modifier.weight(1f, fill = false),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nd.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (feed.isAtom) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .background(nd.chipBg)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "ATOM",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            color = nd.muted,
                        )
                    }
                }
            }
            Text(
                feed.url,
                fontSize = 12.sp,
                color = nd.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Why the last refresh of this source failed, until one succeeds again.
            if (feed.lastError.isNotBlank()) {
                Text(
                    "${feed.lastError} · ${TextUtil.timeAgo(feed.lastErrorAt)}",
                    fontSize = 12.sp,
                    color = nd.negative,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Muting silences a source's keyword alerts but keeps it fetched and its matches
        // pinned: for a source worth reading but too chatty to buzz the phone.
        IconButton(onClick = onToggleMute) {
            Icon(
                if (feed.muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                contentDescription = if (feed.muted) "Unmute alerts from this source" else "Mute alerts from this source",
                modifier = Modifier.size(20.dp),
                tint = if (feed.muted) nd.ink else nd.faint,
            )
        }
        NdSwitch(checked = feed.enabled, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Remove source",
                modifier = Modifier.size(20.dp),
                tint = nd.faint,
            )
        }
    }
}

@Composable
private fun GroupHeader(group: FeedGroup, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            group.name.uppercase(),
            Modifier.weight(1f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            color = nd.muted,
        )
        IconButton(onClick = onRename, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Rename ${group.name}",
                modifier = Modifier.size(14.dp),
                tint = nd.faint,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete ${group.name}",
                modifier = Modifier.size(14.dp),
                tint = nd.faint,
            )
        }
    }
}
