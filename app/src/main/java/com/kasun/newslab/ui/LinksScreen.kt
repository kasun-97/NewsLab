package com.kasun.newslab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.QuickLink
import com.kasun.newslab.data.QuickLinkList
import com.kasun.newslab.ui.theme.nd

/** Vertical travel on the drag handle that moves a section one place. */
private val DRAG_STEP = 64.dp

/**
 * User-kept web links (country hubs, dashboards, anything that isn't a feed), filed
 * into named lists. Each list is a foldable section: tap its header to collapse it,
 * drag the handle to move it up or down. Tapping a link opens it in a browser tab.
 */
@Composable
fun LinksScreen(vm: MainViewModel, listState: LazyListState) {
    val links by vm.quickLinks.collectAsState()
    val lists by vm.quickLinkLists.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val context = LocalContext.current
    val density = LocalDensity.current

    var addToListId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editLinkId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAddList by rememberSaveable { mutableStateOf(false) }
    var renameListId by rememberSaveable { mutableStateOf<Long?>(null) }
    val collapsed = rememberSaveable(
        stateSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<Long>()) }

    // Sections render from this local order so a drag reorders them under the finger;
    // it re-syncs from the database whenever no drag is in flight.
    var order by remember { mutableStateOf<List<Long>>(emptyList()) }
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(lists) {
        if (draggingId == null) order = lists.map { it.id }
    }

    val listsById = remember(lists) { lists.associateBy { it.id } }
    val linksByList = remember(links) { links.groupBy { it.listId } }
    // A list deleted mid-drag, or one not yet in `order`, must still be reachable.
    val ordered = remember(order, listsById) {
        (order.mapNotNull { listsById[it] } + lists.filter { it.id !in order }).distinctBy { it.id }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Links", subtitle = linkCountLabel(links.size, lists.size)) {
            HeaderAction(Icons.Outlined.Add, "Add list", onClick = { showAddList = true })
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(ordered, key = { it.id }) { list ->
                val isDragging = list.id == draggingId
                Column(
                    Modifier
                        // The dragged section floats above its neighbours while it moves.
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                        .background(if (isDragging) nd.chipBg else nd.paper)
                ) {
                    ListHeaderRow(
                        list = list,
                        linkCount = linksByList[list.id].orEmpty().size,
                        expanded = list.id !in collapsed.value,
                        onToggle = {
                            collapsed.value =
                                if (list.id in collapsed.value) collapsed.value - list.id
                                else collapsed.value + list.id
                        },
                        onAddLink = { addToListId = list.id },
                        onRename = { renameListId = list.id },
                        onDelete = { vm.deleteQuickLinkList(list) },
                        onDragStart = {
                            draggingId = list.id
                            dragOffset = 0f
                        },
                        onDrag = { dy ->
                            dragOffset += dy
                            val step = with(density) { DRAG_STEP.toPx() }
                            val from = order.indexOf(list.id)
                            if (from >= 0) {
                                if (dragOffset > step && from < order.lastIndex) {
                                    order = order.toMutableList().apply { add(from + 1, removeAt(from)) }
                                    dragOffset -= step
                                } else if (dragOffset < -step && from > 0) {
                                    order = order.toMutableList().apply { add(from - 1, removeAt(from)) }
                                    dragOffset += step
                                }
                            }
                        },
                        onDragEnd = {
                            draggingId = null
                            dragOffset = 0f
                            vm.reorderQuickLinkLists(order)
                        },
                    )
                    if (list.id !in collapsed.value) {
                        val sectionLinks = linksByList[list.id].orEmpty()
                        if (sectionLinks.isEmpty()) {
                            Text(
                                "No links here yet.",
                                Modifier.padding(start = 14.dp, end = 18.dp, top = 2.dp, bottom = 10.dp),
                                fontSize = 12.5.sp,
                                color = nd.faint,
                            )
                        }
                        sectionLinks.forEach { link ->
                            LinkRow(
                                link = link,
                                showImages = showImages,
                                onOpen = { openLink(context, link.url) },
                                onEdit = { editLinkId = link.id },
                                onDelete = { vm.deleteQuickLink(link) },
                            )
                        }
                    }
                    HorizontalDivider(color = nd.border, thickness = 1.dp)
                }
            }
            item(key = "add-list") { AddRowButton("Add list", onClick = { showAddList = true }) }
            item(key = "footer") { Spacer(Modifier.height(24.dp)) }
        }
    }

    addToListId?.let { targetId ->
        QuickLinkDialog(
            title = "Add link",
            lists = lists,
            initialListId = targetId,
            onFetchTitle = { vm.pageTitle(it) },
            onDismiss = { addToListId = null },
            onConfirm = { name, url, listId ->
                vm.addQuickLink(name, url, listId)
                addToListId = null
            },
        )
    }
    links.firstOrNull { it.id == editLinkId }?.let { link ->
        QuickLinkDialog(
            title = "Edit link",
            initialTitle = link.title,
            initialUrl = link.url,
            lists = lists,
            initialListId = link.listId,
            onFetchTitle = { vm.pageTitle(it) },
            onDismiss = { editLinkId = null },
            onConfirm = { name, url, listId ->
                vm.updateQuickLink(link, name, url, listId)
                editLinkId = null
            },
        )
    }
    if (showAddList) {
        QuickLinkListDialog(
            title = "Add list",
            onDismiss = { showAddList = false },
            onConfirm = { name ->
                vm.addQuickLinkList(name)
                showAddList = false
            },
        )
    }
    lists.firstOrNull { it.id == renameListId }?.let { list ->
        QuickLinkListDialog(
            title = "Rename list",
            initialName = list.name,
            onDismiss = { renameListId = null },
            onConfirm = { name ->
                vm.renameQuickLinkList(list, name)
                renameListId = null
            },
        )
    }
}

