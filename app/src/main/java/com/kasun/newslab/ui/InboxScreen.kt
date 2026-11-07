package com.kasun.newslab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.DensitySmall
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewHeadline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.Article
import com.kasun.newslab.data.Category
import com.kasun.newslab.data.Classifier
import com.kasun.newslab.data.Feed
import com.kasun.newslab.data.FeedArticleCounts
import com.kasun.newslab.data.FeedGroup
import com.kasun.newslab.data.FeedGroupMember
import com.kasun.newslab.data.ListLayout
import com.kasun.newslab.data.StoryClusterer
import com.kasun.newslab.data.TextUtil
import com.kasun.newslab.ui.theme.nd
import com.kasun.newslab.ui.theme.sourceHue
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How long a keyword match stays pinned in the KEYWORD ALERTS section. */
private const val ALERT_PIN_WINDOW_MS = 24 * 3_600_000L
private const val MAX_PINNED_ALERTS = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    vm: MainViewModel,
    onOpenArticle: (Article) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSourcesSettings: () -> Unit,
    onOpenAlerts: () -> Unit,
    listState: LazyListState,
    pageListStates: MutableMap<String, LazyListState>,
    filterState: MutableState<String>,
    selectedSourceIdsState: MutableState<Set<Long>>,
    selectedKeywordState: MutableState<String?>,
    searchActiveState: MutableState<Boolean>,
    searchQueryState: MutableState<String>,
    searchMatchState: MutableState<Int>,
) {
    val articles by vm.articles.collectAsState()
    val categories by vm.categories.collectAsState()
    val feeds by vm.feeds.collectAsState()
    val feedGroups by vm.feedGroups.collectAsState()
    val feedGroupMembers by vm.feedGroupMembers.collectAsState()
    val feedArticleCounts by vm.feedArticleCounts.collectAsState()
    val unread by vm.unread.collectAsState()
    val hideRead by vm.hideReadArticles.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val listLayout by vm.listLayout.collectAsState()
    val groupSimilar by vm.groupSimilarStories.collectAsState()
    // Hoisted to App so the selection survives the article reader unmounting this screen.
    var filter by filterState
    var selectedSourceIds by selectedSourceIdsState
    var selectedKeyword by selectedKeywordState
    var searchActive by searchActiveState
    var searchQuery by searchQueryState
    var searchMatch by searchMatchState
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAddFeed by rememberSaveable { mutableStateOf(false) }
    // Saveable id (not the Article object) so the open sheet survives rotation.
    var actionsArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    // Drop a dangling id once its article leaves the list — otherwise the sheet
    // could silently reopen later if that id ever re-enters the query window.
    LaunchedEffect(articles) {
        if (actionsArticleId != null && articles.isNotEmpty() && articles.none { it.id == actionsArticleId }) {
            actionsArticleId = null
        }
    }
    // Set when the refresh button is tapped; the list jumps to the top once new
    // articles land (a pull-to-refresh starts at the top already, so it doesn't set it).
    var pendingScrollTop by remember { mutableStateOf(false) }
    LaunchedEffect(articles) {
        if (pendingScrollTop) {
            // Each filter keeps its own scroll, so this targets the page on screen.
            (pageListStates[filter] ?: listState).scrollToItem(0)
            pendingScrollTop = false
        }
    }
    val filteringBySelection = selectedSourceIds.isNotEmpty() || selectedKeyword != null
    // The chip row's order, so a swipe can step along exactly what's on screen.
    val filterOrder = remember(categories) {
        buildList {
            add("all")
            categories.forEach { add("cat:${it.id}") }
            add("saved")
        }
    }
    // How far a rightward pull the first page can't use has to travel to open the drawer.
    val drawerPull = with(LocalDensity.current) { 48.dp.toPx() }
    // Same idea for the header and chip row, which sit above the pager and so never
    // hand it a drag of their own.
    val headerSwipe = with(LocalDensity.current) { 32.dp.toPx() }
    val pagerState = rememberPagerState(
        initialPage = filterOrder.indexOf(filter).coerceAtLeast(0),
    ) { filterOrder.size }
    // The pager only commits a page past half the screen by default, which makes a
    // flick between filters feel heavy. A fifth is enough to mean it.
    val pagerFling = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.2f,
    )

    // Settling on a page is what sets the filter, so the chip row tracks the swipe.
    LaunchedEffect(pagerState, filterOrder) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            filterOrder.getOrNull(page)?.let { if (it != filter) filter = it }
        }
    }
    // ...and tapping a chip animates the pager across to meet it. The guard on
    // currentPage keeps the two from chasing each other.
    LaunchedEffect(filter, filterOrder) {
        val index = filterOrder.indexOf(filter)
        if (index >= 0 && index != pagerState.currentPage) pagerState.animateScrollToPage(index)
    }

    // The pager owns horizontal drags now, so opening the sources drawer becomes an
    // over-scroll: a rightward pull that the first page has no room to use.
    val drawerEdgePull = remember(pagerState, drawerState, scope, drawerPull) {
        object : NestedScrollConnection {
            private var pulled = 0f

            /** Settled on the first page, rather than mid-swipe on the way to it. */
            private val atFirstPage: Boolean
                get() = pagerState.currentPage == 0 &&
                    abs(pagerState.currentPageOffsetFraction) < 0.01f

            // Taken before the pager, not after: there is nothing to the left of the
            // first page, so the pager would spend a rightward drag on an overscroll
            // stretch and leave nothing over. Claiming it here spends it on revealing
            // the sources drawer instead.
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Only from a drag the pager itself owns. Pre-scroll runs outermost
                // first, so it reaches here before the content under the finger gets
                // its turn — and the alert carousel and the multi-source cards are
                // pagers of their own, whose rightward swipes were being spent on the
                // drawer instead of stepping back through the carousel. A scroll
                // session is only open on the pager the finger is actually dragging,
                // which is what tells one from the other.
                if (available.x <= 0f || !pagerState.isScrollInProgress) {
                    pulled = 0f
                    return Offset.Zero
                }
                if (!atFirstPage || drawerState.isOpen) return Offset.Zero
                pulled += available.x
                if (pulled >= drawerPull) {
                    pulled = 0f
                    scope.launch { drawerState.open() }
                }
                return Offset(available.x, 0f)
            }
        }
    }

    // Search looks across every fetched article, ignoring the current filter/hide-read
    // setting entirely — it's meant to find things regardless of the current view.
    // Computed up here so both the search bar (result count / navigation) and the
    // list below can share it.
    //
    // Article bodies are searched too, through the full-text index the ViewModel
    // queries off the main thread; this only hands it the query, and clears it when
    // search closes. Those matches join the headline ones in the list's own order.
    LaunchedEffect(searchActive, searchQuery) {
        vm.setBodySearchQuery(if (searchActive) searchQuery else "")
    }
    val bodyMatchIds by vm.bodySearchIds.collectAsState()
    val searchResults = remember(articles, searchActive, searchQuery, bodyMatchIds) {
        val q = searchQuery.trim()
        if (!searchActive || q.isBlank()) {
            emptyList()
        } else {
            articles.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.summary.contains(q, ignoreCase = true) ||
                    it.feedName.contains(q, ignoreCase = true) ||
                    it.id in bodyMatchIds
            }
        }
    }
    // Keep the focused result valid as the match set changes under it.
    val safeSearchMatch = searchMatch.coerceIn(0, (searchResults.size - 1).coerceAtLeast(0))

    // Step the list to the focused result when the user taps next/previous.
    LaunchedEffect(safeSearchMatch, searchActive, searchResults) {
        if (searchActive && searchResults.isNotEmpty()) {
            listState.animateScrollToItem(safeSearchMatch)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Off while closed: a horizontal drag on the content steps through the filter
        // chips instead, and only a right-swipe already at the first chip opens this.
        // Left on while open so it can still be swiped shut.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerShape = RectangleShape,
                drawerContainerColor = nd.paper,
                drawerTonalElevation = 0.dp,
            ) {
                SourcesDrawer(
                    feeds = feeds,
                    feedGroups = feedGroups,
                    feedGroupMembers = feedGroupMembers,
                    counts = feedArticleCounts,
                    unread = unread,
                    selectedIds = selectedSourceIds,
                    onToggle = { id ->
                        selectedSourceIds =
                            if (id in selectedSourceIds) selectedSourceIds - id else selectedSourceIds + id
                        scope.launch { drawerState.close() }
                    },
                    onSelectGroup = { ids ->
                        selectedSourceIds = selectedSourceIds + ids
                        scope.launch { drawerState.close() }
                    },
                    onAddSource = {
                        scope.launch { drawerState.close() }
                        showAddFeed = true
                    },
                    onManageSources = {
                        scope.launch { drawerState.close() }
                        onOpenSourcesSettings()
                    },
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // Only drags no child took reach here: over the pager it consumes them
                // and this never fires, but over the header and chips it does — so a
                // swipe means the same thing wherever it lands on screen.
                .pointerInput(filterOrder, filteringBySelection, searchActive) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragCancel = { dx = 0f },
                        onDragEnd = {
                            val moved = dx
                            dx = 0f
                            val index = filterOrder.indexOf(filter)
                            when {
                                searchActive || abs(moved) < headerSwipe -> Unit
                                moved > 0 && (filteringBySelection || index <= 0) ->
                                    scope.launch { drawerState.open() }
                                moved > 0 -> scope.launch { pagerState.animateScrollToPage(index - 1) }
                                filteringBySelection -> Unit
                                index in 0 until filterOrder.lastIndex ->
                                    scope.launch { pagerState.animateScrollToPage(index + 1) }
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        dx += amount
                    }
                }
        ) {
            InboxHeader(
                unread = unread,
                refreshing = vm.refreshing,
                allRead = unread == 0,
                layout = listLayout,
                groupSimilar = groupSimilar,
                onRefresh = { pendingScrollTop = true; vm.refresh() },
                onMarkAllRead = vm::markAllRead,
                onMarkAllUnread = vm::markAllUnread,
                onSetLayout = vm::setListLayout,
                onToggleGroupSimilar = { vm.setGroupSimilarStories(!groupSimilar) },
                onOpenSettings = onOpenSettings,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onOpenSearch = { searchActive = true },
            )
            if (searchActive) {
                ListSearchBar(
                    query = searchQuery,
                    matchCount = searchResults.size,
                    currentIndex = safeSearchMatch,
                    placeholder = "Search articles",
                    onQueryChange = {
                        searchQuery = it
                        searchMatch = 0
                    },
                    onPrev = {
                        if (searchResults.isNotEmpty()) {
                            searchMatch = (safeSearchMatch - 1 + searchResults.size) % searchResults.size
                        }
                    },
                    onNext = {
                        if (searchResults.isNotEmpty()) {
                            searchMatch = (safeSearchMatch + 1) % searchResults.size
                        }
                    },
                    onClose = {
                        searchActive = false
                        searchQuery = ""
                        searchMatch = 0
                    },
                )
            } else if (filteringBySelection) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val chipListState = rememberLazyListState()
                    LazyRow(
                        Modifier.weight(1f),
                        state = chipListState,
                        flingBehavior = rememberSnapFlingBehavior(chipListState),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(feeds.filter { it.id in selectedSourceIds }, key = { it.id }) { feed ->
                            SourceChip(feed.name, feed.color) { selectedSourceIds = selectedSourceIds - feed.id }
                        }
                        selectedKeyword?.let { label ->
                            item(key = "keyword") { KeywordChip(label) { selectedKeyword = null } }
                        }
                    }
                    Text(
                        "Clear",
                        Modifier
                            .clickable {
                                selectedSourceIds = emptySet()
                                selectedKeyword = null
                            }
                            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = nd.muted,
                    )
                }
            } else {
                FilterChips(filter = filter, categories = categories, unread = unread, onSelect = { filter = it })
            }
            HorizontalDivider(color = nd.border, thickness = 1.dp)

            // Remembered against real inputs — this would otherwise re-filter and
            // re-group up to 500 articles on every recomposition, including every
            // frame of a swipe. Read articles can be hidden; saved ones are exempt.
            val visibleArticles = remember(articles, hideRead) {
                if (hideRead) articles.filter { !it.isRead || it.isSaved } else articles
            }
            // Pinned matches belong to the All page only, so they're computed once here
            // rather than per page. The cutoff is sampled per input change, not per
            // recomposition, or two recompositions could disagree at the 24h boundary.
            val alertMatches = remember(visibleArticles) {
                val now = System.currentTimeMillis()
                visibleArticles.filter {
                    it.matchedRules.isNotEmpty() && now - it.publishedAt < ALERT_PIN_WINDOW_MS
                }
            }
            val alerts = remember(alertMatches) { alertMatches.take(MAX_PINNED_ALERTS) }
            val alertIds = remember(alerts) { alerts.mapTo(HashSet()) { it.id } }
            val alertCountLabel =
                if (alertMatches.size > MAX_PINNED_ALERTS) "$MAX_PINNED_ALERTS+" else "${alertMatches.size}"

            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    // Search is a flat list across every article, so it sits outside the
                    // pager: its results don't belong to any one filter.
                    searchActive -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        if (searchResults.isEmpty()) {
                            item {
                                Box(Modifier.fillParentMaxSize()) {
                                    EmptyState(
                                        refreshing = vm.refreshing,
                                        message =
                                            if (searchQuery.isBlank()) "Search all your fetched articles"
                                            else "No matches for \"$searchQuery\"",
                                    )
                                }
                            }
                        }
                        // No day headers, so the focused result's index maps straight to
                        // a scroll position for next/previous.
                        itemsIndexed(searchResults, key = { _, article -> article.id }) { index, article ->
                            ArticleListItem(
                                layout = listLayout,
                                article = article,
                                highlighted = index == safeSearchMatch,
                                onOpen = { vm.open(article); onOpenArticle(article) },
                                onLongPress = { actionsArticleId = article.id },
                                onToggleSaved = { vm.toggleSaved(article) },
                                showImage = showImages,
                                onSourceClick = { selectedSourceIds = selectedSourceIds + article.feedId },
                                onKeywordClick = { selectedKeyword = article.matchedLabels },
                            )
                        }
                        item(key = "footer") { Spacer(Modifier.height(24.dp)) }
                    }
                    // A source/keyword selection replaces the chips entirely, so it also
                    // sits outside the pager — there are no filters to swipe between.
                    filteringBySelection -> {
                        val selection = remember(visibleArticles, selectedSourceIds, selectedKeyword) {
                            // An article can match several rules and its chip label is the
                            // joined string, so match per individual label rather than
                            // exact-string, which would hide articles sharing a keyword.
                            val labels = selectedKeyword?.let { Classifier.splitLabels(it).toSet() }
                            visibleArticles
                                .filter { selectedSourceIds.isEmpty() || it.feedId in selectedSourceIds }
                                .filter {
                                    labels == null ||
                                        Classifier.splitLabels(it.matchedLabels).any { label -> label in labels }
                                }
                        }
                        TimelineList(
                            vm = vm,
                            listState = listState,
                            articles = selection,
                            alerts = emptyList(),
                            alertCountLabel = "",
                            cluster = groupSimilar,
                            listLayout = listLayout,
                            showImages = showImages,
                            emptyMessage = "Nothing here yet",
                            onOpenArticle = onOpenArticle,
                            onLongPress = { actionsArticleId = it },
                            onSelectSource = { selectedSourceIds = selectedSourceIds + it },
                            onSelectKeyword = { selectedKeyword = it },
                            onOpenAlerts = onOpenAlerts,
                        )
                    }
                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().nestedScroll(drawerEdgePull),
                        flingBehavior = pagerFling,
                    ) { page ->
                        val pageFilter = filterOrder.getOrElse(page) { "all" }
                        key(pageFilter) {
                            // One state per filter, kept in a map App owns: a state
                            // remembered here would be thrown away when the article
                            // reader unmounts this screen, so every page came back at
                            // the top.
                            val pageListState = pageListStates.getOrPut(pageFilter) { LazyListState() }
                            val pageArticles = remember(visibleArticles, pageFilter, alertIds) {
                                when {
                                    // Pinned matches are up in the carousel, so they're
                                    // dropped here rather than shown twice.
                                    pageFilter == "all" -> visibleArticles.filter { it.id !in alertIds }
                                    pageFilter == "saved" -> visibleArticles.filter { it.isSaved }
                                    pageFilter.startsWith("cat:") -> {
                                        val needle = "," + pageFilter.removePrefix("cat:") + ","
                                        visibleArticles.filter { it.categoryIds.contains(needle) }
                                    }
                                    else -> visibleArticles
                                }
                            }
                            TimelineList(
                                vm = vm,
                                listState = pageListState,
                                articles = pageArticles,
                                alerts = if (pageFilter == "all") alerts else emptyList(),
                                alertCountLabel = alertCountLabel,
                                // A saved item is a specific article, not a story to merge.
                                cluster = groupSimilar && pageFilter != "saved",
                                listLayout = listLayout,
                                showImages = showImages,
                                emptyMessage = when {
                                    articles.isEmpty() -> "Pull down to fetch news"
                                    pageFilter == "saved" -> "Long-press any article to save it"
                                    else -> "Nothing here yet"
                                },
                                onOpenArticle = onOpenArticle,
                                onLongPress = { actionsArticleId = it },
                                onSelectSource = { selectedSourceIds = selectedSourceIds + it },
                                onSelectKeyword = { selectedKeyword = it },
                                onOpenAlerts = onOpenAlerts,
                            )
                        }
                    }
                }
            }
        }
    }

    articles.firstOrNull { it.id == actionsArticleId }?.let { article ->
        ArticleActionsSheet(
            article = article,
            onDismiss = { actionsArticleId = null },
            onToggleSaved = { vm.toggleSaved(article) },
            onToggleRead = { vm.toggleRead(article) },
            onDelete = { vm.deleteArticle(article) },
        )
    }

    // Same dialog the Sources settings screen uses, so a source added from the drawer
    // gets the same feed discovery and group picker.
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
}

