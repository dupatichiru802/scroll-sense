package com.dupati.scrollsense

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** [topicLabel] is which feed this came from (e.g. "US", "World", "Business") - shown
 *  alongside the headline so it's clear which topic/region a story belongs to when
 *  multiple topics are mixed together on the bubble or in the News screen's list. */
data class NewsItem(val title: String, val link: String, val topicLabel: String)

data class NewsTopic(val id: String, val labelResId: Int, val feedUrl: String)

/** BBC News' public RSS feeds, one per topic - no API key, no account, no cost. */
object NewsTopics {
    const val TOP = "top"
    const val WORLD = "world"
    const val UK = "uk"
    const val INDIA = "india"
    const val US = "us"
    const val BUSINESS = "business"
    const val POLITICS = "politics"
    const val HEALTH = "health"
    const val SCIENCE = "science"
    const val TECHNOLOGY = "technology"
    const val ENTERTAINMENT = "entertainment"

    val ALL = listOf(
        NewsTopic(TOP, R.string.news_topic_top, "https://feeds.bbci.co.uk/news/rss.xml"),
        NewsTopic(WORLD, R.string.news_topic_world, "https://feeds.bbci.co.uk/news/world/rss.xml"),
        NewsTopic(UK, R.string.news_topic_uk, "https://feeds.bbci.co.uk/news/uk/rss.xml"),
        NewsTopic(INDIA, R.string.news_topic_india, "https://feeds.bbci.co.uk/news/world/asia/india/rss.xml"),
        NewsTopic(US, R.string.news_topic_us, "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml"),
        NewsTopic(BUSINESS, R.string.news_topic_business, "https://feeds.bbci.co.uk/news/business/rss.xml"),
        NewsTopic(POLITICS, R.string.news_topic_politics, "https://feeds.bbci.co.uk/news/politics/rss.xml"),
        NewsTopic(HEALTH, R.string.news_topic_health, "https://feeds.bbci.co.uk/news/health/rss.xml"),
        NewsTopic(SCIENCE, R.string.news_topic_science, "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml"),
        NewsTopic(TECHNOLOGY, R.string.news_topic_technology, "https://feeds.bbci.co.uk/news/technology/rss.xml"),
        NewsTopic(ENTERTAINMENT, R.string.news_topic_entertainment, "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml")
    )

    /** Resolves selected topic ids to (display label, feed URL) pairs, ready to pass
     *  to NewsFetcher.fetchHeadlines - resolving the label here (rather than inside
     *  NewsFetcher) keeps that object free of any Context/resources dependency. */
    fun feedsFor(context: Context, topicIds: Set<String>): List<Pair<String, String>> =
        ALL.filter { it.id in topicIds }.map { context.getString(it.labelResId) to it.feedUrl }
}

/** Fetches and parses BBC News RSS feeds. Network calls here must be made off the main
 *  thread by the caller. */
object NewsFetcher {
    private const val TIMEOUT_MILLIS = 8000

    // Keeps the pool of headlines (bubble pulse rotation, and the News screen's list)
    // to a manageable, recent handful rather than however many a multi-topic/multi-
    // stock selection could add up to.
    private const val MAX_HEADLINES = 10

    /** Fetches every (label, feedUrl) pair in [feeds] and interleaves their results
     *  round-robin (rather than concatenating topic by topic) so a multi-topic
     *  selection doesn't read as one topic's full list followed by another's. A feed
     *  that fails to load is simply left out rather than failing the whole batch.
     *  Stories that appear in more than one selected topic (a story can be both "Top
     *  Stories" and "World", for instance) are de-duplicated by article link, keeping
     *  the first (and so its topic label) it was seen under. Stops once MAX_HEADLINES
     *  are collected. */
    fun fetchHeadlines(feeds: List<Pair<String, String>>): List<NewsItem> {
        if (feeds.isEmpty()) return emptyList()
        val perFeed = feeds.map { (label, url) -> label to fetchFeed(url) }
        val seenLinks = mutableSetOf<String>()
        val result = mutableListOf<NewsItem>()
        var index = 0
        while (result.size < MAX_HEADLINES) {
            var addedAny = false
            for ((label, headlines) in perFeed) {
                if (result.size >= MAX_HEADLINES) break
                if (index < headlines.size) {
                    addedAny = true
                    val (title, link) = headlines[index]
                    if (seenLinks.add(link)) result.add(NewsItem(title, link, label))
                }
            }
            if (!addedAny) break
            index++
        }
        return result
    }

    private fun fetchFeed(url: String): List<Pair<String, String>> {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                requestMethod = "GET"
            }
            try {
                connection.inputStream.use { parseRss(it) }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Raw (title, link) pairs, untagged with a topic - fetchHeadlines() is what
     *  attaches the topic label, since one feed URL can be reused (see StockFetcher)
     *  with a different, caller-chosen label. */
    private fun parseRss(stream: InputStream): List<Pair<String, String>> {
        val items = mutableListOf<Pair<String, String>>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var title: String? = null
        var link: String? = null
        var inItem = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "item" -> {
                        inItem = true
                        title = null
                        link = null
                    }
                    "title" -> if (inItem) title = parser.nextText()
                    "link" -> if (inItem) link = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name == "item") {
                    inItem = false
                    val t = title?.trim()
                    val l = link?.trim()
                    if (!t.isNullOrBlank() && !l.isNullOrBlank()) {
                        items.add(t to l)
                    }
                }
            }
            eventType = parser.next()
        }
        return items
    }
}
