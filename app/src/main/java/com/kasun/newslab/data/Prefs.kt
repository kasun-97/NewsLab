package com.kasun.newslab.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        private val DIGEST_NOTIFICATIONS = booleanPreferencesKey("digest_notifications")
        private val QUIET_HOURS_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val QUIET_HOURS_START = intPreferencesKey("quiet_hours_start")
        private val QUIET_HOURS_END = intPreferencesKey("quiet_hours_end")
        private val PREFETCH_ALERT_BODIES = booleanPreferencesKey("prefetch_alert_bodies")
        private val MUTE_WORDS = stringPreferencesKey("mute_words")
        private val AUTO_BACKUP_FOLDER = stringPreferencesKey("auto_backup_folder")
        private val AUTO_BACKUP_LAST_AT = longPreferencesKey("auto_backup_last_at")
        private val AUTO_BACKUP_LAST_ERROR = stringPreferencesKey("auto_backup_last_error")

        /** One setting that travels in a backup, with how its value is written as text and read back. */
        private class Carried<T : Any>(
            val key: Preferences.Key<T>,
            val encode: (T) -> String,
            val decode: (String) -> T?,
        ) {
            fun read(prefs: Preferences): Pair<String, String>? = prefs[key]?.let { key.name to encode(it) }

            /** A value missing from the backup, or unreadable, puts the setting back to its default. */
            fun write(prefs: MutablePreferences, raw: String?) {
                val value = raw?.let(decode)
                if (value == null) prefs.remove(key) else prefs[key] = value
            }
        }

        private fun flag(key: Preferences.Key<Boolean>) = Carried(key, Boolean::toString, String::toBooleanStrictOrNull)
        private fun text(key: Preferences.Key<String>) = Carried(key, { it }, { it })

        /**
         * The settings a backup carries: everything the reader chose. Left out are the
         * last-refresh stamp, which describes the device rather than a choice, and the
         * automatic backup's folder and history, since access to a folder is granted to
         * one install and means nothing on another.
         */
        private val CARRIED: List<Carried<*>> = listOf(
            flag(NOTIFICATIONS), text(THEME_MODE), text(DESIGN_STYLE), text(FONT_FAMILY),
            text(UI_FONT_FAMILY), text(FONT_SIZE),
            Carried(REFRESH_INTERVAL_MINUTES, Long::toString, String::toLongOrNull),
            flag(HIDE_READ), flag(SHOW_IMAGES), flag(PAGE_IMAGES), text(LIST_LAYOUT),
            flag(GROUP_SIMILAR), flag(ONBOARDING_DONE), flag(LINKS_ENABLED),
            flag(DIGEST_NOTIFICATIONS), flag(QUIET_HOURS_ENABLED),
            Carried(QUIET_HOURS_START, Int::toString, String::toIntOrNull),
            Carried(QUIET_HOURS_END, Int::toString, String::toIntOrNull),
            flag(PREFETCH_ALERT_BODIES), text(MUTE_WORDS),
        )

        const val DEFAULT_REFRESH_MINUTES = 15L

        /** Quiet hours default to 22:00 to 07:00, stored as minutes after midnight. */
        const val DEFAULT_QUIET_START_MINUTES = 22 * 60
        const val DEFAULT_QUIET_END_MINUTES = 7 * 60

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }
    }

    /** The settings a backup carries, by name; one never changed from its default is absent. */
    suspend fun exportSettings(): Map<String, String> {
        val prefs = context.dataStore.data.first()
        return CARRIED.mapNotNull { it.read(prefs) }.toMap()
    }

    /**
     * Puts the carried settings back as a backup recorded them. A setting the backup
     * doesn't mention returns to its default, as it was when the backup was made;
     * names this version doesn't know are ignored.
     */
    suspend fun importSettings(values: Map<String, String>) {
        context.dataStore.edit { prefs -> CARRIED.forEach { it.write(prefs, values[it.key.name]) } }
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

    /** When on, several alerts from one refresh arrive as a single summary notification. */
    val digestNotifications: Flow<Boolean> =
        context.dataStore.data.map { it[DIGEST_NOTIFICATIONS] ?: false }

    suspend fun setDigestNotifications(enabled: Boolean) {
        context.dataStore.edit { it[DIGEST_NOTIFICATIONS] = enabled }
    }

    /** When on, alerts falling inside the quiet window are held and posted after it. */
    val quietHoursEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[QUIET_HOURS_ENABLED] ?: false }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        context.dataStore.edit { it[QUIET_HOURS_ENABLED] = enabled }
    }

    /** Start of the quiet window, in minutes after local midnight. */
    val quietHoursStartMinutes: Flow<Int> =
        context.dataStore.data.map { it[QUIET_HOURS_START] ?: DEFAULT_QUIET_START_MINUTES }

    suspend fun setQuietHoursStartMinutes(minutes: Int) {
        context.dataStore.edit { it[QUIET_HOURS_START] = minutes }
    }

    /** End of the quiet window, in minutes after local midnight; may be before the start (overnight). */
    val quietHoursEndMinutes: Flow<Int> =
        context.dataStore.data.map { it[QUIET_HOURS_END] ?: DEFAULT_QUIET_END_MINUTES }

    suspend fun setQuietHoursEndMinutes(minutes: Int) {
        context.dataStore.edit { it[QUIET_HOURS_END] = minutes }
    }

    /**
     * When on, a background refresh on an unmetered network caches the bodies of new
     * alert matches, so they open offline and are covered by full-text search.
     */
    val prefetchAlertBodies: Flow<Boolean> =
        context.dataStore.data.map { it[PREFETCH_ALERT_BODIES] ?: true }

    suspend fun setPrefetchAlertBodies(enabled: Boolean) {
        context.dataStore.edit { it[PREFETCH_ALERT_BODIES] = enabled }
    }

    /** Comma-separated terms that keep an article out of the timeline; see [MuteFilter]. */
    val muteWords: Flow<String> =
        context.dataStore.data.map { it[MUTE_WORDS] ?: "" }

    suspend fun setMuteWords(words: String) {
        context.dataStore.edit { it[MUTE_WORDS] = words }
    }

    /**
     * The folder (a document-tree URI) the weekly automatic backup writes into; blank
     * while it is off. The folder is the switch: there is nothing to back up to without
     * one, so a separate on/off flag could only disagree with it.
     */
    val autoBackupFolder: Flow<String> =
        context.dataStore.data.map { it[AUTO_BACKUP_FOLDER] ?: "" }

    /** Turns the automatic backup on with a folder, or off with a blank one; either way its history starts over. */
    suspend fun setAutoBackupFolder(folder: String) {
        context.dataStore.edit {
            it[AUTO_BACKUP_FOLDER] = folder
            it.remove(AUTO_BACKUP_LAST_AT)
            it.remove(AUTO_BACKUP_LAST_ERROR)
        }
    }

    /** When the last automatic backup was written, 0 if none has been yet. */
    val autoBackupLastAt: Flow<Long> =
        context.dataStore.data.map { it[AUTO_BACKUP_LAST_AT] ?: 0L }

    /** Why the latest automatic backup failed; blank when it succeeded or none has run. */
    val autoBackupLastError: Flow<String> =
        context.dataStore.data.map { it[AUTO_BACKUP_LAST_ERROR] ?: "" }

    /** Records an automatic backup's outcome: a blank [error] is a success at [time]. */
    suspend fun recordAutoBackup(time: Long, error: String) {
        context.dataStore.edit {
            if (error.isBlank()) it[AUTO_BACKUP_LAST_AT] = time
            it[AUTO_BACKUP_LAST_ERROR] = error
        }
    }
}
