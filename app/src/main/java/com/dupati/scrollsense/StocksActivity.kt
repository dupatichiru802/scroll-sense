package com.dupati.scrollsense

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/** Lets the user track stock ticker symbols, see their live-ish price and day change,
 *  and drill into stock-specific headlines for each one. Prices and news both come
 *  from free, keyless, unofficial endpoints - see StockFetcher. */
class StocksActivity : AppCompatActivity() {

    private lateinit var symbolInput: EditText
    private lateinit var searchResultsContainer: LinearLayout
    private lateinit var stocksContainer: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView

    // Per-stock expand/collapse state, all in-memory only (reset each time this screen
    // is reopened, same as the old single-level news toggle this replaces) - keyed by
    // symbol so rebuilding the whole list (after an add/remove) doesn't collapse
    // everything else the user had open.
    private val expandedStocks = mutableSetOf<String>()
    // Prices has no lazy fetch (the data's already in hand), so it defaults open:
    // tracked as which symbols are collapsed, rather than which are expanded.
    private val collapsedPriceSections = mutableSetOf<String>()
    private val expandedNewsSections = mutableSetOf<String>()

    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    // Bumped on every new search so a slow, superseded lookup can't overwrite the
    // results of one the user typed after it.
    private var searchGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(bubbleColorThemeOverlayRes(TimerPrefs.getBubbleColor(this)), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stocks)

        symbolInput = findViewById(R.id.stockSymbolInput)
        searchResultsContainer = findViewById(R.id.stockSearchResultsContainer)
        stocksContainer = findViewById(R.id.stocksContainer)
        loadingIndicator = findViewById(R.id.stocksLoadingIndicator)
        emptyText = findViewById(R.id.stocksEmptyText)

        findViewById<View>(R.id.addStockButton).setOnClickListener { addStockBySymbol(symbolInput.text.toString()) }

        symbolInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onSearchTextChanged(s?.toString().orEmpty())
            }
        })

        loadStocks()
    }

    /** Debounces as-you-type search: waits for a brief pause in typing before actually
     *  querying, so every keystroke doesn't fire its own network request. */
    private fun onSearchTextChanged(text: String) {
        pendingSearch?.let { searchHandler.removeCallbacks(it) }
        val query = text.trim()
        if (query.isEmpty()) {
            searchResultsContainer.removeAllViews()
            searchResultsContainer.visibility = View.GONE
            return
        }
        val runnable = Runnable { performSearch(query) }
        pendingSearch = runnable
        searchHandler.postDelayed(runnable, 350)
    }

    private fun performSearch(query: String) {
        val generation = ++searchGeneration
        Thread {
            val results = StockFetcher.searchSymbols(query)
            runOnUiThread {
                if (generation != searchGeneration) return@runOnUiThread
                renderSearchResults(results)
            }
        }.start()
    }

    private fun renderSearchResults(results: List<StockSearchResult>) {
        searchResultsContainer.removeAllViews()
        searchResultsContainer.visibility = View.VISIBLE

        if (results.isEmpty()) {
            searchResultsContainer.addView(TextView(this).apply {
                text = getString(R.string.stocks_search_empty)
                setTextColor(ContextCompat.getColor(context, R.color.labelText))
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
            return
        }

        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        results.forEachIndexed { index, result ->
            if (index > 0) searchResultsContainer.addView(buildDivider())
            searchResultsContainer.addView(TextView(this).apply {
                text = "${result.symbol} — ${result.name}"
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.labelText))
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                isFocusable = true
                background = ContextCompat.getDrawable(this@StocksActivity, outValue.resourceId)
                setOnClickListener {
                    searchResultsContainer.removeAllViews()
                    searchResultsContainer.visibility = View.GONE
                    symbolInput.setText("")
                    addStockBySymbol(result.symbol)
                }
            })
        }
    }

    private fun addStockBySymbol(rawSymbol: String) {
        val symbol = rawSymbol.trim().uppercase()
        if (symbol.isEmpty()) return

        val watched = TimerPrefs.getWatchedStocks(this)
        if (symbol in watched) {
            Toast.makeText(this, R.string.stocks_already_tracked, Toast.LENGTH_SHORT).show()
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        Thread {
            val quote = StockFetcher.fetchQuote(symbol)
            runOnUiThread {
                loadingIndicator.visibility = View.GONE
                if (quote == null) {
                    Toast.makeText(this, R.string.stocks_invalid_symbol, Toast.LENGTH_SHORT).show()
                } else {
                    TimerPrefs.setWatchedStocks(this, watched + symbol)
                    symbolInput.setText("")
                    loadStocks()
                }
            }
        }.start()
    }

    private fun removeStock(symbol: String) {
        TimerPrefs.setWatchedStocks(this, TimerPrefs.getWatchedStocks(this) - symbol)
        expandedStocks.remove(symbol)
        collapsedPriceSections.remove(symbol)
        expandedNewsSections.remove(symbol)
        loadStocks()
    }

    private fun loadStocks() {
        val symbols = TimerPrefs.getWatchedStocks(this)
        stocksContainer.removeAllViews()

        if (symbols.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            return
        }
        emptyText.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE

        Thread {
            val quotes = symbols.associateWith { StockFetcher.fetchQuote(it) }
            runOnUiThread {
                loadingIndicator.visibility = View.GONE
                symbols.forEachIndexed { index, symbol ->
                    if (index > 0) stocksContainer.addView(buildDivider())
                    stocksContainer.addView(buildStockBlock(symbol, quotes[symbol]))
                }
            }
        }.start()
    }

    /** One tracked stock: a header row (name, current price, delete) that expands, on
     *  tap, into two of its own mini collapsible sections - Prices (day/week change,
     *  already in hand) and News (that stock's headlines, fetched lazily the first
     *  time it's opened, same pattern DailyReportActivity uses for a report row's
     *  session list). */
    private fun buildStockBlock(symbol: String, quote: StockQuote?): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        val nameText = TextView(this).apply {
            text = quote?.name?.let { "$symbol — $it" } ?: symbol
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.labelText))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val priceText = if (quote != null) {
            TextView(this).apply {
                text = formatPrice(quote)
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.labelText))
                setPadding(dp(10), 0, 0, 0)
            }
        } else {
            TextView(this).apply {
                text = getString(R.string.stocks_invalid_symbol)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.stockNegative))
                setPadding(dp(10), 0, 0, 0)
            }
        }

        val removeButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            imageTintList = ContextCompat.getColorStateList(this@StocksActivity, R.color.labelText)
            contentDescription = getString(R.string.stocks_remove_button)
            val size = dp(22)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(14) }
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@StocksActivity, outValue.resourceId)
            setOnClickListener { removeStock(symbol) }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            addView(nameText)
            addView(priceText)
            addView(removeButton)
        }

        wrapper.addView(headerRow)

        // A symbol that failed to resolve to a quote has nothing meaningful to expand
        // into - just the header row (with its inline error) and a delete button.
        if (quote == null) return wrapper

        val chevron = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) }
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = ContextCompat.getColorStateList(this@StocksActivity, R.color.labelText)
        }
        headerRow.addView(chevron)
        headerRow.isClickable = true
        headerRow.isFocusable = true
        headerRow.background = ContextCompat.getDrawable(this@StocksActivity, outValue.resourceId)

        val detailContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        setupCollapsibleSection(
            header = headerRow,
            content = detailContainer,
            chevron = chevron,
            getExpanded = { symbol in expandedStocks },
            setExpanded = { expanded -> if (expanded) expandedStocks.add(symbol) else expandedStocks.remove(symbol) }
        )

        val pricesHeader = buildSubSectionHeader(getString(R.string.stocks_prices_label))
        val pricesContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(10))
            addView(buildChangeLine(getString(R.string.stocks_today_label), quote.change, quote.changePercent))
            quote.weekChangePercent?.let { weekPercent ->
                addView(buildChangeLine(getString(R.string.stocks_week_label), quote.weekChange!!, weekPercent))
            }
        }
        setupCollapsibleSection(
            header = pricesHeader.row,
            content = pricesContent,
            chevron = pricesHeader.chevron,
            getExpanded = { symbol !in collapsedPriceSections },
            setExpanded = { expanded ->
                if (expanded) collapsedPriceSections.remove(symbol) else collapsedPriceSections.add(symbol)
            }
        )

        val newsHeader = buildSubSectionHeader(getString(R.string.stocks_news_label))
        val newsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(4))
        }
        setupNewsSection(symbol, newsHeader.row, newsContent, newsHeader.chevron)

        detailContainer.addView(pricesHeader.row)
        detailContainer.addView(pricesContent)
        detailContainer.addView(buildDivider())
        detailContainer.addView(newsHeader.row)
        detailContainer.addView(newsContent)
        wrapper.addView(detailContainer)
        return wrapper
    }

    private class SubSectionHeader(val row: LinearLayout, val chevron: ImageView)

    /** A small "Prices"/"News"-style mini header, same chevron-and-label shape as the
     *  app's other collapsible sections, just lighter-weight for nesting inside a
     *  single stock's expanded block. */
    private fun buildSubSectionHeader(label: String): SubSectionHeader {
        val chevron = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = ContextCompat.getColorStateList(this@StocksActivity, R.color.labelText)
        }
        val labelText = TextView(this).apply {
            text = label
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.sectionHeader))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(8), dp(4), dp(8))
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            background = ContextCompat.getDrawable(this@StocksActivity, outValue.resourceId)
            addView(labelText)
            addView(chevron)
        }
        return SubSectionHeader(row, chevron)
    }

    /** Same shape as setupCollapsibleSection, but with a lazy fetch on first expand -
     *  kept separate since that shared helper has no concept of "load content the
     *  first time this opens". */
    private fun setupNewsSection(symbol: String, header: View, content: LinearLayout, chevron: ImageView) {
        fun apply(expanded: Boolean, animate: Boolean) {
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            val targetRotation = if (expanded) 180f else 0f
            if (animate) chevron.animate().rotation(targetRotation).setDuration(150).start()
            else chevron.rotation = targetRotation
            if (expanded && content.childCount == 0) loadStockNewsInto(symbol, content)
        }

        apply(symbol in expandedNewsSections, animate = false)
        header.setOnClickListener {
            val expanded = symbol !in expandedNewsSections
            if (expanded) expandedNewsSections.add(symbol) else expandedNewsSections.remove(symbol)
            apply(expanded, animate = true)
        }
    }

    private fun loadStockNewsInto(symbol: String, container: LinearLayout) {
        container.addView(ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        Thread {
            val items = StockFetcher.fetchStockNews(symbol)
            runOnUiThread {
                container.removeAllViews()
                if (items.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = getString(R.string.stocks_news_empty)
                        setTextColor(ContextCompat.getColor(context, R.color.labelText))
                        textSize = 13f
                        setPadding(0, dp(4), 0, dp(4))
                    })
                } else {
                    // Already capped to a manageable handful by NewsFetcher itself.
                    items.forEachIndexed { index, item ->
                        if (index > 0) container.addView(buildDivider())
                        container.addView(buildHeadlineRow(item))
                    }
                }
            }
        }.start()
    }

    private fun buildHeadlineRow(item: NewsItem): View {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return TextView(this).apply {
            text = item.title
            textSize = 14f
            setTextColor(themeAccentColor())
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@StocksActivity, outValue.resourceId)
            setOnClickListener { openArticle(item.link) }
        }
    }

    private fun buildDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(ContextCompat.getColor(context, R.color.cardStroke))
    }

    private fun formatPrice(quote: StockQuote): String {
        val symbolPrefix = when (quote.currency) {
            "USD" -> "$"
            "GBP" -> "£"
            "EUR" -> "€"
            "INR" -> "₹"
            "JPY" -> "¥"
            else -> ""
        }
        val formatted = String.format(Locale.US, "%.2f", quote.price)
        return if (symbolPrefix.isNotEmpty()) "$symbolPrefix$formatted" else "${quote.currency.orEmpty()} $formatted".trim()
    }

    /** One "Today +1.61 (+0.71%)"-style line, coloured green/red by sign. Shared by the
     *  day-change and week-change rows so they read consistently. */
    private fun buildChangeLine(label: String, change: Double, changePercent: Double): View {
        val sign = if (change >= 0) "+" else ""
        return TextView(this).apply {
            text = String.format(Locale.US, "%s %s%.2f (%s%.2f%%)", label, sign, change, sign, changePercent)
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(context, if (change >= 0) R.color.stockPositive else R.color.stockNegative)
            )
        }
    }

    private fun openArticle(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            // No browser available - nothing else sensible to do here.
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
