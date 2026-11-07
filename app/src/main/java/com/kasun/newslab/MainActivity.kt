package com.kasun.newslab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kasun.newslab.data.ThemeMode
import com.kasun.newslab.ui.App
import com.kasun.newslab.ui.theme.NewsLabTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingArticleId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch carries a to-be-handled notification tap; on
        // recreation (rotation, process-death restore) the extra is stale and
        // re-reading it would force the article back open.
        if (savedInstanceState == null) {
            pendingArticleId = intent?.getStringExtra(EXTRA_ARTICLE_ID)
        }
        setContent {
            val vm: MainViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val designStyle by vm.designStyle.collectAsState()
            val uiFont by vm.uiFontFamily.collectAsState()
            val appFontSize by vm.fontSize.collectAsState()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            // Status/nav bar icon contrast must follow the in-app theme, which can
            // override the system setting enableEdgeToEdge() would otherwise use.
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            NewsLabTheme(themeMode, designStyle, uiFont, appFontSize) {
                App(
                    vm = vm,
                    openArticleId = pendingArticleId,
                    onOpenArticleConsumed = {
                        pendingArticleId = null
                        intent?.removeExtra(EXTRA_ARTICLE_ID)
                    },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingArticleId = intent.getStringExtra(EXTRA_ARTICLE_ID)
    }

    companion object {
        const val EXTRA_ARTICLE_ID = "article_id"
    }
}