@Composable
private fun InboxHeader(
    unread: Int,
    refreshing: Boolean,
    allRead: Boolean,
    layout: ListLayout,
    groupSimilar: Boolean,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
    onMarkAllUnread: () -> Unit,
    onSetLayout: (ListLayout) -> Unit,
    onToggleGroupSimilar: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    ScreenHeader(
        "Inbox",
        subtitle = if (unread == 0) "All read" else if (unread == 1) "1 unread" else "$unread unread",
        leading = {
            // A plain arrow, hinting at the right-swipe that opens the sources drawer.
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "Sources",
                    modifier = Modifier.size(24.dp),
                    tint = nd.muted,
                )
            }
        },
    ) {
        HeaderAction(Icons.Outlined.Search, "Search", onClick = onOpenSearch)
        HeaderAction(Icons.Outlined.Refresh, "Refresh", onClick = onRefresh, busy = refreshing)
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "More",
                    modifier = Modifier.size(20.dp),
                    tint = nd.ink,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (allRead) "Mark all as unread" else "Mark all as read") },
                    leadingIcon = {
                        Icon(
                            if (allRead) Icons.Outlined.MarkEmailUnread else Icons.Outlined.DoneAll,
                            contentDescription = null,
                            tint = nd.muted,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        if (allRead) onMarkAllUnread() else onMarkAllRead()
                    },
                )
                HorizontalDivider(color = nd.border)
                LayoutMenuItem(
                    label = "Timeline view",
                    icon = Icons.Outlined.ViewHeadline,
                    selected = layout == ListLayout.TIMELINE,
                    onClick = { menuOpen = false; onSetLayout(ListLayout.TIMELINE) },
                )
                LayoutMenuItem(
                    label = "Card view",
                    icon = Icons.Outlined.ViewAgenda,
                    selected = layout == ListLayout.CARD,
                    onClick = { menuOpen = false; onSetLayout(ListLayout.CARD) },
                )
                LayoutMenuItem(
                    label = "Compact view",
                    icon = Icons.Outlined.DensitySmall,
                    selected = layout == ListLayout.COMPACT,
                    onClick = { menuOpen = false; onSetLayout(ListLayout.COMPACT) },
                )
                HorizontalDivider(color = nd.border)
                LayoutMenuItem(
                    label = "Group similar stories",
                    icon = Icons.Outlined.Layers,
                    selected = groupSimilar,
                    onClick = { menuOpen = false; onToggleGroupSimilar() },
                )
                HorizontalDivider(color = nd.border)
                DropdownMenuItem(
                    text = { Text("Settings") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = nd.muted)
                    },
                    onClick = {
                        menuOpen = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}

