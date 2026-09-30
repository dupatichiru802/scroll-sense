package com.dupati.scrollsense

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class StockSearchResult(val symbol: String, val name: String, val exchange: String?)

data class StockQuote(
    val symbol: String,
    val name: String?,
    val currency: String?,
    val price: Double,
    val previousClose: Double,
    // Null when a week of history wasn't available (e.g. a symbol newly listed this
    // week) - callers should just omit the week-change line in that case.
    val weekAgoClose: Double?
) {
    val change: Double get() = price - previousClose
    val changePercent: Double get() = if (previousClose != 0.0) (change / previousClose) * 100.0 else 0.0
    val weekChange: Double? get() = weekAgoClose?.let { price - it }
    val weekChangePercent: Double? get() = weekAgoClose
        ?.takeIf { it != 0.0 }
        ?.let { (price - it) / it * 100.0 }
}

/** Pulls stock quotes and stock-specific news from free, keyless, unofficial public
 *  endpoints - the same "no signup, no cost" spirit as NewsFetcher's BBC feeds. Being
 *  unofficial, both could change or start rate-limiting without notice; if that ever
 *  happens, a proper keyed provider (Alpha Vantage, Finnhub, etc.) would need to
 *  replace them. Network calls here must be made off the main thread by the caller. */
object StockFetcher {
    private const val TIMEOUT_MILLIS = 8000

    /** Null on any network/parse failure, or if the symbol doesn't resolve to a quote -
     *  the caller treats that as "couldn't find that symbol". */
    fun fetchQuote(symbol: String): StockQuote? {
        return try {
            val encoded = URLEncoder.encode(symbol, "UTF-8")
            // range=6d rather than 1d: it's the same request/endpoint, just asking for
            // a week of daily closes instead of one, so the week-ago close needed for
            // weekChange comes back alongside the current price/previousClose in meta -
            // no second network round trip needed.
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$encoded?interval=1d&range=6d"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                requestMethod = "GET"
                // Yahoo's unofficial endpoint 403s on the default JVM user agent.
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val result = JSONObject(body)
                .getJSONObject("chart")
                .getJSONArray("result")
                .getJSONObject(0)
            val meta = result.getJSONObject("meta")

            val price = meta.optDouble("regularMarketPrice", Double.NaN)
            val previousClose = meta.optDouble("previousClose", meta.optDouble("chartPreviousClose", Double.NaN))
            if (price.isNaN() || previousClose.isNaN()) return null

            val longName = meta.optString("longName")
            val shortName = meta.optString("shortName")
            val name = longName.takeIf { it.isNotBlank() } ?: shortName.takeIf { it.isNotBlank() }

            // Earliest non-null close in the returned window - roughly a week back,
            // since 6 calendar days of daily closes spans a weekend gap either way.
            val weekAgoClose = try {
                val closes = result.getJSONObject("indicators")
                    .getJSONArray("quote")
                    .getJSONObject(0)
                    .getJSONArray("close")
                (0 until closes.length())
                    .firstOrNull { !closes.isNull(it) }
                    ?.let { closes.getDouble(it) }
            } catch (e: Exception) {
                null
            }

            StockQuote(
                symbol = meta.optString("symbol", symbol.uppercase()).uppercase(),
                name = name,
                currency = meta.optString("currency").takeIf { it.isNotBlank() },
                price = price,
                previousClose = previousClose,
                weekAgoClose = weekAgoClose
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Looks up matching symbols for a free-text company name or partial ticker (e.g.
     *  "apple" -> AAPL), using the same Yahoo Finance domain's public search endpoint -
     *  so the user doesn't need to already know the exact ticker. Empty on any failure
     *  or if nothing matches. */
    fun searchSymbols(query: String): List<StockSearchResult> {
        if (query.isBlank()) return emptyList()
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://query2.finance.yahoo.com/v1/finance/search" +
                "?q=$encoded&quotesCount=8&newsCount=0&listsCount=0"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                readTimeout = TIMEOUT_MILLIS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val quotes = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
            val results = mutableListOf<StockSearchResult>()
            for (i in 0 until quotes.length()) {
                val quote = quotes.getJSONObject(i)
                val symbol = quote.optString("symbol").takeIf { it.isNotBlank() } ?: continue
                val longName = quote.optString("longname")
                val shortName = quote.optString("shortname")
                val name = longName.takeIf { it.isNotBlank() } ?: shortName.takeIf { it.isNotBlank() } ?: symbol
                val exchange = quote.optString("exchange").takeIf { it.isNotBlank() }
                results.add(StockSearchResult(symbol.uppercase(), name, exchange))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Google News' public RSS search, scoped to one stock symbol - exposed
     *  separately from fetchStockNews so callers combining several stocks (the bubble
     *  news pulse, alongside the topic feeds) can pass them all to one
     *  NewsFetcher.fetchHeadlines call and get them interleaved/de-duplicated
     *  together, rather than fetching and merging each stock's news themselves. */
    fun stockNewsFeedUrl(symbol: String): String {
        val query = URLEncoder.encode("$symbol stock", "UTF-8")
        return "https://news.google.com/rss/search?q=$query&hl=en-US&gl=US&ceid=US:en"
    }

    /** Stock-specific headlines via Google News' public RSS search - the exact same
     *  RSS 2.0 shape NewsFetcher already parses, so it's reused here rather than
     *  duplicated. */
    fun fetchStockNews(symbol: String): List<NewsItem> {
        return try {
            NewsFetcher.fetchHeadlines(listOf(symbol to stockNewsFeedUrl(symbol)))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
