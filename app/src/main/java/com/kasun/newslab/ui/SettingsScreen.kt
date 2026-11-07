package com.kasun.newslab.ui

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kasun.newslab.MainViewModel
import com.kasun.newslab.data.AlertPolicy
import com.kasun.newslab.data.AppFont
import com.kasun.newslab.data.BackupManager
import com.kasun.newslab.data.DesignStyle
import com.kasun.newslab.data.FontSize
import com.kasun.newslab.data.ImportResult
import com.kasun.newslab.data.ThemeMode
import com.kasun.newslab.ui.theme.nd
import com.kasun.newslab.ui.theme.toFontFamily
import kotlinx.coroutines.launch

private val REFRESH_INTERVAL_OPTIONS = listOf(
    "15m" to 15L,
    "30m" to 30L,
    "1h" to 60L,
    "2h" to 120L,
    "4h" to 240L,
)

private val FONT_OPTIONS = listOf(
    "System" to AppFont.SYSTEM,
    "Literata" to AppFont.LITERATA,
    "Open Sans" to AppFont.OPEN_SANS,
)

private val FONT_SIZE_OPTIONS = listOf(
    "Small" to FontSize.SMALL,
    "Default" to FontSize.DEFAULT,
    "Large" to FontSize.LARGE,
    "Extra Large" to FontSize.EXTRA_LARGE,
)

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenKeywordRules: () -> Unit,
    onOpenStatistics: () -> Unit,
    scrollState: ScrollState,
) {
    val themeMode by vm.themeMode.collectAsState()
    val designStyle by vm.designStyle.collectAsState()
    val appFont by vm.fontFamily.collectAsState()
    val uiFont by vm.uiFontFamily.collectAsState()
    val appFontSize by vm.fontSize.collectAsState()
    val refreshMinutes by vm.refreshIntervalMinutes.collectAsState()
    val hideRead by vm.hideReadArticles.collectAsState()
    val showImages by vm.showImages.collectAsState()
    val pageImages by vm.alwaysUsePageImages.collectAsState()
    val notifications by vm.notificationsEnabled.collectAsState()
    val linksEnabled by vm.linksEnabled.collectAsState()
    val articleStats by vm.articleStats.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = nd.ink)
            }
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = nd.ink)
        }
        HorizontalDivider(color = nd.border, thickness = 1.dp)

        SectionLabel("FEEDS", nd.muted)
        SettingsNavRow("Sources", "Manage RSS sources and groups.", onOpenSources)
        SettingsNavRow("Categories", "Manage article categories.", onOpenCategories)
        SettingsNavRow("Keyword Rules", "Manage alert keywords.", onOpenKeywordRules)

        SectionLabel("APPEARANCE", nd.muted)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip("Warm", designStyle == DesignStyle.WARM_LEDGER) {
                vm.setDesignStyle(DesignStyle.WARM_LEDGER)
            }
            SelectChip("Classic", designStyle == DesignStyle.CLASSIC) {
                vm.setDesignStyle(DesignStyle.CLASSIC)
            }
        }
        Text(
            "Warm is the ruled-paper look with amber accents. " +
                "Classic is the original white-and-grey design.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = nd.faint,
            lineHeight = 17.sp,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SelectChip("Light", themeMode == ThemeMode.LIGHT) { vm.setThemeMode(ThemeMode.LIGHT) }
            SelectChip("Dark", themeMode == ThemeMode.DARK) { vm.setThemeMode(ThemeMode.DARK) }
            SelectChip("System", themeMode == ThemeMode.SYSTEM) { vm.setThemeMode(ThemeMode.SYSTEM) }
        }
        Text(
            "System follows your device's light/dark setting.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = nd.faint,
        )
        FontPickerRow(
            title = "App font",
            description = "Used for the interface — feeds, lists, menus and settings.",
            selected = uiFont,
            onSelect = vm::setUiFontFamily,
        )
        FontPickerRow(
            title = "Reading font",
            description = "Used for an opened article's text, on top of the app font.",
            selected = appFont,
            onSelect = vm::setFontFamily,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Font size", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(
                    "Scales all text throughout the app.",
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
            var fontSizeMenuOpen by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(nd.chipBg)
                        .clickable { fontSizeMenuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        FONT_SIZE_OPTIONS.first { it.second == appFontSize }.first,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = nd.ink,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = nd.faint,
                    )
                }
                DropdownMenu(
                    expanded = fontSizeMenuOpen,
                    onDismissRequest = { fontSizeMenuOpen = false },
                    shape = RoundedCornerShape(2.dp),
                    containerColor = nd.card,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    FONT_SIZE_OPTIONS.forEach { (label, size) ->
                        DropdownMenuItem(
                            text = {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                            },
                            onClick = {
                                vm.setFontSize(size)
                                fontSizeMenuOpen = false
                            },
                            trailingIcon = {
                                if (size == appFontSize) {
                                    Icon(
                                        Icons.Outlined.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = nd.ink,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        SectionLabel("BACKGROUND REFRESH", nd.muted)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            REFRESH_INTERVAL_OPTIONS.forEach { (label, minutes) ->
                SelectChip(label, refreshMinutes == minutes) { vm.setRefreshInterval(minutes) }
            }
        }
        Text(
            "How often the app checks feeds in the background and notifies you of new " +
                "keyword matches while it's closed. Shorter intervals use more battery.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = nd.faint,
            lineHeight = 17.sp,
        )

        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    batteryUnrestricted = isIgnoringBatteryOptimizations(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        if (!batteryUnrestricted) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Background refresh may be unreliable",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = nd.ink,
                    )
                    Text(
                        if (Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
                            "Your phone's battery saver can stop this from running on schedule. " +
                                "Find NewsLab in the list and allow unrestricted battery use. " +
                                "Also check Security app → Permissions → Autostart is on for NewsLab."
                        } else {
                            "Your phone's battery saver can stop this from running on schedule. " +
                                "Find NewsLab in the list and allow unrestricted battery use."
                        },
                        fontSize = 12.sp,
                        color = nd.faint,
                        lineHeight = 16.sp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(nd.chipBg)
                        .clickable { openBatteryOptimizationSettings(context) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("Open", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                }
            }
        }

        SectionLabel("SECTIONS", nd.muted)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Links", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(
                    "Show the Links tab for reference pages that aren't feeds.",
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
            NdSwitch(checked = linksEnabled, onCheckedChange = { vm.setLinksEnabled(it) })
        }

        SectionLabel("NOTIFICATIONS", nd.muted)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Keyword alerts", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(
                    "Notify when a background check finds a new keyword match.",
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
            NdSwitch(checked = notifications, onCheckedChange = { vm.setNotifications(it) })
        }
        // Refinements of the master switch, so shown only while it's on, like the
        // high-quality images row under its parent.
        if (notifications) {
            val digest by vm.digestNotifications.collectAsState()
            val quietHours by vm.quietHoursEnabled.collectAsState()
            val quietStart by vm.quietHoursStartMinutes.collectAsState()
            val quietEnd by vm.quietHoursEndMinutes.collectAsState()
            NotificationSubRow(
                title = "Digest notification",
                subtitle = "When a check finds several matches, post one summary listing them " +
                    "instead of one notification each.",
            ) {
                NdSwitch(checked = digest, onCheckedChange = { vm.setDigestNotifications(it) })
            }
            NotificationSubRow(
                title = "Quiet hours",
                subtitle = "Hold alerts during these hours and post them after. " +
                    "Matches still appear in the app as usual.",
            ) {
                NdSwitch(checked = quietHours, onCheckedChange = { vm.setQuietHoursEnabled(it) })
            }
            if (quietHours) {
                // The platform picker: a few lines here instead of a Compose time
                // picker dependency for two rows.
                val pickTime: (Int, (Int) -> Unit) -> Unit = { current, onPicked ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute -> onPicked(hour * 60 + minute) },
                        current / 60,
                        current % 60,
                        true,
                    ).show()
                }
                QuietTimeRow("Starts", quietStart) { pickTime(quietStart, vm::setQuietHoursStartMinutes) }
                QuietTimeRow("Ends", quietEnd) { pickTime(quietEnd, vm::setQuietHoursEndMinutes) }
            }
        }

        SectionLabel("READING", nd.muted)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hide read articles", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(
                    "Keep read articles out of the inbox timeline. Saved articles always stay visible.",
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
            NdSwitch(checked = hideRead, onCheckedChange = { vm.setHideRead(it) })
        }
        val prefetchAlertBodies by vm.prefetchAlertBodies.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Pre-fetch alert articles on Wi-Fi", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(
                    "After a background check on an unmetered network, save the full text of new " +
                        "keyword matches so they open offline and turn up in search.",
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
            NdSwitch(checked = prefetchAlertBodies, onCheckedChange = { vm.setPrefetchAlertBodies(it) })
        }

        SectionLabel("IMAGES", nd.muted)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show article images", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
                Text(
                    "Thumbnails in the inbox and the photo at the top of an article. " +
                        "Turn off to save data.",
                    fontSize = 12.sp,
                    color = nd.faint,
                    lineHeight = 17.sp,
                )
            }
            NdSwitch(checked = showImages, onCheckedChange = { vm.setShowImages(it) })
        }
        if (showImages) {
            Row(
                Modifier.fillMaxWidth().padding(start = 34.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "High-quality images",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = nd.ink,
                    )
                    Text(
                        "Always take the full-size photo from the article's own page, instead of the " +
                            "smaller one the feed supplies. Costs a little more data per article opened.",
                        fontSize = 12.sp,
                        color = nd.faint,
                        lineHeight = 16.sp,
                    )
                }
                NdSwitch(checked = pageImages, onCheckedChange = { vm.setAlwaysUsePageImages(it) })
            }
        }

        SectionLabel("STATISTICS", nd.muted)
        StatRow("Total articles", "${articleStats.total}")
        StatRow("Read", "${articleStats.total - articleStats.unread}")
        StatRow("Saved", "${articleStats.saved}")
        StatRow("Keyword alerts", "${articleStats.alertMatches}")
        SettingsNavRow("View all statistics", "Per-source breakdown and refresh history.", onOpenStatistics)

        SectionLabel("BACKUP", nd.muted)
        val scope = rememberCoroutineScope()
        var confirmImport by remember { mutableStateOf(false) }
        var confirmReset by remember { mutableStateOf(false) }

        val exportDbLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val result = BackupManager.export(context, uri, includeImages = false)
                    Toast.makeText(context, exportMessage(result), Toast.LENGTH_SHORT).show()
                }
            }
        }
        val exportZipLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val result = BackupManager.export(context, uri, includeImages = true)
                    Toast.makeText(context, exportMessage(result), Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Whether the picked file is a plain database or a zip with images is
        // detected from its own content, so a single picker covers both exports.
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    when (val result = BackupManager.import(context, uri)) {
                        is ImportResult.Success -> BackupManager.restartApp(context)
                        is ImportResult.Failure -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        SettingsNavRow(
            "Export database",
            "Feeds, categories, rules and articles — no images.",
        ) { exportDbLauncher.launch(BackupManager.exportFileName(includeImages = false)) }
        SettingsNavRow(
            "Export database + images",
            "Also bundles cached article thumbnails.",
        ) { exportZipLauncher.launch(BackupManager.exportFileName(includeImages = true)) }
        SettingsNavRow(
            "Import",
            "Restores a backup, images included if the file has them. The app restarts afterward.",
        ) { confirmImport = true }
        SettingsNavRow(
            "Reset app",
            "Erase everything and restore the app to its fresh-install state. The app restarts afterward.",
        ) { confirmReset = true }

        Spacer(Modifier.height(12.dp))

        if (confirmImport) {
            ConfirmDialog(
                title = "Import and replace everything?",
                body = "This overwrites your current feeds, categories, rules, articles, and " +
                    "image cache with the contents of the backup you choose. Your current " +
                    "database is kept as a backup file first, but export your current data too " +
                    "if you want an easy way back.",
                confirmLabel = "Choose file",
                onDismiss = { confirmImport = false },
                onConfirm = {
                    confirmImport = false
                    importLauncher.launch(arrayOf("*/*"))
                },
            )
        }

        if (confirmReset) {
            ConfirmDialog(
                title = "Reset app to defaults?",
                body = "This permanently erases everything — all articles, sources, categories, " +
                    "keyword rules, saved articles and settings — and restores " +
                    "the default feeds. This can't be undone. Export a backup first if you might " +
                    "want your data back. The app will restart.",
                confirmLabel = "Reset",
                onDismiss = { confirmReset = false },
                onConfirm = {
                    confirmReset = false
                    scope.launch {
                        when (val result = BackupManager.reset(context)) {
                            is ImportResult.Success -> BackupManager.restartApp(context)
                            is ImportResult.Failure ->
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
    }
}

private fun exportMessage(result: ImportResult): String = when (result) {
    is ImportResult.Success -> "Exported"
    is ImportResult.Failure -> result.message
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return true
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Opens the system battery-optimisation screen so the user can exempt the app
 * themselves. Deliberately not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which pops
 * the one-tap dialog but needs a permission Play only grants apps that genuinely break
 * under Doze. These two actions need no permission at all.
 */
private fun openBatteryOptimizationSettings(context: Context) {
    // The exemption list first, since that's the screen the toggle actually lives on;
    // the app's own details page is the fallback, and on OEM ROMs is often where the
    // vendor puts its extra battery restrictions anyway.
    val intents = listOf(
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) {
            // Some OEM ROMs don't ship this screen; fall through to the next.
        }
    }
}

/** An indented row under the Keyword alerts switch, with its control on the right. */
@Composable
private fun NotificationSubRow(title: String, subtitle: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 34.dp, end = 18.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = nd.ink)
            Text(subtitle, fontSize = 12.sp, color = nd.faint, lineHeight = 16.sp)
        }
        control()
    }
}

/** A quiet-hours bound as a tappable "22:00" chip, opening the time picker. */
@Composable
private fun QuietTimeRow(label: String, minutes: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 52.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = nd.ink)
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(nd.chipBg)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(AlertPolicy.clockLabel(minutes), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
        }
    }
}

@Composable
private fun SettingsNavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
            Text(subtitle, fontSize = 12.sp, color = nd.faint, lineHeight = 17.sp)
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = nd.faint,
        )
    }
}

/** Label/description row with a dropdown that previews each option in its own typeface. */
@Composable
private fun FontPickerRow(
    title: String,
    description: String,
    selected: AppFont,
    onSelect: (AppFont) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
            Text(description, fontSize = 12.sp, color = nd.faint, lineHeight = 17.sp)
        }
        var menuOpen by remember { mutableStateOf(false) }
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(nd.chipBg)
                    .clickable { menuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    FONT_OPTIONS.first { it.second == selected }.first,
                    fontFamily = selected.toFontFamily(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nd.ink,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
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
                FONT_OPTIONS.forEach { (label, font) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                fontFamily = font.toFontFamily(),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = nd.ink,
                            )
                        },
                        onClick = {
                            onSelect(font)
                            menuOpen = false
                        },
                        trailingIcon = {
                            if (font == selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = nd.ink,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