/** Layout option in the three-dots menu; shows a check on the active layout. */
@Composable
private fun LayoutMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = nd.muted) },
        trailingIcon = {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = nd.ink)
        },
        onClick = onClick,
    )
}

@Composable
private fun FilterChips(
    filter: String,
    categories: List<Category>,
    unread: Int,
    onSelect: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FilterTab("All", filter == "all", count = unread.takeIf { it > 0 }) { onSelect("all") }
        categories.forEach { category ->
            FilterTab(category.name, filter == "cat:${category.id}") {
                onSelect("cat:${category.id}")
            }
        }
        FilterTab("Saved", filter == "saved") { onSelect("saved") }
    }
}

/** Sentinel group id for feeds that don't belong to any real group. */
private const val UNGROUPED_KEY = -1L

/**
 * Drawer listing every source (A–Z), grouped into collapsible sections when the
 * user has organized feeds into groups; tap a source to add/remove it from the
 * selection, or tap a group header to expand/collapse it.
 */
@Composable
private fun SourcesDrawer(
    feeds: List<Feed>,
    feedGroups: List<FeedGroup>,
    feedGroupMembers: List<FeedGroupMember>,
    counts: List<FeedArticleCounts>,
    unread: Int,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onSelectGroup: (Set<Long>) -> Unit,
    onAddSource: () -> Unit,
    onManageSources: () -> Unit,
) {
    val countsByFeed = remember(counts) { counts.associateBy { it.feedId } }
    val feedIdsByGroup = remember(feedGroupMembers) {
        feedGroupMembers.groupBy({ it.groupId }, { it.feedId }).mapValues { it.value.toSet() }
    }
    val groupIdsByFeed = remember(feedGroupMembers) {
        feedGroupMembers.groupBy({ it.feedId }, { it.groupId }).mapValues { it.value.toSet() }
    }
    var collapsedGroups by remember { mutableStateOf(emptySet<Long>()) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Sources",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = nd.ink,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (unread == 1) "1 unread" else "$unread unread",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = nd.muted,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddSource, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = "Add source",
                    modifier = Modifier.size(19.dp),
                    tint = nd.faint,
                )
            }
            IconButton(onClick = onManageSources, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Outlined.DashboardCustomize,
                    contentDescription = "Manage sources",
                    modifier = Modifier.size(17.dp),
                    tint = nd.faint,
                )
            }
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)

        if (feedGroups.isEmpty()) {
            val sortedFeeds = remember(feeds) { feeds.sortedBy { it.name.lowercase() } }
            sortedFeeds.forEach { feed ->
                DrawerSourceRow(feed, countsByFeed[feed.id]?.unread ?: 0, feed.id in selectedIds) {
                    onToggle(feed.id)
                }
            }
        } else {
            feedGroups.forEach { group ->
                val collapsed = group.id in collapsedGroups
                val groupFeeds = remember(feeds, feedIdsByGroup, group.id) {
                    feeds.filter { it.id in (feedIdsByGroup[group.id] ?: emptySet()) }
                        .sortedBy { it.name.lowercase() }
                }
                DrawerGroupHeader(
                    name = group.name,
                    collapsed = collapsed,
                    onSelectGroup = { onSelectGroup(groupFeeds.mapTo(mutableSetOf()) { it.id }) },
                    onToggleCollapse = {
                        collapsedGroups =
                            if (collapsed) collapsedGroups - group.id else collapsedGroups + group.id
                    },
                )
                if (!collapsed) {
                    groupFeeds.forEach { feed ->
                        DrawerSourceRow(feed, countsByFeed[feed.id]?.unread ?: 0, feed.id in selectedIds) {
                            onToggle(feed.id)
                        }
                    }
                }
            }
            val ungrouped = remember(feeds, groupIdsByFeed) {
                feeds.filter { it.id !in groupIdsByFeed }.sortedBy { it.name.lowercase() }
            }
            if (ungrouped.isNotEmpty()) {
                val collapsed = UNGROUPED_KEY in collapsedGroups
                DrawerGroupHeader(
                    name = "Ungrouped",
                    collapsed = collapsed,
                    onSelectGroup = { onSelectGroup(ungrouped.mapTo(mutableSetOf()) { it.id }) },
                    onToggleCollapse = {
                        collapsedGroups =
                            if (collapsed) collapsedGroups - UNGROUPED_KEY else collapsedGroups + UNGROUPED_KEY
                    },
                )
                if (!collapsed) {
                    ungrouped.forEach { feed ->
                        DrawerSourceRow(feed, countsByFeed[feed.id]?.unread ?: 0, feed.id in selectedIds) {
                            onToggle(feed.id)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tappable group section header: tapping the name selects every source in the
 * group, while the arrowhead (a separate, larger tap target) only expands/collapses it.
 */
@Composable
private fun DrawerGroupHeader(
    name: String,
    collapsed: Boolean,
    onSelectGroup: () -> Unit,
    onToggleCollapse: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name.uppercase(),
            Modifier.weight(1f).clickable(onClick = onSelectGroup),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            color = nd.muted,
        )
        IconButton(onClick = onToggleCollapse, modifier = Modifier.size(32.dp)) {
            Icon(
                if (collapsed) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                modifier = Modifier.size(18.dp),
                tint = nd.faint,
            )
        }
    }
}

@Composable
private fun DrawerSourceRow(feed: Feed, unread: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(sourceHue(feed.color)))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                feed.name,
                Modifier.weight(1f, fill = false),
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = nd.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unread > 0) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Filled.Circle,
                    contentDescription = null,
                    modifier = Modifier.size(6.dp),
                    tint = nd.faint,
                )
                Spacer(Modifier.width(4.dp))
                Text("$unread", fontSize = 11.sp, color = nd.faint)
            }
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(16.dp),
                tint = nd.ink,
            )
        }
    }
}

