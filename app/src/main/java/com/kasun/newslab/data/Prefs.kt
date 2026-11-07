package com.kasun.newslab.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The app's design language. WARM_LEDGER is the ruled-paper look — warm grounds,
 * amber for the reader's own intent, right angles. CLASSIC is the original look —
 * white and cool greys, red keyword accents, rounded corners.
 */
enum class DesignStyle { WARM_LEDGER, CLASSIC }

/**
 * A selectable typeface, chosen separately for an opened article and for the interface.
 * SYSTEM leaves the device's own default in place.
 */
enum class AppFont { SYSTEM, LITERATA, OPEN_SANS }

enum class FontSize { SMALL, DEFAULT, LARGE, EXTRA_LARGE }

/** How article lists render each item: a timeline row, a photo-forward card, or a dense headline row. */
enum class ListLayout { TIMELINE, CARD, COMPACT }

class Prefs private constructor(private val context: Context) {

    companion object {
        private val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DESIGN_STYLE = stringPreferencesKey("design_style")
        private val FONT_FAMILY = stringPreferencesKey("font_family")
        private val UI_FONT_FAMILY = stringPreferencesKey("ui_font_family")
        private val FONT_SIZE = stringPreferencesKey("font_size")
        private val REFRESH_INTERVAL_MINUTES = longPreferencesKey("refresh_interval_minutes")
        private val HIDE_READ = booleanPreferencesKey("hide_read_articles")
        private val SHOW_IMAGES = booleanPreferencesKey("show_images")
        private val PAGE_IMAGES = booleanPreferencesKey("page_images_always")
        private val LIST_LAYOUT = stringPreferencesKey("list_layout")
        private val GROUP_SIMILAR = booleanPreferencesKey("group_similar_stories")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_complete")
        private val LINKS_ENABLED = booleanPreferencesKey("links_enabled")
        private val LAST_REFRESH_AT = longPreferencesKey("last_refresh_at")

        const val DEFAULT_REFRESH_MINUTES = 15L

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }
    }

    val notificationsEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[NOTIFICATIONS] ?: true }

    suspend fun setNotifications(enabled: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS] = enabled }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[THEME_MODE]?.let { raw -> runCatching { ThemeMode.valueOf(raw) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    val designStyle: Flow<DesignStyle> = context.dataStore.data.map { prefs ->
        prefs[DESIGN_STYLE]?.let { raw -> runCatching { DesignStyle.valueOf(raw) }.getOrNull() }
            ?: DesignStyle.WARM_LEDGER
    }

    suspend fun setDesignStyle(style: DesignStyle) {
        context.dataStore.edit { it[DESIGN_STYLE] = style.name }
    }

    // An install from before the bundled fonts may hold one of the old generic names
    // (DEFAULT, SERIF, SANS_SERIF, MONOSPACE); valueOf fails on those and it falls back
    // to the system font, which is what DEFAULT meant anyway.
    val fontFamily: Flow<AppFont> = context.dataStore.data.map { prefs ->
        prefs[FONT_FAMILY]?.let { raw -> runCatching { AppFont.valueOf(raw) }.getOrNull() }
            ?: AppFont.SYSTEM
    }

    suspend fun setFontFamily(font: AppFont) {
        context.dataStore.edit { it[FONT_FAMILY] = font.name }
    }

    /** Typeface for the interface — everything except an opened article's text. */
    val uiFontFamily: Flow<AppFont> = context.dataStore.data.map { prefs ->
        prefs[UI_FONT_FAMILY]?.let { raw -> runCatching { AppFont.valueOf(raw) }.getOrNull() }
            ?: AppFont.SYSTEM
    }

    suspend fun setUiFontFamily(font: AppFont) {
        context.dataStore.edit { it[UI_FONT_FAMILY] = font.name }
    }

    val fontSize: Flow<FontSize> = context.dataStore.data.map { prefs ->
        prefs[FONT_SIZE]?.let { raw -> runCatching { FontSize.valueOf(raw) }.getOrNull() }
            ?: FontSize.DEFAULT
    }

    suspend fun setFontSize(size: FontSize) {
        context.dataStore.edit { it[FONT_SIZE] = size.name }
    }

    val refreshIntervalMinutes: Flow<Long> =
        context.dataStore.data.map { it[REFRESH_INTERVAL_MINUTES] ?: DEFAULT_REFRESH_MINUTES }

    suspend fun setRefreshInterval(minutes: Long) {
        context.dataStore.edit { it[REFRESH_INTERVAL_MINUTES] = minutes }
    }

    val hideRead: Flow<Boolean> =
        context.dataStore.data.map { it[HIDE_READ] ?: false }

    suspend fun setHideRead(hidden: Boolean) {
        context.dataStore.edit { it[HIDE_READ] = hidden }
    }

    val showImages: Flow<Boolean> =
        context.dataStore.data.map { it[SHOW_IMAGES] ?: true }

    suspend fun setShowImages(enabled: Boolean) {
        context.dataStore.edit { it[SHOW_IMAGES] = enabled }
    }

    /**
     * When on, opening an article always reads the full-size image off its own page,
     * rather than only when the feed carried no image at all. Off by default — it
     * costs a request and a larger download per article opened.
     */
    val alwaysUsePageImages: Flow<Boolean> =
        context.dataStore.data.map { it[PAGE_IMAGES] ?: false }

    suspend fun setAlwaysUsePageImages(enabled: Boolean) {
        context.dataStore.edit { it[PAGE_IMAGES] = enabled }
    }

    val listLayout: Flow<ListLayout> = context.dataStore.data.map { prefs ->
        prefs[LIST_LAYOUT]?.let { raw -> runCatching { ListLayout.valueOf(raw) }.getOrNull() }
            ?: ListLayout.TIMELINE
    }

    suspend fun setListLayout(layout: ListLayout) {
        context.dataStore.edit { it[LIST_LAYOUT] = layout.name }
    }

    /** When on, the inbox collapses near-duplicate stories from different sources into one card. */
    val groupSimilarStories: Flow<Boolean> =
        context.dataStore.data.map { it[GROUP_SIMILAR] ?: false }

    suspend fun setGroupSimilarStories(enabled: Boolean) {
        context.dataStore.edit { it[GROUP_SIMILAR] = enabled }
    }

    /** False until the first-run intro has been completed or skipped. */
    val onboardingComplete: Flow<Boolean> =
        context.dataStore.data.map { it[ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[ONBOARDING_DONE] = true }
    }

    /** Whether the Links tab of saved reference pages is shown. */
    val linksEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[LINKS_ENABLED] ?: true }

    suspend fun setLinksEnabled(enabled: Boolean) {
        context.dataStore.edit { it[LINKS_ENABLED] = enabled }
    }

    /** Timestamp of the last completed refresh pass (manual or background), 0 if never. */
    val lastRefreshAt: Flow<Long> =
        context.dataStore.data.map { it[LAST_REFRESH_AT] ?: 0L }

    suspend fun setLastRefreshAt(time: Long) {
        context.dataStore.edit { it[LAST_REFRESH_AT] = time }
    }
}
