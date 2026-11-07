package com.kasun.newslab.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Small robots.txt checker (in the spirit of RFC 9309) so full-text extraction
 * only fetches pages the site permits. Each host's robots.txt is fetched once and
 * cached for the session. It fails open — a missing (4xx) or unreachable robots.txt
 * is treated as "allowed", since a personal reader shouldn't break on a transient
 * error — but an explicit Disallow for our crawler is honored.
 */
internal object Robots {

    /** Our crawler's product token, matched against robots `User-agent` groups. */
    private const val TOKEN = "newslab"
    private const val MAX_BYTES = 512L * 1024L

    private val cache = ConcurrentHashMap<String, Rules>()

    /** True if our crawler may fetch [url] under the host's robots.txt. */
    suspend fun isAllowed(url: HttpUrl): Boolean {
        val origin = "${url.scheme}://${url.host}:${url.port}"
        val rules = cache[origin] ?: fetchRules(url).also { cache[origin] = it }
        return rules.allows(url.encodedPath.ifEmpty { "/" })
    }

    private suspend fun fetchRules(url: HttpUrl): Rules = withContext(Dispatchers.IO) {
        val robotsUrl = HttpUrl.Builder()
            .scheme(url.scheme)
            .host(url.host)
            .port(url.port)
            .encodedPath("/robots.txt")
            .build()
        try {
            val request = Request.Builder().url(robotsUrl).header("User-Agent", Http.USER_AGENT).build()
            Http.client.newCall(request).execute().use { response ->
                when {
                    // No robots.txt (4xx) or a server error (5xx): treat as unrestricted.
                    !response.isSuccessful -> ALLOW_ALL
                    else -> parse(response.peekBody(MAX_BYTES).string())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ALLOW_ALL
        }
    }

    internal fun parse(content: String): Rules {
        val groups = mutableListOf<Group>()
        var current: Group? = null
        var lastWasAgent = false
        for (raw in content.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val field = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (field) {
                "user-agent" -> {
                    // Consecutive user-agent lines share the rules that follow them.
                    if (!lastWasAgent || current == null) {
                        current = Group().also { groups.add(it) }
                    }
                    // A blank value names nobody (it used to count as a prefix of every token).
                    if (value.isNotEmpty()) current!!.agents.add(value.lowercase())
                    lastWasAgent = true
                }
                "allow" -> {
                    if (value.isNotEmpty()) current?.rules?.add(Rule(allow = true, pattern = value))
                    lastWasAgent = false
                }
                "disallow" -> {
                    if (value.isNotEmpty()) current?.rules?.add(Rule(allow = false, pattern = value))
                    lastWasAgent = false
                }
                else -> lastWasAgent = false // sitemap, crawl-delay, ... ignored
            }
        }

        // RFC 9309: the groups naming our product token exactly (case-insensitively)
        // apply, and several such groups are combined; otherwise the wildcard groups.
        // An agent that merely shares a prefix with ours ("news") is someone else.
        val ours = groups.filter { TOKEN in it.agents }
        val chosen = if (ours.isNotEmpty()) ours else groups.filter { "*" in it.agents }
        if (chosen.isEmpty()) return ALLOW_ALL
        return Rules(chosen.flatMap { it.rules })
    }

    /** A `robots.txt` group: one or more user-agents and the Allow/Disallow rules under them. */
    private class Group {
        val agents = mutableListOf<String>()
        val rules = mutableListOf<Rule>()
    }

    internal data class Rule(val allow: Boolean, val pattern: String)

    internal class Rules(private val rules: List<Rule>) {
        /** Longest-match wins; on an equal-length tie, Allow beats Disallow. */
        fun allows(path: String): Boolean {
            var best: Rule? = null
            for (rule in rules) {
                if (!matches(rule.pattern, path)) continue
                val bestLen = best?.pattern?.length ?: -1
                if (rule.pattern.length > bestLen ||
                    (rule.pattern.length == bestLen && rule.allow && best?.allow == false)
                ) {
                    best = rule
                }
            }
            return best?.allow ?: true
        }
    }

    private val ALLOW_ALL = Rules(emptyList())

    /** Path-prefix match supporting `*` wildcards and a trailing `$` end-anchor. */
    private fun matches(pattern: String, path: String): Boolean {
        val endAnchor = pattern.endsWith("$")
        val core = if (endAnchor) pattern.dropLast(1) else pattern
        val regex = buildString {
            append('^')
            for (c in core) when (c) {
                '*' -> append(".*")
                '.', '\\', '+', '?', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> {
                    append('\\'); append(c)
                }
                else -> append(c)
            }
            if (endAnchor) append('$')
        }
        return runCatching { Regex(regex).containsMatchIn(path) }.getOrDefault(false)
    }
}
