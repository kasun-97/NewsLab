package com.kasun.newslab

import android.app.Application
import com.kasun.newslab.data.Prefs
import com.kasun.newslab.work.Notifier
import com.kasun.newslab.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NewsLabApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        CoroutineScope(Dispatchers.IO).launch {
            val minutes = Prefs.get(this@NewsLabApp).refreshIntervalMinutes.first()
            RefreshWorker.schedule(this@NewsLabApp, minutes)
        }
    }
}
