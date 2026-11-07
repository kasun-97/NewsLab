package com.kasun.newslab.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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

/** Bookmarked articles (kept forever, exempt from pruning). */
@Composable
fun SavedScreen(
    vm: MainViewModel,
    onOpenArticle: (Article) -> Unit,
    listState: LazyListState,
    searchActiveState: MutableState<Boolean>,
    searchQueryState: MutableState<String>,
    searchMatchState: MutableState<Int>,
) {
    val articles by vm.articles.collectAsState()
    val saved = remember(articles) { articles.filter { it.isSaved } }
    val showImages by vm.showImages.collectAsState()
    val listLayout by vm.listLayout.collectAsState()
    var actionsArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    // Drop a dangling id once its article leaves the list — otherwise the sheet
    // could silently reopen later if that id ever re-enters the query window.
    LaunchedEffect(articles) {
        if (actionsArticleId != null && articles.isNotEmpty() && articles.none { it.id == actionsArticleId }) {
            actionsArticleId = null
        }
    }

    // Hoisted to App so search survives the article reader unmounting this screen.
    var searchActive by searchActiveState
    var searchQuery by searchQueryState
    var searchMatch by searchMatchState

    val searchResults = remember(saved, searchActive, searchQuery) {
        val q = searchQuery.trim()
        if (!searchActive || q.isBlank()) {
            emptyList()
        } else {
            saved.filter {
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
            "Saved",
            subtitle = if (saved.size == 1) "1 article" else "${saved.size} articles",
        ) {
            if (saved.isNotEmpty()) {
                HeaderAction(Icons.Outlined.Search, "Search", onClick = { searchActive = true })
            }
        }
        if (searchActive) {
            ListSearchBar(
                query = searchQuery,
                matchCount = searchResults.size,
                currentIndex = safeMatch,
                placeholder = "Search saved",
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
            saved.isEmpty() -> EmptyState(refreshing = false, message = "Long-press any article to save it")
            searchActive && searchQuery.isBlank() -> EmptyState(refreshing = false, message = "Search your saved articles")
            searchActive && searchResults.isEmpty() -> EmptyState(refreshing = false, message = "No matches for \"$searchQuery\"")
            else -> {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    if (searchActive) {
                        itemsIndexed(searchResults, key = { _, article -> article.id }) { index, article ->
                            ArticleListItem(
                                layout = listLayout,
                                article = article,
                                highlighted = index == safeMatch,
                                onOpen = { vm.open(article); onOpenArticle(article) },
                                onLongPress = { actionsArticleId = article.id },
                                onToggleSaved = { vm.toggleSaved(article) },
                                showImage = showImages,
                            )
                        }
                    } else {
                        item { Spacer(Modifier.height(6.dp)) }
                        items(saved, key = { it.id }) { article ->
                            ArticleListItem(
                                layout = listLayout,
                                article = article,
                                onOpen = { vm.open(article); onOpenArticle(article) },
                                onLongPress = { actionsArticleId = article.id },
                                onToggleSaved = { vm.toggleSaved(article) },
                                showImage = showImages,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
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
