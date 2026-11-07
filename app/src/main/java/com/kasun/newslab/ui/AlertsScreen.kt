package com.kasun.newslab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.Article
import com.kasun.newslab.data.Classifier
import com.kasun.newslab.ui.theme.nd

/** Articles matching a keyword rule (manage the rules themselves in Settings). */
@Composable
fun AlertsScreen(
    vm: MainViewModel,
    onOpenArticle: (Article) -> Unit,
    listState: LazyListState,
    selectedSourceIdState: MutableState<Long?>,
    selectedKeywordState: MutableState<String?>,
    searchActiveState: MutableState<Boolean>,
    searchQueryState: MutableState<String>,
    searchMatchState: MutableState<Int>,
) {
    val articles by vm.articles.collectAsState()
    val feeds by vm.feeds.collectAsState()
    val hideRead by vm.hideReadArticles.collectAsState()
    val showImages by vm.showImages.collectAsState()
    var actionsArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    // Drop a dangling id once its article leaves the list — otherwise the sheet
    // could silently reopen later if that id ever re-enters the query window.
    LaunchedEffect(articles) {
        if (actionsArticleId != null && articles.isNotEmpty() && articles.none { it.id == actionsArticleId }) {
            actionsArticleId = null
        }
    }
    var selectedSourceId by selectedSourceIdState
    var selectedKeyword by selectedKeywordState
    // Hoisted to App so search survives the article reader unmounting this screen.
    var searchActive by searchActiveState
    var searchQuery by searchQueryState
    var searchMatch by searchMatchState

    // Remembered so opening/closing the long-press sheet doesn't re-run the
    // filter+sort; keyword filtering matches per individual rule label (an
    // article matching several rules carries a joined label string).
    val matched = remember(articles, hideRead, selectedSourceId, selectedKeyword) {
        val selectedLabels = selectedKeyword?.let { Classifier.splitLabels(it).toSet() }
        articles
            .filter { it.matchedRules.isNotEmpty() }
            .filter { !hideRead || !it.isRead || it.isSaved }
            .filter { selectedSourceId == null || it.feedId == selectedSourceId }
            .filter {
                selectedLabels == null ||
                    Classifier.splitLabels(it.matchedLabels).any { label -> label in selectedLabels }
            }
            .sortedByDescending { it.publishedAt }
    }

    val searchResults = remember(matched, searchActive, searchQuery) {
        val q = searchQuery.trim()
        if (!searchActive || q.isBlank()) {
            emptyList()
        } else {
            matched.filter {
                it.title.contains(q, ignoreCase = true) ||
                    it.summary.contains(q, ignoreCase = true) ||
                    it.feedName.contains(q, ignoreCase = true)
            }
        }
    }
    val safeMatch = searchMatch.coerceIn(0, (searchResults.size - 1).coerceAtLeast(0))

    LaunchedEffect(safeMatch, searchActive, searchResults) {
        if (searchActive && searchResults.isNotEmpty()) {
            listState.animateScrollToItem(safeMatch)
        }
    }

    fun closeSearch() {
        searchActive = false
        searchQuery = ""
        searchMatch = 0
    }
    BackHandler(enabled = searchActive) { closeSearch() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Alerts",
            subtitle = if (matched.size == 1) "1 keyword match" else "${matched.size} keyword matches",
        ) {
            if (matched.isNotEmpty()) {
                HeaderAction(Icons.Outlined.Search, "Search", onClick = { searchActive = true })
            }
        }
        if (selectedSourceId != null || selectedKeyword != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedSourceId?.let { id ->
                    feeds.firstOrNull { it.id == id }?.let { feed ->
                        SourceChip(feed.name, feed.color) { selectedSourceId = null }
                    }
                }
                selectedKeyword?.let { label ->
                    KeywordChip(label) { selectedKeyword = null }
                }
            }
        }
        if (searchActive) {
            ListSearchBar(
                query = searchQuery,
                matchCount = searchResults.size,
                currentIndex = safeMatch,
                placeholder = "Search alerts",
                onQueryChange = {
                    searchQuery = it
                    searchMatch = 0
                },
                onPrev = {
                    if (searchResults.isNotEmpty()) searchMatch = (safeMatch - 1 + searchResults.size) % searchResults.size
                },
                onNext = {
                    if (searchResults.isNotEmpty()) searchMatch = (safeMatch + 1) % searchResults.size
                },
                onClose = { closeSearch() },
            )
        }

        when {
            matched.isEmpty() -> EmptyState(
                refreshing = false,
                message = if (selectedSourceId != null || selectedKeyword != null) {
                    "No keyword matches for this filter"
                } else {
                    "No keyword matches yet — add a rule in Settings"
                },
            )
            searchActive && searchQuery.isBlank() -> EmptyState(refreshing = false, message = "Search your alerts")
            searchActive && searchResults.isEmpty() -> EmptyState(refreshing = false, message = "No matches for \"$searchQuery\"")
            else -> {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (searchActive) {
                        itemsIndexed(searchResults, key = { _, article -> article.id }) { index, article ->
                            AlertCard(
                                article = article,
                                onOpen = { vm.open(article); onOpenArticle(article) },
                                onLongPress = { actionsArticleId = article.id },
                                showImage = showImages,
                                highlighted = index == safeMatch,
                                onSourceClick = { selectedSourceId = article.feedId },
                                onKeywordClick = { selectedKeyword = article.matchedLabels },
                            )
                        }
                    } else {
                        item(key = "header") {
                            UserSectionHeader("MATCHED ARTICLES · ${matched.size}")
                        }
                        items(matched, key = { it.id }) { article ->
                            AlertCard(
                                article = article,
                                onOpen = { vm.open(article); onOpenArticle(article) },
                                onLongPress = { actionsArticleId = article.id },
                                showImage = showImages,
                                onSourceClick = { selectedSourceId = article.feedId },
                                onKeywordClick = { selectedKeyword = article.matchedLabels },
                            )
                        }
                    }
                    item(key = "footer") { Spacer(Modifier.height(24.dp)) }
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
}
