package com.kasun.newslab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kasun.newslab.data.POPULAR_FEEDS
import com.kasun.newslab.data.PopularFeed
import com.kasun.newslab.ui.theme.nd
import kotlinx.coroutines.launch

private data class Slide(val icon: ImageVector, val title: String, val body: String)

private val SLIDES = listOf(
    Slide(
        Icons.AutoMirrored.Outlined.Article,
        "Welcome to NewsLab",
        "Your sources, keyword alerts and saved reading in one quiet place — everything stays on your phone.",
    ),
    Slide(
        Icons.Outlined.Layers,
        "Every source, together",
        "Follow any RSS feed. The same story from different outlets collapses into one card you can swipe between.",
    ),
    Slide(
        Icons.Outlined.TrackChanges,
        "Never miss a topic",
        "Set keyword alerts and get notified the moment a story matches — pinned to the top of your inbox.",
    ),
    Slide(
        Icons.Outlined.Link,
        "Keep your references close",
        "An optional Links tab for the pages that aren't feeds — dashboards, data releases, country hubs.",
    ),
)

/**
 * First-run intro: a few feature slides followed by a popular-feeds picker that also
 * asks whether to enable the optional Links section. [onDone] is called with the
 * feeds to add and the section choice.
 */
@Composable
fun OnboardingScreen(
    onDone: (feeds: List<PopularFeed>, linksEnabled: Boolean) -> Unit,
) {
    val pageCount = SLIDES.size + 1
    val lastIndex = pageCount - 1
    val pagerState = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    val selectedUrls = remember {
        mutableStateListOf<String>().apply { addAll(POPULAR_FEEDS.filter { it.recommended }.map { it.url }) }
    }
    var linksWanted by remember { mutableStateOf(true) }
    val onFeedPage = pagerState.currentPage == lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(nd.paper)
            .systemBarsPadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterEnd) {
            if (!onFeedPage) {
                Text(
                    "Skip",
                    Modifier
                        .clickable { onDone(emptyList(), linksWanted) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = nd.brandText,
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            if (page < SLIDES.size) {
                FeatureSlide(SLIDES[page])
            } else {
                FeedPicker(
                    selectedUrls = selectedUrls,
                    linksWanted = linksWanted,
                    onToggleLinks = { linksWanted = !linksWanted },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pageCount) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == pagerState.currentPage) 7.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (index == pagerState.currentPage) nd.ink else nd.border)
                )
            }
        }

        // The primary button is ink, not amber — pressing Next is not the same as
        // owning something.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(nd.radiusCard))
                .background(nd.ink)
                .clickable {
                    if (onFeedPage) {
                        onDone(
                            POPULAR_FEEDS.filter { it.url in selectedUrls },
                            linksWanted,
                        )
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (onFeedPage) "Get started" else "Next",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = nd.paper,
            )
        }
    }
}

@Composable
private fun FeatureSlide(slide: Slide) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(nd.chipBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(slide.icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = nd.ink)
        }
        Spacer(Modifier.height(32.dp))
        Text(
            slide.title,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            color = nd.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            slide.body,
            fontSize = 15.sp,
            color = nd.muted,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FeedPicker(
    selectedUrls: MutableList<String>,
    linksWanted: Boolean,
    onToggleLinks: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(nd.chipBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.RssFeed, contentDescription = null, modifier = Modifier.size(34.dp), tint = nd.ink)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Add popular feeds",
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = nd.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a few to start with. You can add or remove sources anytime in Settings.",
            fontSize = 14.sp,
            color = nd.muted,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        POPULAR_FEEDS.forEach { feed ->
            val checked = feed.url in selectedUrls
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .clickable {
                        if (checked) selectedUrls.remove(feed.url) else selectedUrls.add(feed.url)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(feed.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink, maxLines = 1)
                    Text(
                        "${feed.group} · ${host(feed.url)}",
                        fontSize = 12.sp,
                        color = nd.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (checked) "Selected" else "Not selected",
                    modifier = Modifier.size(22.dp),
                    tint = if (checked) nd.ink else nd.faint,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = nd.border)
        Spacer(Modifier.height(6.dp))
        SectionToggle(
            title = "Links",
            body = "A Links tab for reference pages that aren't feeds — country hubs, dashboards.",
            checked = linksWanted,
            onToggle = onToggleLinks,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionToggle(title: String, body: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = nd.ink)
            Text(body, fontSize = 12.sp, color = nd.faint, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        NdSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun host(url: String): String =
    url.removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')
