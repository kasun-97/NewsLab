package com.kasun.newslab.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.Article
import com.kasun.newslab.ui.theme.nd
import kotlinx.coroutines.flow.drop

@Composable
fun App(
    vm: MainViewModel,
    openArticleId: String? = null,
    onOpenArticleConsumed: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf("inbox") }
    var selectedArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var settingsSubScreen by rememberSaveable { mutableStateOf<String?>(null) }
    val inboxListState = rememberLazyListState()
    // One list state per filter page of the inbox pager. Hoisted for the same reason as
    // the states around it — opening an article unmounts the inbox, and anything
    // remembered inside it comes back scrolled to the top — and saved as well, since
    // the pages held their positions across a rotation while they owned their states.
    val inboxPageListStates = rememberSaveable(
        saver = listSaver(
            save = { states ->
                states.flatMap { (filter, state) ->
                    listOf(filter, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                }
            },
            restore = { saved ->
                val states = mutableMapOf<String, LazyListState>()
                saved.chunked(3).forEach { (filter, index, offset) ->
                    states[filter as String] = LazyListState(index as Int, offset as Int)
                }
                states
            },
        )
    ) { mutableMapOf<String, LazyListState>() }
    val savedListState = rememberLazyListState()
    val alertsListState = rememberLazyListState()
    val linksListState = rememberLazyListState()
    val inboxSearchActiveState = rememberSaveable { mutableStateOf(false) }
    val inboxSearchQueryState = rememberSaveable { mutableStateOf("") }
    val inboxSearchMatchState = rememberSaveable { mutableStateOf(0) }
    val savedSearchActiveState = rememberSaveable { mutableStateOf(false) }
    val savedSearchQueryState = rememberSaveable { mutableStateOf("") }
    val savedSearchMatchState = rememberSaveable { mutableStateOf(0) }
    val alertsSearchActiveState = rememberSaveable { mutableStateOf(false) }
    val alertsSearchQueryState = rememberSaveable { mutableStateOf("") }
    val alertsSearchMatchState = rememberSaveable { mutableStateOf(0) }
    val sourcesListState = rememberLazyListState()
    val categoriesListState = rememberLazyListState()
    val keywordRulesListState = rememberLazyListState()
    val statisticsListState = rememberLazyListState()
    val settingsScrollState = rememberScrollState()
    val inboxFilterState = rememberSaveable { mutableStateOf("all") }
    val inboxSelectedSourceIdsState = rememberSaveable(
        stateSaver = listSaver<Set<Long>, Long>(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(emptySet<Long>()) }
    val inboxSelectedKeywordState = rememberSaveable { mutableStateOf<String?>(null) }
    val alertsSelectedSourceIdState = rememberSaveable { mutableStateOf<Long?>(null) }
    val alertsSelectedKeywordState = rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val onboardingComplete by vm.onboardingComplete.collectAsState()
    val linksEnabled by vm.linksEnabled.collectAsState()
    val articles by vm.articles.collectAsState()
    val feeds by vm.feeds.collectAsState()
    val categories by vm.categories.collectAsState()
    val selectedArticle = selectedArticleId?.let { id -> articles.firstOrNull { it.id == id } }
    val onOpenArticle: (Article) -> Unit = { selectedArticleId = it.id }
    val context = LocalContext.current
    var lastBackPressAt by remember { mutableStateOf(0L) }
    val inboxFilter by inboxFilterState
    val inboxSelectedSourceIds by inboxSelectedSourceIdsState
    val inboxSelectedKeyword by inboxSelectedKeywordState
    val alertsSelectedSourceId by alertsSelectedSourceIdState
    val alertsSelectedKeyword by alertsSelectedKeywordState
    val inboxSearchActive by inboxSearchActiveState

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    // Started here rather than from the ViewModel's constructor, so the opening fetch
    // begins after the first frame is on screen instead of competing with it.
    LaunchedEffect(Unit) { vm.start() }

    // Declared here (not inside InboxScreen/AlertsScreen) so it only reacts to real selection
    // changes, not to those screens remounting when the article reader closes. drop(1) skips
    // the value snapshotFlow emits on start, so a rotation/process-death restore keeps the
    // restored scroll position instead of jumping back to the top.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(inboxFilter, inboxSelectedSourceIds, inboxSelectedKeyword) }
            .drop(1)
            .collect { inboxListState.scrollToItem(0) }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { alertsSelectedSourceId to alertsSelectedKeyword }
            .drop(1)
            .collect { alertsListState.scrollToItem(0) }
    }

    // A deleted feed must not linger as an (invisible) source filter, and a deleted
    // category must not stay as the active chip filter — both would silently empty
    // the timeline with no visible control left to clear them. drop(1) skips the
    // flows' synthetic empty initial value so restored state isn't wiped before the
    // DB loads, while still pruning when the user deletes the last feed/category.
    LaunchedEffect(Unit) {
        snapshotFlow { feeds }.drop(1).collect { list ->
            val valid = list.mapTo(HashSet()) { it.id }
            val selected = inboxSelectedSourceIdsState.value
            if (!valid.containsAll(selected)) {
                inboxSelectedSourceIdsState.value = selected.filterTo(mutableSetOf()) { it in valid }
            }
            val alertsSelected = alertsSelectedSourceIdState.value
            if (alertsSelected != null && alertsSelected !in valid) {
                alertsSelectedSourceIdState.value = null
            }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { categories }.drop(1).collect { list ->
            val current = inboxFilterState.value
            if (current.startsWith("cat:") && list.none { "cat:${it.id}" == current }) {
                inboxFilterState.value = "all"
            }
        }
    }

    // Turning a section off must not leave its (now hidden) tab selected.
    LaunchedEffect(linksEnabled) {
        if (!linksEnabled && tab == "links") tab = "inbox"
    }

    // Deep-link from a keyword-alert notification tap: jump straight to that article
    // (marking it read, which also clears the notification) rather than launching a
    // browser. On a cold start Room hasn't emitted yet, so keep the id un-consumed
    // until the list actually loads — the `articles` key re-runs this on each emission.
    LaunchedEffect(openArticleId, articles) {
        if (openArticleId == null) return@LaunchedEffect
        val article = articles.firstOrNull { it.id == openArticleId }
        when {
            article != null -> {
                vm.open(article)
                selectedArticleId = article.id
                onOpenArticleConsumed()
            }
            articles.isNotEmpty() -> onOpenArticleConsumed() // loaded, but the article is gone (pruned/deleted)
        }
    }

    BackHandler {
        when {
            selectedArticle != null -> selectedArticleId = null
            // Search survives this screen unmounting, so only treat back as
            // "close search" while the search UI is actually visible.
            inboxSearchActive && tab == "inbox" && !showSettings && settingsSubScreen == null -> {
                inboxSearchActiveState.value = false
                inboxSearchQueryState.value = ""
            }
            settingsSubScreen != null -> settingsSubScreen = null
            showSettings -> showSettings = false
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackPressAt < 2_000) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPressAt = now
                    Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onMainTab = selectedArticle == null && settingsSubScreen == null && !showSettings

    Box(Modifier.fillMaxSize().background(nd.paper)) {
    if (onboardingComplete == true) {
    Scaffold(
        containerColor = nd.paper,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (onMainTab) {
                BottomBar(
                    current = tab,
                    linksEnabled = linksEnabled,
                    alertsBadge = remember(articles) {
                        articles.any { it.matchedRules.isNotEmpty() && !it.isRead }
                    },
                    onSelect = { selected ->
                        tab = selected
                        selectedArticleId = null
                        showSettings = false
                        settingsSubScreen = null
                    },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when {
                selectedArticle != null -> ArticleDetailScreen(
                    vm = vm,
                    article = selectedArticle,
                    onBack = { selectedArticleId = null },
                )
                settingsSubScreen == "sources" -> SourcesScreen(
                    vm, onBack = { settingsSubScreen = null }, listState = sourcesListState,
                )
                settingsSubScreen == "categories" -> CategoriesScreen(
                    vm, onBack = { settingsSubScreen = null }, listState = categoriesListState,
                )
                settingsSubScreen == "rules" -> KeywordRulesScreen(
                    vm, onBack = { settingsSubScreen = null }, listState = keywordRulesListState,
                )
                settingsSubScreen == "statistics" -> StatisticsScreen(
                    vm, onBack = { settingsSubScreen = null }, listState = statisticsListState,
                )
                showSettings -> SettingsScreen(
                    vm = vm,
                    onBack = { showSettings = false },
                    onOpenSources = { settingsSubScreen = "sources" },
                    onOpenCategories = { settingsSubScreen = "categories" },
                    onOpenKeywordRules = { settingsSubScreen = "rules" },
                    onOpenStatistics = { settingsSubScreen = "statistics" },
                    scrollState = settingsScrollState,
                )
                else -> {
                    when (tab) {
                        "inbox" -> InboxScreen(
                            vm, onOpenArticle,
                            onOpenSettings = { showSettings = true },
                            onOpenSourcesSettings = { settingsSubScreen = "sources" },
                            onOpenAlerts = { tab = "alerts" },
                            listState = inboxListState,
                            pageListStates = inboxPageListStates,
                            filterState = inboxFilterState,
                            selectedSourceIdsState = inboxSelectedSourceIdsState,
                            selectedKeywordState = inboxSelectedKeywordState,
                            searchActiveState = inboxSearchActiveState,
                            searchQueryState = inboxSearchQueryState,
                            searchMatchState = inboxSearchMatchState,
                        )
                        "saved" -> SavedScreen(
                            vm, onOpenArticle,
                            listState = savedListState,
                            searchActiveState = savedSearchActiveState,
                            searchQueryState = savedSearchQueryState,
                            searchMatchState = savedSearchMatchState,
                        )
                        "alerts" -> AlertsScreen(
                            vm, onOpenArticle,
                            listState = alertsListState,
                            selectedSourceIdState = alertsSelectedSourceIdState,
                            selectedKeywordState = alertsSelectedKeywordState,
                            searchActiveState = alertsSearchActiveState,
                            searchQueryState = alertsSearchQueryState,
                            searchMatchState = alertsSearchMatchState,
                        )
                        "links" -> LinksScreen(vm, listState = linksListState)
                    }
                }
            }
        }
    }
    }

        if (onboardingComplete == false) {
            OnboardingScreen(onDone = { feeds, links -> vm.completeOnboarding(feeds, links) })
        }
    }
}

private data class NavItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

@Composable
private fun BottomBar(
    current: String,
    linksEnabled: Boolean,
    alertsBadge: Boolean,
    onSelect: (String) -> Unit,
) {
    val items = listOfNotNull(
        NavItem("inbox", "Inbox", Icons.Outlined.Inbox, Icons.Filled.Inbox),
        NavItem("saved", "Saved", Icons.Outlined.Bookmark, Icons.Filled.Bookmark),
        NavItem("alerts", "Alerts", Icons.Outlined.Notifications, Icons.Filled.Notifications),
        if (linksEnabled) NavItem("links", "Links", Icons.Outlined.Link, Icons.Filled.Link) else null,
    )
    Column(Modifier.background(nd.paper)) {
        HorizontalDivider(color = nd.border, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            items.forEach { item ->
                val selected = item.id == current
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item.id) },
                ) {
                    // The selected item's 2dp amber top rule — the reader's own
                    // position in the app, so it gets the brand stroke.
                    if (selected) {
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(nd.brandStroke)
                        )
                    }
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box {
                            Icon(
                                if (selected) item.selectedIcon else item.icon,
                                contentDescription = item.label,
                                modifier = Modifier.size(21.dp),
                                tint = if (selected) nd.ink else nd.faint,
                            )
                            // A 6dp amber square, not a red dot: a waiting keyword match
                            // is the reader's own rule firing, not an error.
                            if (item.id == "alerts" && alertsBadge) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 5.dp, y = (-2).dp)
                                        .size(6.dp)
                                        .background(nd.brandStroke)
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            item.label,
                            fontSize = 10.5.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) nd.ink else nd.faint,
                        )
                    }
                }
            }
        }
    }
}

/** Opens an article in a Chrome Custom Tab, falling back to any browser. */
fun openLink(context: Context, url: String) {
    // Links come verbatim from feeds; only web URLs may reach ACTION_VIEW —
    // never tel:/file:/intent:/app-scheme URIs a malicious feed could plant.
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build()
            .launchUrl(context, Uri.parse(url))
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // No browser available; ignore.
        }
    }
}
