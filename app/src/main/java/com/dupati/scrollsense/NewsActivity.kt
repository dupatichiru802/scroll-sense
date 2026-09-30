package com.dupati.scrollsense

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.checkbox.MaterialCheckBox

/** Lets the user pick which BBC News topics feed the bubble's news pulse, and shows
 *  today's headlines from those topics so there's somewhere to browse beyond whatever
 *  the bubble happens to be showing right now. */
class NewsActivity : AppCompatActivity() {

    private lateinit var topicsContainer: LinearLayout
    private lateinit var headlinesContainer: LinearLayout
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(bubbleColorThemeOverlayRes(TimerPrefs.getBubbleColor(this)), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_news)

        topicsContainer = findViewById(R.id.newsTopicsContainer)
        headlinesContainer = findViewById(R.id.newsHeadlinesContainer)
        loadingIndicator = findViewById(R.id.newsLoadingIndicator)
        emptyText = findViewById(R.id.newsEmptyText)

        setupCollapsibleSection(
            header = findViewById(R.id.newsTopicsSectionHeader),
            content = findViewById(R.id.newsTopicsSectionContent),
            chevron = findViewById<ImageView>(R.id.newsTopicsSectionChevron),
            getExpanded = { TimerPrefs.getNewsTopicsSectionExpanded(this) },
            setExpanded = { TimerPrefs.setNewsTopicsSectionExpanded(this, it) }
        )
        setupCollapsibleSection(
            header = findViewById(R.id.newsHeadlinesSectionHeader),
            content = findViewById(R.id.newsHeadlinesSectionContent),
            chevron = findViewById<ImageView>(R.id.newsHeadlinesSectionChevron),
            getExpanded = { TimerPrefs.getNewsHeadlinesSectionExpanded(this) },
            setExpanded = { TimerPrefs.setNewsHeadlinesSectionExpanded(this, it) }
        )

        buildTopicRows()
        loadHeadlines()
    }

    private fun buildTopicRows() {
        topicsContainer.removeAllViews()
        val accent = themeAccentColor()
        val selected = TimerPrefs.getNewsTopics(this).toMutableSet()
        NewsTopics.ALL.forEach { topic ->
            val row = MaterialCheckBox(this).apply {
                text = getString(topic.labelResId)
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.labelText))
                isChecked = topic.id in selected
                buttonTintList = ColorStateList.valueOf(accent)
                setPadding(dp(2), dp(10), dp(2), dp(10))
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selected.add(topic.id) else selected.remove(topic.id)
                    // Always keep at least one topic selected, so the feature never
                    // goes silent from a single unchecked box.
                    if (selected.isEmpty()) {
                        selected.add(NewsTopics.TOP)
                        TimerPrefs.setNewsTopics(this@NewsActivity, selected)
                        buildTopicRows()
                    } else {
                        TimerPrefs.setNewsTopics(this@NewsActivity, selected)
                    }
                    refreshOverlayNews()
                    loadHeadlines()
                }
            }
            topicsContainer.addView(row)
        }
    }

    /** Tells a running OverlayService to drop its cached headlines, so the bubble's
     *  next news pulse reflects the topic change right away instead of waiting out
     *  its normal refresh interval or showing stale, no-longer-selected topics. */
    private fun refreshOverlayNews() {
        if (OverlayService.isRunning) {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_REFRESH_NEWS
            })
        }
    }

    private fun loadHeadlines() {
        loadingIndicator.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        headlinesContainer.removeAllViews()

        val feeds = NewsTopics.feedsFor(this, TimerPrefs.getNewsTopics(this))
        Thread {
            val items = NewsFetcher.fetchHeadlines(feeds)
            runOnUiThread {
                loadingIndicator.visibility = View.GONE
                if (items.isEmpty()) {
                    emptyText.visibility = View.VISIBLE
                } else {
                    items.forEachIndexed { index, item ->
                        if (index > 0) headlinesContainer.addView(buildDivider())
                        headlinesContainer.addView(buildHeadlineRow(item))
                    }
                }
            }
        }.start()
    }

    private fun buildHeadlineRow(item: NewsItem): View {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(this).apply {
            text = item.topicLabel.uppercase()
            textSize = 11f
            setTextColor(themeAccentColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.04f
        })
        textColumn.addView(TextView(this).apply {
            text = item.title
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.labelText))
            setPadding(0, dp(2), 0, 0)
        })

        val chevron = TextView(this).apply {
            text = "›"
            textSize = 18f
            setTextColor(themeAccentColor())
            setPadding(dp(10), 0, 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(this@NewsActivity, outValue.resourceId)
            setOnClickListener { openArticle(item.link) }
            addView(textColumn)
            addView(chevron)
        }
    }

    private fun buildDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(ContextCompat.getColor(context, R.color.cardStroke))
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
