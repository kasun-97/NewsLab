package com.kasun.newslab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.data.FeedDiscovery
import com.kasun.newslab.data.FeedGroup
import com.kasun.newslab.data.QuickLinkList
import com.kasun.newslab.ui.theme.nd
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun FeedDialog(
    title: String = "Add RSS source",
    initialName: String = "",
    initialUrl: String = "",
    groups: List<FeedGroup> = emptyList(),
    initialGroupIds: Set<Long> = emptySet(),
    onDiscover: (suspend (String) -> List<FeedDiscovery.DiscoveredFeed>)? = null,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String, groupIds: Set<Long>) -> Unit,
) {
    // Saveable so half-typed input (and the multi-select) survives rotation.
    var name by rememberSaveable { mutableStateOf(initialName) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var groupIds by rememberSaveable(
        stateSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(initialGroupIds) }
    val scope = rememberCoroutineScope()
    var detecting by remember { mutableStateOf(false) }
    var detectStatus by remember { mutableStateOf<String?>(null) }
    var detectChoices by remember { mutableStateOf<List<FeedDiscovery.DiscoveredFeed>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Feed or site URL") },
                    placeholder = { Text("https://example.com") },
                    singleLine = true,
                )
                if (onDiscover != null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                val input = url
                                scope.launch {
                                    detecting = true
                                    detectStatus = null
                                    detectChoices = emptyList()
                                    val found = onDiscover(input)
                                    detecting = false
                                    when {
                                        found.isEmpty() -> detectStatus = "No feed found at that address"
                                        found.size == 1 -> {
                                            url = found[0].url
                                            if (name.isBlank()) name = found[0].name
                                            detectStatus = "Found: ${found[0].name}"
                                        }
                                        else -> {
                                            detectChoices = found
                                            detectStatus = "${found.size} feeds found — pick one"
                                        }
                                    }
                                }
                            },
                            enabled = url.isNotBlank() && !detecting,
                        ) { Text(if (detecting) "Searching…" else "Find feed") }
                        detectStatus?.let {
                            Spacer(Modifier.width(4.dp))
                            Text(it, fontSize = 12.sp, color = nd.muted)
                        }
                    }
                    detectChoices.forEach { choice ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    url = choice.url
                                    if (name.isBlank()) name = choice.name
                                    detectChoices = emptyList()
                                    detectStatus = "Selected: ${choice.name}"
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Column {
                                Text(choice.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                                Text(choice.url, fontSize = 11.sp, color = nd.faint, maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                } else {
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                )
                if (groups.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Groups (tap any number)", fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        groups.forEach { group ->
                            SelectChip(group.name, group.id in groupIds) {
                                groupIds = if (group.id in groupIds) groupIds - group.id else groupIds + group.id
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, groupIds) }, enabled = url.isNotBlank()) {
                Text(if (initialUrl.isEmpty()) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add/edit dialog for a quick link, including which list it files under. */
@Composable
fun QuickLinkDialog(
    title: String,
    initialTitle: String = "",
    initialUrl: String = "",
    lists: List<QuickLinkList> = emptyList(),
    initialListId: Long = QuickLinkList.DEFAULT_ID,
    onFetchTitle: (suspend (String) -> String?)? = null,
    onDismiss: () -> Unit,
    onConfirm: (title: String, url: String, listId: Long) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialTitle) }
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var listId by rememberSaveable { mutableStateOf(initialListId) }
    var fetchingName by remember { mutableStateOf(false) }
    // Seeded with the initial URL so opening the edit dialog doesn't refetch a link
    // that already has a name.
    var lastLookedUp by rememberSaveable { mutableStateOf(initialUrl.trim()) }

    // Once a URL has been typed/pasted and settled, name the link from the page's own
    // title. Only fills a blank name, so anything typed by hand is never overwritten.
    if (onFetchTitle != null) {
        LaunchedEffect(url) {
            val candidate = url.trim()
            if (candidate.length < 6 || !candidate.contains(".") || candidate == lastLookedUp) {
                return@LaunchedEffect
            }
            delay(700) // cancelled and restarted by the next keystroke
            fetchingName = true
            val fetched = runCatching { onFetchTitle(candidate) }.getOrNull()
            fetchingName = false
            lastLookedUp = candidate
            if (!fetched.isNullOrBlank() && name.isBlank()) name = fetched
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Link URL") },
                    placeholder = { Text("https://example.com/page") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (fetchingName) "Name (looking up…)" else "Name (optional)") },
                    singleLine = true,
                )
                // Hidden when there's only the one list — a picker with a single choice
                // is just noise.
                if (lists.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    Text("List", fontSize = 12.sp, color = nd.faint)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        lists.forEach { list ->
                            SelectChip(list.name, list.id == listId) { listId = list.id }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url, listId) }, enabled = url.isNotBlank()) {
                Text(if (initialUrl.isEmpty()) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FeedGroupDialog(
    title: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                placeholder = { Text("Markets") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(if (initialName.isEmpty()) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CategoryDialog(
    title: String,
    initialName: String = "",
    initialKeywords: String = "",
    onDismiss: () -> Unit,
    onConfirm: (name: String, keywords: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var keywords by rememberSaveable { mutableStateOf(initialKeywords) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category name") },
                    placeholder = { Text("Economy") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("Match terms (comma-separated)") },
                    placeholder = { Text("inflation, gdp, imf, central bank") },
                    minLines = 2,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "An article joins this category when its title or summary contains any of these terms.",
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, keywords) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RuleDialog(
    title: String = "Add keyword rule",
    initialLabel: String = "",
    initialQuery: String = "",
    initialNotify: Boolean = true,
    onDismiss: () -> Unit,
    onConfirm: (label: String, query: String, notify: Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    var notify by rememberSaveable { mutableStateOf(initialNotify) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Keywords") },
                    placeholder = { Text("fuel price  —  or  \"rate cut\"") },
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Fires when ALL words appear in an article, in any order. " +
                        "Wrap words in \"quotes\" to require the exact phrase. " +
                        "Put - in front of a word or phrase to skip articles that " +
                        "mention it: -imf, -\"world cup\".",
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify", fontWeight = FontWeight.SemiBold)
                        Text(
                            "When off, matches are still pinned and listed in Alerts, without a notification.",
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    NdSwitch(checked = notify, onCheckedChange = { notify = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label, query, notify) }, enabled = query.isNotBlank()) {
                Text(if (initialQuery.isEmpty()) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String = "Remove",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        // Destruction is the one thing that gets a filled red button — rare enough
        // to mean it.
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = nd.negative,
                    contentColor = nd.paper,
                ),
            ) { Text(confirmLabel, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = nd.ink) }
        },
    )
}

/** Create/rename dialog for a quick-link list. */
@Composable
fun QuickLinkListDialog(
    title: String,
    initialName: String = "",
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("List name") },
                placeholder = { Text("Statistics") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(if (initialName.isEmpty()) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