/** Trailing page in the alerts carousel once all pinned matches are shown. */
@Composable
private fun ReadAllAlertsCard(height: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(nd.radiusCard)
    Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(shape)
                .border(1.dp, nd.border, shape)
                .background(nd.card)
                .clickable(onClick = onClick)
                .padding(14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.TrackChanges,
                contentDescription = null,
                modifier = Modifier.size(26.dp),
                tint = nd.tagAccent,
            )
            Spacer(Modifier.height(10.dp))
            Text("Read all alerts", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = nd.ink)
            Spacer(Modifier.height(4.dp))
            Text("Every keyword match, in one place", fontSize = 12.sp, color = nd.muted)
        }
    }
}

/** Short amber bars marking position within the alerts carousel. */
@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                Modifier
                    .padding(horizontal = 2.5.dp)
                    .size(width = 14.dp, height = 2.dp)
                    .background(if (index == currentPage) nd.brandStroke else nd.border)
            )
        }
    }
}

/**
 * The article list itself: an optional keyword-alerts carousel on top, then the
 * articles grouped under day headers. Shared by every pager page and by the
 * source/keyword selection view, so all of them scroll and cluster alike.
 */
@Composable
private fun TimelineList(
    vm: MainViewModel,
    listState: LazyListState,
    articles: List<Article>,
    alerts: List<Article>,
    alertCountLabel: String,
    cluster: Boolean,
    listLayout: ListLayout,
    showImages: Boolean,
    emptyMessage: String,
    onOpenArticle: (Article) -> Unit,
    onLongPress: (String) -> Unit,
    onSelectSource: (Long) -> Unit,
    onSelectKeyword: (String) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    val clusters = remember(articles, cluster) {
        if (cluster) StoryClusterer.cluster(articles)
        else articles.map { com.kasun.newslab.data.StoryCluster(listOf(it)) }
    }
    // Keyed on the current day boundary too: the labels compare against wall-clock
    // "today", so an unchanged list must still regroup after midnight or yesterday's
    // articles keep reading TODAY.
    //
    // Grouped by day-start first and labelled afterwards, so the header text is built
    // once per distinct day (a handful) rather than once per article (hundreds).
    // groupBy returns a LinkedHashMap and mapKeys preserves order, so sections keep
    // their newest-first order.
    val todayStart = TextUtil.startOfToday()
    val dayGroups = remember(clusters, todayStart) {
        clusters.groupBy { TextUtil.dayStartOf(it.representative.publishedAt) }
            .mapKeys { (dayStart, _) -> TextUtil.dayLabel(dayStart, todayStart) }
    }

    if (alerts.isEmpty() && articles.isEmpty()) {
        // A LazyColumn rather than a plain Box, so the pull-to-refresh nested-scroll
        // connection still gets drag deltas when empty — the copy tells you to pull here.
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Box(Modifier.fillParentMaxSize()) {
                    EmptyState(refreshing = vm.refreshing, message = emptyMessage)
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        if (alerts.isNotEmpty()) {
            item(key = "alerts-header") {
                Box(Modifier.background(nd.brandWash)) {
                    UserSectionHeader(
                        "YOUR KEYWORDS · $alertCountLabel NEW",
                        trailing = "See all",
                        onTrailingClick = onOpenAlerts,
                    )
                }
            }
            item(key = "alerts-row") {
                // One extra page beyond the real alerts: a "Read all alerts" card.
                val pageCount = alerts.size + 1
                val pagerState = rememberPagerState { pageCount }
                // The alert cards size themselves to two headline lines and three summary
                // lines; the trailing page copies whatever they measured to, so the
                // carousel can't change height mid-swipe. The derived value only covers
                // the first frame, before a real card has been measured.
                var measured by remember { mutableStateOf(0) }
                val fallback = alertCardHeight()
                val cardHeight =
                    if (measured > 0) with(LocalDensity.current) { measured.toDp() } else fallback
                Column(Modifier.background(nd.brandWash)) {
                    HorizontalPager(state = pagerState) { page ->
                        if (page < alerts.size) {
                            val article = alerts[page]
                            AlertCard(
                                article = article,
                                onOpen = { vm.open(article); onOpenArticle(article) },
                                onLongPress = { onLongPress(article.id) },
                                showImage = showImages,
                                onSourceClick = { onSelectSource(article.feedId) },
                                onKeywordClick = { onSelectKeyword(article.matchedLabels) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { if (it.height > measured) measured = it.height },
                            )
                        } else {
                            ReadAllAlertsCard(height = cardHeight, onClick = onOpenAlerts)
                        }
                    }
                    PageIndicator(pageCount = pageCount, currentPage = pagerState.currentPage)
                    HorizontalDivider(color = nd.border, thickness = 1.dp)
                }
            }
        }
        dayGroups.forEach { (label, groupClusters) ->
            item(key = "header-$label", contentType = "header") {
                DayHeader(label, count = groupClusters.sumOf { it.size })
            }
            // Without a contentType every slot looks interchangeable, so LazyColumn
            // tries to recycle a plain row into a cluster card (which holds a whole
            // nested pager) and throws the work away. Layout is part of the type so
            // timeline/card/compact rows don't cross-recycle either.
            items(
                groupClusters,
                key = { it.representative.id },
                contentType = { if (it.size == 1) listLayout else "cluster" },
            ) { group ->
                if (group.size == 1) {
                    val article = group.representative
                    ArticleListItem(
                        layout = listLayout,
                        article = article,
                        onOpen = { vm.open(article); onOpenArticle(article) },
                        onLongPress = { onLongPress(article.id) },
                        onToggleSaved = { vm.toggleSaved(article) },
                        showImage = showImages,
                        onSourceClick = { onSelectSource(article.feedId) },
                        onKeywordClick = { onSelectKeyword(article.matchedLabels) },
                    )
                } else {
                    StoryClusterCard(
                        cluster = group,
                        showImage = showImages,
                        onOpen = { article -> vm.open(article); onOpenArticle(article) },
                        onLongPress = { article -> onLongPress(article.id) },
                    )
                }
            }
        }
        item(key = "footer") { Spacer(Modifier.height(24.dp)) }
    }
}
