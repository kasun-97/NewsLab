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
import com.kasun.newslab.data.AlertPolicy
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

    /** Tag of the one summary notification; each new digest replaces the last. */
    const val DIGEST_TAG = "digest"

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun notifyMatches(context: Context, articles: List<Article>) {
        if (!canPost(context)) return
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

    /**
     * One summary for a batch of matches, listing their titles. It opens the app rather
     * than any one article; the Alerts tab has them all. Replacing the previous digest
     * (same tag) is deliberate: its matches are still in the Alerts tab, and a stack of
     * summaries would bring back the noise the digest exists to cut.
     */
    @SuppressLint("MissingPermission")
    fun notifyDigest(context: Context, articles: List<Article>) {
        if (articles.isEmpty() || !canPost(context)) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // No data URI, so this can't collide with a per-article intent, which all carry one.
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = AlertPolicy.digestTitle(articles)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        articles.take(AlertPolicy.DIGEST_LINES).forEach { style.addLine(it.title) }
        val more = articles.size - AlertPolicy.DIGEST_LINES
        if (more > 0) style.setSummaryText("+$more more")
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(articles.first().title)
            .setStyle(style)
            .setNumber(articles.size)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(DIGEST_TAG, 0, notification)
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
