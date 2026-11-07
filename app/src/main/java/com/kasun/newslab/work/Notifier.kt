package com.kasun.newslab.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kasun.newslab.MainActivity
import com.kasun.newslab.R
import com.kasun.newslab.data.Article

object Notifier {

    const val CHANNEL_ID = "keyword_alerts"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Keyword alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Fires when a new article matches one of your keyword rules"
            },
        )
    }

    @SuppressLint("MissingPermission")
    fun notifyMatches(context: Context, articles: List<Article>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        articles.take(5).forEach { article ->
            // Deep-links into the in-app reader (not an external browser tab) so opening
            // it also marks the article read and clears this notification via MainViewModel.
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                // Per-article data URI: extras don't count toward PendingIntent
                // identity, so without this a request-code collision would make
                // two alerts share one intent and open the wrong article.
                data = Uri.parse("newslab://article/${Uri.encode(article.id)}")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_ARTICLE_ID, article.id)
            }
            val pending = PendingIntent.getActivity(
                context,
                article.id.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(article.matchedLabels.ifBlank { "Keyword alert" })
                .setContentText(article.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(article.title))
                .setSubText(article.feedName)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            // Tagged with the full article id — unlike the int hash alone, tags
            // can't collide and silently replace another article's notification.
            manager.notify(article.id, 0, notification)
        }
    }

    /** Clears a keyword-alert notification once its article is read (however that happened). */
    fun cancel(context: Context, articleId: String) {
        NotificationManagerCompat.from(context).cancel(articleId, 0)
    }

    /** Clears every keyword-alert notification this app has posted (e.g. after "mark all read"). */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}
