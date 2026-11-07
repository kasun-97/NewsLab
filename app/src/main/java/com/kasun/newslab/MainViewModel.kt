package com.kasun.newslab

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kasun.newslab.data.AlertTrend
import com.kasun.newslab.data.AppFont
import com.kasun.newslab.data.Article
import com.kasun.newslab.data.ArticleExtractor
import com.kasun.newslab.data.ArticleStats
import com.kasun.newslab.data.Category
import com.kasun.newslab.data.Feed
import com.kasun.newslab.data.FeedDiscovery
import com.kasun.newslab.data.FeedGroup
import com.kasun.newslab.data.FontSize
import com.kasun.newslab.data.ImportResult
import com.kasun.newslab.data.ListLayout
import com.kasun.newslab.data.KeywordRule
import com.kasun.newslab.data.OpmlManager
import com.kasun.newslab.data.PopularFeed
import com.kasun.newslab.data.Palette
import com.kasun.newslab.data.Prefs
import com.kasun.newslab.data.QuickLink
import com.kasun.newslab.data.QuickLinkList
import com.kasun.newslab.data.Repository
import com.kasun.newslab.data.SavedExporter
import com.kasun.newslab.data.DesignStyle
import com.kasun.newslab.data.TextUtil
import com.kasun.newslab.data.ThemeMode
import com.kasun.newslab.work.Notifier
import com.kasun.newslab.work.RefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** A fetch newer than this is close enough that opening the app needn't refetch. */
        private const val STARTUP_REFRESH_AFTER_MS = 5 * 60_000L
    }

    private val repo = Repository(app)
    private val dao = repo.dao
    private val prefs = Prefs.get(app)

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val feeds = dao.feeds().state(emptyList())
    val feedGroups = dao.feedGroups().state(emptyList())
    val feedGroupMembers = dao.feedGroupMembers().state(emptyList())
    val feedArticleCounts = dao.articleCountsByFeed().state(emptyList())
    val quickLinks = dao.quickLinks().state(emptyList())
    val quickLinkLists = dao.quickLinkLists().state(emptyList())
    val categories = dao.categories().state(emptyList())
    val rules = dao.rules().state(emptyList())
    val notificationsEnabled = prefs.notificationsEnabled.state(true)
    val themeMode = prefs.themeMode.state(ThemeMode.SYSTEM)
    val designStyle = prefs.designStyle.state(DesignStyle.WARM_LEDGER)
    val fontFamily = prefs.fontFamily.state(AppFont.SYSTEM)
    val uiFontFamily = prefs.uiFontFamily.state(AppFont.SYSTEM)
    val fontSize = prefs.fontSize.state(FontSize.DEFAULT)
    val refreshIntervalMinutes = prefs.refreshIntervalMinutes.state(Prefs.DEFAULT_REFRESH_MINUTES)
    val hideReadArticles = prefs.hideRead.state(false)
    val showImages = prefs.showImages.state(true)
    val alwaysUsePageImages = prefs.alwaysUsePageImages.state(false)
    val listLayout = prefs.listLayout.state(ListLayout.TIMELINE)
    val groupSimilarStories = prefs.groupSimilarStories.state(false)
    // Tri-state: null until the stored value loads, so the UI renders neither the app
    // nor the intro for that first frame — preventing an inbox flash before the intro.
    val onboardingComplete = prefs.onboardingComplete.map<Boolean, Boolean?> { it }.state(null)
    val linksEnabled = prefs.linksEnabled.state(true)
    val articleStats = dao.articleStats().state(ArticleStats(0, 0, 0, 0))
    val lastRefreshAt = prefs.lastRefreshAt.state(0L)
    val digestNotifications = prefs.digestNotifications.state(false)
    val quietHoursEnabled = prefs.quietHoursEnabled.state(false)
    val quietHoursStartMinutes = prefs.quietHoursStartMinutes.state(Prefs.DEFAULT_QUIET_START_MINUTES)
    val quietHoursEndMinutes = prefs.quietHoursEndMinutes.state(Prefs.DEFAULT_QUIET_END_MINUTES)
    val prefetchAlertBodies = prefs.prefetchAlertBodies.state(true)

    // Articles from disabled sources are hidden, except ones the user saved.
    //
    // flowOn matters here: viewModelScope is Main.immediate, and Room only moves the
    // *query* off-thread — this transform would otherwise filter hundreds of articles
    // on the UI thread every time the table changes, which during a refresh is often.
    private val visibleArticles = combine(dao.articles(), feeds) { all, currentFeeds ->
        val disabledIds = currentFeeds.filterNot { it.enabled }.mapTo(HashSet()) { it.id }
        if (disabledIds.isEmpty()) all else all.filter { it.isSaved || it.feedId !in disabledIds }
    }.flowOn(Dispatchers.Default)

    // unread derives from the hot `articles` StateFlow, not the cold upstream —
    // a second cold subscription would register a second Room observer and run
    // the timeline query twice on every table change.
    val articles = visibleArticles.state(emptyList())
    val unread = articles.map { list -> list.count { !it.isRead } }
        .flowOn(Dispatchers.Default)
        .state(0)

    private val bodySearchQuery = MutableStateFlow("")

    /**
     * Ids of articles whose cached body matches the inbox search, for the inbox to add
     * to its headline matches. Only bodies already extracted (opened or pre-fetched)
     * are in the index, so this can't find text in an article nobody has opened.
     *
     * Debounced so a query runs once typing pauses, not per keystroke, and on IO since
     * it goes straight to the database. Clearing skips the wait: the inbox ignores the
     * set while the query is blank, but a stale set would briefly widen the next search.
     */
    @OptIn(FlowPreview::class)
    val bodySearchIds: StateFlow<Set<String>> = bodySearchQuery
        .debounce { if (it.isBlank()) 0L else 250L }
        .distinctUntilChanged()
        .map { query -> if (query.isBlank()) emptySet() else repo.searchBodies(query) }
        .flowOn(Dispatchers.IO)
        .state(emptySet())

    /** What the inbox search bar holds; blank while search is closed. */
    fun setBodySearchQuery(query: String) {
        bodySearchQuery.value = query.trim()
    }

    var refreshing by mutableStateOf(false)
        private set

    private val _messages = MutableSharedFlow<String>()
    val messages: SharedFlow<String> = _messages

    private var started = false

    /**
     * Kicks off the opening fetch. Deliberately not in `init`: the ViewModel is built
     * during the first composition, so refreshing there put six feed fetches — parsing,
     * HTML cleaning, hashing, classification — against the first frames of the app.
     * The UI calls this once the first frame is up instead.
     *
     * A fetch is skipped when one landed recently, so returning to the app doesn't
     * re-fetch, but an empty database always fetches so a first run isn't left blank.
     */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            val since = System.currentTimeMillis() - prefs.lastRefreshAt.first()
            if (since >= STARTUP_REFRESH_AFTER_MS || articles.value.isEmpty()) refresh()
        }
    }

    // ---- Articles ----

    fun refresh() {
        if (refreshing) return
        viewModelScope.launch {
            refreshing = true
            try {
                _messages.emit(refreshMessage(repo.refresh(notify = false)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A refresh-wide failure (e.g. DB error) degrades to a message,
                // never an app crash.
                _messages.emit("Refresh failed — try again")
            } finally {
                // Without this, one refresh exception would leave the flag stuck
                // true and permanently dead-end the refresh button.
                refreshing = false
            }
        }
    }

    private fun refreshMessage(result: Repository.RefreshResult): String {
        val added = result.added
        val addedText = "$added new article${if (added == 1) "" else "s"}"
        val failed = result.failedFeeds
        return when {
            failed.isEmpty() -> if (added > 0) addedText else "You're up to date"
            else -> {
                val failText =
                    if (failed.size == 1) "couldn't reach ${failed.first().name}: ${failed.first().reason}"
                    else "couldn't reach ${failed.size} sources"
                if (added > 0) "$addedText · $failText"
                else failText.replaceFirstChar { it.uppercase() }
            }
        }
    }

    fun open(article: Article) = viewModelScope.launch {
        dao.markRead(article.id)
        Notifier.cancel(getApplication(), article.id)
    }

    /** Article ids already probed for an og:image this session — pages without one aren't refetched per open. */
    private val imageFetchAttempted = mutableSetOf<String>()

    /** The already-extracted body for an article, or null if it hasn't been fetched yet. */
    suspend fun cachedArticleText(article: Article): String? = repo.cachedArticleText(article.id)

    /** Extracts (and caches) an article's full readable body, for the "Read full article" action. */
    suspend fun loadArticleText(article: Article): ArticleExtractor.Result = repo.loadArticleText(article)

    /**
     * Lazily reads the full-size image off the article's own page. With [highQuality]
     * off that's only a backfill for feeds carrying no image at all; with it on the
     * page is read for every article, since a feed's own image is often a thumbnail.
     */
    fun ensureArticleImage(article: Article, highQuality: Boolean) {
        // The page has already given what it has; there's nothing better to fetch.
        if (article.pageImageUrl.isNotBlank()) return
        if (!highQuality && article.imageUrl.isNotBlank()) return
        if (!imageFetchAttempted.add(article.id)) return
        viewModelScope.launch {
            val imageUrl = repo.fetchArticleImage(article.link) ?: return@launch
            // Column-targeted write: the row may have changed (markRead) since this
            // article snapshot was captured, so a full-row update would revert it.
            dao.setPageImageUrl(article.id, imageUrl)
        }
    }

    fun markAllRead() = viewModelScope.launch {
        dao.markAllRead()
        Notifier.cancelAll(getApplication())
    }

    fun markAllUnread() = viewModelScope.launch {
        dao.markAllUnread()
    }

    fun toggleSaved(article: Article) = viewModelScope.launch {
        dao.setSaved(article.id, !article.isSaved)
        _messages.emit(if (article.isSaved) "Removed from Saved" else "Saved")
    }

    fun toggleRead(article: Article) = viewModelScope.launch {
        val nowRead = !article.isRead
        dao.setRead(article.id, nowRead)
        if (nowRead) Notifier.cancel(getApplication(), article.id)
    }

    fun deleteArticle(article: Article) = viewModelScope.launch {
        dao.deleteArticle(article.id)
        dao.deleteArticleContent(article.id)
        Notifier.cancel(getApplication(), article.id)
        _messages.emit("Article deleted")
    }

    // ---- Feeds ----

    /** Finds subscribable feeds for a pasted website (or feed) URL. */
    suspend fun discoverFeeds(input: String): List<FeedDiscovery.DiscoveredFeed> =
        FeedDiscovery.discover(input)

    fun addFeed(name: String, url: String, groupIds: Set<Long> = emptySet()) = viewModelScope.launch {
        val cleanUrl = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val display = name.trim().ifEmpty {
            cleanUrl.removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").substringBefore('/')
        }
        val id = dao.insertFeed(Feed(name = display, url = cleanUrl, color = Palette.next(feeds.value.size)))
        if (groupIds.isNotEmpty()) dao.setFeedGroups(id, groupIds)
        refresh()
    }

    fun toggleFeed(feed: Feed) = viewModelScope.launch {
        dao.updateFeed(feed.copy(enabled = !feed.enabled))
    }

    fun toggleFeedMuted(feed: Feed) = viewModelScope.launch {
        dao.setFeedMuted(feed.id, !feed.muted)
    }

    fun updateFeed(feed: Feed, name: String, url: String, groupIds: Set<Long>) = viewModelScope.launch {
        val cleanUrl = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val display = name.trim().ifEmpty {
            cleanUrl.removePrefix("https://").removePrefix("http://")
                .removePrefix("www.").substringBefore('/')
        }
        // A new address means the stored validators describe some other document;
        // sending them could earn a bogus 304 and never fetch the new feed.
        val validatorsStillValid = cleanUrl == feed.url
        dao.updateFeedWithGroups(
            feed.copy(
                name = display,
                url = cleanUrl,
                etag = if (validatorsStillValid) feed.etag else "",
                lastModified = if (validatorsStillValid) feed.lastModified else "",
            ),
            renameArticles = display != feed.name,
            groupIds = groupIds,
        )
    }

    fun deleteFeed(feed: Feed) = viewModelScope.launch {
        dao.deleteFeedCompletely(feed)
    }

    // ---- OPML ----

    /** Writes every source (grouped into folders) to [destination] as an OPML file. */
    fun exportOpml(destination: Uri) = viewModelScope.launch {
        val currentFeeds = feeds.value
        if (currentFeeds.isEmpty()) {
            _messages.emit("No sources to export")
            return@launch
        }
        val members = feedGroupMembers.value
        val feedIdsByGroup = members.groupBy({ it.groupId }, { it.feedId }).mapValues { it.value.toSet() }
        val groupedFeedIds = members.mapTo(HashSet()) { it.feedId }
        val opmlGroups = feedGroups.value
            .sortedBy { it.name.lowercase() }
            .map { group ->
                OpmlManager.OpmlGroup(
                    name = group.name,
                    feeds = currentFeeds
                        .filter { it.id in (feedIdsByGroup[group.id] ?: emptySet()) }
                        .map { OpmlManager.OpmlFeed(it.name, it.url) },
                )
            }
            .filter { it.feeds.isNotEmpty() }
        val ungrouped = currentFeeds
            .filter { it.id !in groupedFeedIds }
            .map { OpmlManager.OpmlFeed(it.name, it.url) }

        _messages.emit(
            when (val result = OpmlManager.export(getApplication(), destination, opmlGroups, ungrouped)) {
                is ImportResult.Success -> "Exported ${currentFeeds.size} sources"
                is ImportResult.Failure -> result.message
            }
        )
    }

    /** Adds every source in the OPML at [source], skipping URLs already present and creating missing groups. */
    fun importOpml(source: Uri) = viewModelScope.launch {
        val entries = OpmlManager.parse(getApplication(), source)
        if (entries == null) {
            _messages.emit("Couldn't read that OPML file")
            return@launch
        }
        if (entries.isEmpty()) {
            _messages.emit("No sources found in that file")
            return@launch
        }

        // Collapse duplicate rows (a source in several folders) into one entry per URL.
        val byUrl = LinkedHashMap<String, MutableList<OpmlManager.OpmlEntry>>()
        entries.forEach { entry ->
            val cleanUrl = cleanFeedUrl(entry.xmlUrl)
            if (cleanUrl.isNotBlank()) {
                byUrl.getOrPut(normalizeUrl(cleanUrl)) { mutableListOf() }.add(entry.copy(xmlUrl = cleanUrl))
            }
        }

        val existingUrls = feeds.value.mapTo(HashSet()) { normalizeUrl(it.url) }
        val groupIdByName = feedGroups.value.associateTo(HashMap()) { it.name.lowercase() to it.id }
        var added = 0
        var skipped = 0
        var groupsCreated = 0
        var colorIndex = feeds.value.size

        for ((normalized, rows) in byUrl) {
            if (normalized in existingUrls) {
                skipped++
                continue
            }
            val url = rows.first().xmlUrl
            val title = rows.firstNotNullOfOrNull { it.title.takeIf { t -> t.isNotBlank() } } ?: deriveFeedName(url)
            val feedId = dao.insertFeed(Feed(name = title, url = url, color = Palette.next(colorIndex++)))
            added++

            val groupIds = rows
                .mapNotNull { it.group?.trim()?.takeIf { name -> name.isNotEmpty() } }
                .distinct()
                .map { name ->
                    groupIdByName[name.lowercase()] ?: dao.insertFeedGroup(FeedGroup(name = name)).also { id ->
                        groupIdByName[name.lowercase()] = id
                        groupsCreated++
                    }
                }
                .toSet()
            if (groupIds.isNotEmpty()) dao.setFeedGroups(feedId, groupIds)
        }

        if (added > 0) refresh()
        _messages.emit(opmlImportSummary(added, skipped, groupsCreated))
    }

    private fun cleanFeedUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed else "https://$trimmed"
    }

    private fun normalizeUrl(url: String): String =
        url.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun deriveFeedName(url: String): String =
        url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/').ifBlank { url }

    private fun opmlImportSummary(added: Int, skipped: Int, groupsCreated: Int): String {
        if (added == 0) return if (skipped > 0) "All $skipped sources were already added" else "No sources imported"
        return buildString {
            append("Imported $added source").append(if (added == 1) "" else "s")
            if (groupsCreated > 0) append(" in $groupsCreated new group").append(if (groupsCreated == 1) "" else "s")
            if (skipped > 0) append(", skipped $skipped already added")
        }
    }

    // ---- Saved export ----

    /** Writes every saved article to [destination] as Markdown or CSV. */
    fun exportSaved(destination: Uri, format: SavedExporter.Format) = viewModelScope.launch {
        // Read from the database, not the `articles` StateFlow: the picker may have
        // outlived the activity, and a freshly rebuilt ViewModel's list starts empty.
        val saved = dao.articlesOnce().filter { it.isSaved }.sortedByDescending { it.publishedAt }
        if (saved.isEmpty()) {
            _messages.emit("No saved articles to export")
            return@launch
        }
        // Only Markdown carries bodies. Saved lists are short, so a lookup per article is fine.
        val bodies = if (format == SavedExporter.Format.MARKDOWN) {
            saved.mapNotNull { article -> dao.articleContent(article.id)?.let { article.id to it.text } }.toMap()
        } else {
            emptyMap()
        }
        _messages.emit(
            when (val result = SavedExporter.export(getApplication(), destination, format, saved, bodies)) {
                is ImportResult.Success -> if (saved.size == 1) "Exported 1 saved article" else "Exported ${saved.size} saved articles"
                is ImportResult.Failure -> result.message
            }
        )
    }

    // ---- Quick links ----

    /** The page's own title, used to name a link automatically once its URL is entered. */
    suspend fun pageTitle(url: String): String? = FeedDiscovery.pageTitle(url)

    fun addQuickLink(title: String, url: String, listId: Long = QuickLinkList.DEFAULT_ID) = viewModelScope.launch {
        val cleanUrl = url.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
        if (cleanUrl.isBlank()) return@launch
        val name = title.trim().ifEmpty { deriveFeedName(cleanUrl) }
        val target = resolveListId(listId)
        dao.insertQuickLink(
            QuickLink(
                title = name,
                url = cleanUrl,
                position = dao.nextQuickLinkPosition(target),
                listId = target,
            )
        )
    }

    fun updateQuickLink(link: QuickLink, title: String, url: String, listId: Long = link.listId) =
        viewModelScope.launch {
            val cleanUrl = url.trim().let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
            if (cleanUrl.isBlank()) return@launch
            val name = title.trim().ifEmpty { deriveFeedName(cleanUrl) }
            val target = resolveListId(listId)
            // Moving to another section appends it there rather than keeping a position
            // that belongs to the old section's numbering.
            val position = if (target == link.listId) link.position else dao.nextQuickLinkPosition(target)
            dao.updateQuickLink(link.copy(title = name, url = cleanUrl, listId = target, position = position))
        }

    fun deleteQuickLink(link: QuickLink) = viewModelScope.launch {
        dao.deleteQuickLink(link.id)
    }

    /** Falls back to the first existing list if [listId] has since been deleted. */
    private fun resolveListId(listId: Long): Long {
        val lists = quickLinkLists.value
        return when {
            lists.any { it.id == listId } -> listId
            else -> lists.firstOrNull()?.id ?: QuickLinkList.DEFAULT_ID
        }
    }

    // ---- Quick-link lists ----

    fun addQuickLinkList(name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        dao.insertQuickLinkList(QuickLinkList(name = n, position = dao.nextQuickLinkListPosition()))
    }

    fun renameQuickLinkList(list: QuickLinkList, name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        dao.updateQuickLinkList(list.copy(name = n))
    }

    /**
     * Deletes a section, moving its links into the list above it rather than dropping them.
     * The last remaining list can't be deleted — links would have nowhere to live.
     */
    fun deleteQuickLinkList(list: QuickLinkList) = viewModelScope.launch {
        val lists = quickLinkLists.value
        if (lists.size <= 1) {
            _messages.emit("Keep at least one list")
            return@launch
        }
        val target = lists.firstOrNull { it.id != list.id } ?: return@launch
        dao.moveQuickLinksToList(list.id, target.id)
        dao.deleteQuickLinkList(list.id)
        _messages.emit("Moved links to ${target.name}")
    }

    /** Persists a dragged section order, writing each list's new index as its position. */
    fun reorderQuickLinkLists(orderedIds: List<Long>) = viewModelScope.launch {
        orderedIds.forEachIndexed { index, id -> dao.setQuickLinkListPosition(id, index) }
    }

    // ---- Feed groups ----

    fun addFeedGroup(name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        dao.insertFeedGroup(FeedGroup(name = n))
    }

    fun renameFeedGroup(group: FeedGroup, name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        dao.updateFeedGroup(group.copy(name = n))
    }

    fun deleteFeedGroup(group: FeedGroup) = viewModelScope.launch {
        dao.deleteFeedGroupCompletely(group)
    }

    // ---- Keyword rules ----

    fun addRule(label: String, query: String) = viewModelScope.launch {
        val q = query.trim()
        if (q.isEmpty()) return@launch
        dao.insertRule(KeywordRule(label = label.trim().ifEmpty { q }, query = q))
        repo.reclassifyAll()
        _messages.emit("Keyword rule added")
    }

    fun updateRule(rule: KeywordRule, label: String, query: String) = viewModelScope.launch {
        val q = query.trim()
        if (q.isEmpty()) return@launch
        dao.updateRule(rule.copy(label = label.trim().ifEmpty { q }, query = q))
        repo.reclassifyAll()
    }

    fun toggleRule(rule: KeywordRule) = viewModelScope.launch {
        dao.updateRule(rule.copy(enabled = !rule.enabled))
        repo.reclassifyAll()
    }

    // Notify only decides whether a match alerts, not whether it matches: no reclassify.
    fun toggleRuleNotify(rule: KeywordRule) = viewModelScope.launch {
        dao.setRuleNotify(rule.id, !rule.notify)
    }

    fun deleteRule(rule: KeywordRule) = viewModelScope.launch {
        dao.deleteRule(rule)
        repo.reclassifyAll()
    }

    // ---- Categories ----

    fun addCategory(name: String, keywords: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isEmpty()) return@launch
        dao.insertCategory(Category(name = n, keywords = keywords.trim(), position = categories.value.size))
        repo.reclassifyAll()
    }

    fun updateCategory(category: Category) = viewModelScope.launch {
        dao.updateCategory(category)
        repo.reclassifyAll()
    }

    fun deleteCategory(category: Category) = viewModelScope.launch {
        dao.deleteCategory(category)
        repo.reclassifyAll()
    }

    // ---- Settings ----

    fun setNotifications(enabled: Boolean) = viewModelScope.launch {
        prefs.setNotifications(enabled)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        prefs.setThemeMode(mode)
    }

    fun setDesignStyle(style: DesignStyle) = viewModelScope.launch {
        prefs.setDesignStyle(style)
    }

    fun setFontFamily(font: AppFont) = viewModelScope.launch {
        prefs.setFontFamily(font)
    }

    fun setUiFontFamily(font: AppFont) = viewModelScope.launch {
        prefs.setUiFontFamily(font)
    }

    fun setFontSize(size: FontSize) = viewModelScope.launch {
        prefs.setFontSize(size)
    }

    fun setRefreshInterval(minutes: Long) = viewModelScope.launch {
        prefs.setRefreshInterval(minutes)
        RefreshWorker.schedule(getApplication(), minutes)
    }

    fun setHideRead(hidden: Boolean) = viewModelScope.launch {
        prefs.setHideRead(hidden)
    }

    fun setShowImages(enabled: Boolean) = viewModelScope.launch {
        prefs.setShowImages(enabled)
    }

    fun setAlwaysUsePageImages(enabled: Boolean) = viewModelScope.launch {
        prefs.setAlwaysUsePageImages(enabled)
    }

    fun setListLayout(layout: ListLayout) = viewModelScope.launch {
        prefs.setListLayout(layout)
    }

    fun setGroupSimilarStories(enabled: Boolean) = viewModelScope.launch {
        prefs.setGroupSimilarStories(enabled)
    }

    fun setLinksEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setLinksEnabled(enabled)
    }

    fun setDigestNotifications(enabled: Boolean) = viewModelScope.launch {
        prefs.setDigestNotifications(enabled)
    }

    fun setQuietHoursEnabled(enabled: Boolean) = viewModelScope.launch {
        prefs.setQuietHoursEnabled(enabled)
    }

    fun setQuietHoursStartMinutes(minutes: Int) = viewModelScope.launch {
        prefs.setQuietHoursStartMinutes(minutes)
    }

    fun setQuietHoursEndMinutes(minutes: Int) = viewModelScope.launch {
        prefs.setQuietHoursEndMinutes(minutes)
    }

    fun setPrefetchAlertBodies(enabled: Boolean) = viewModelScope.launch {
        prefs.setPrefetchAlertBodies(enabled)
    }

    /** Finishes the first-run intro: adds the chosen feeds and records the section choices. */
    fun completeOnboarding(
        selected: List<PopularFeed>,
        linksEnabled: Boolean,
    ) = viewModelScope.launch {
        prefs.setLinksEnabled(linksEnabled)
        if (selected.isNotEmpty()) {
            val existing = feeds.value.mapTo(HashSet()) { normalizeUrl(it.url) }
            val groupIdByName = feedGroups.value.associateTo(HashMap()) { it.name.lowercase() to it.id }
            var colorIndex = feeds.value.size
            for (feed in selected) {
                if (normalizeUrl(feed.url) in existing) continue
                val feedId = dao.insertFeed(Feed(name = feed.name, url = feed.url, color = Palette.next(colorIndex++)))
                existing.add(normalizeUrl(feed.url))
                val groupId = groupIdByName[feed.group.lowercase()]
                    ?: dao.insertFeedGroup(FeedGroup(name = feed.group)).also { groupIdByName[feed.group.lowercase()] = it }
                dao.setFeedGroups(feedId, setOf(groupId))
            }
            refresh()
        }
        prefs.setOnboardingComplete()
    }

    /**
     * The Statistics screen's 30-day keyword-match chart. A one-shot read rather than
     * a Flow: it's looked at once per visit, and a live query would re-aggregate on
     * every refresh's inserts for a chart nobody is watching change.
     */
    suspend fun alertTrend(): AlertTrend.Trend {
        val todayStart = TextUtil.startOfToday()
        val rows = dao.alertMatchesSince(AlertTrend.windowStart(todayStart))
        val currentRules = dao.rules().first()
        return withContext(Dispatchers.Default) { AlertTrend.aggregate(rows, currentRules, todayStart) }
    }
}