private fun linkCountLabel(links: Int, lists: Int): String {
    val l = if (links == 1) "1 link" else "$links links"
    return if (lists <= 1) l else "$l · $lists lists"
}

/** Section header: fold on tap, reorder by dragging the handle, manage via the overflow menu. */
@Composable
private fun ListHeaderRow(
    list: QuickLinkList,
    linkCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddLink: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "Collapse ${list.name}" else "Expand ${list.name}",
            modifier = Modifier.size(20.dp),
            tint = nd.muted,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            list.name,
            Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
            color = nd.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$linkCount", fontSize = 12.sp, color = nd.faint)
        Spacer(Modifier.width(6.dp))
        // Its own gesture detector, so dragging never reaches the row's fold-on-tap.
        Icon(
            Icons.Outlined.DragHandle,
            contentDescription = "Reorder ${list.name}",
            tint = nd.faint,
            modifier = Modifier
                .size(34.dp)
                .padding(7.dp)
                .pointerInput(list.id) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    ) { change, drag ->
                        change.consume()
                        onDrag(drag.y)
                    }
                },
        )
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "More for ${list.name}",
                    modifier = Modifier.size(17.dp),
                    tint = nd.faint,
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = RoundedCornerShape(2.dp),
                containerColor = nd.card,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                DropdownMenuItem(
                    text = { Text("Add link", fontSize = 13.sp, color = nd.ink) },
                    leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, tint = nd.muted) },
                    onClick = { menuOpen = false; onAddLink() },
                )
                DropdownMenuItem(
                    text = { Text("Rename list", fontSize = 13.sp, color = nd.ink) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = nd.muted) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Delete list", fontSize = 13.sp, color = nd.ink) },
                    leadingIcon = {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = nd.muted)
                    },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    link: QuickLink,
    showImages: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The site's initial sits underneath, so it shows through whenever the
        // favicon is unavailable or images are turned off.
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Text(
                link.title.take(1).uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = nd.faint,
            )
            if (showImages) {
                AsyncImage(
                    model = faviconUrl(link.url),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                link.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = nd.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                linkHost(link.url),
                fontSize = 12.sp,
                color = nd.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "Edit ${link.title}",
                modifier = Modifier.size(15.dp),
                tint = nd.faint,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = "Remove ${link.title}",
                modifier = Modifier.size(16.dp),
                tint = nd.faint,
            )
        }
    }
}

private fun linkHost(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

/**
 * Favicon for a link's site. Sites serve their own /favicon.ico as real ICO, which
 * Android can't decode, so this goes through Google's favicon endpoint (PNG) — the
 * one lookup in the app that leaves the device for something other than a feed. It
 * only runs when images are enabled, and a letter tile shows if it fails.
 */
private fun faviconUrl(url: String): String =
    "https://www.google.com/s2/favicons?domain=${linkHost(url)}&sz=64"
