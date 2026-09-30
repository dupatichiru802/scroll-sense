package com.dupati.scrollsense

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Lets the user pick which message the reminder popup shows: a preset from a dropdown,
 *  or one of up to three of their own custom messages, each supporting {app}/{minutes}
 *  placeholders. Custom messages can be added and deleted; presets cannot. */
class ReminderMessageActivity : AppCompatActivity() {

    private lateinit var presetSpinner: Spinner
    private lateinit var customMessagesContainer: LinearLayout
    private lateinit var addCustomMessageButton: Button
    private lateinit var customMessageLimitNote: TextView
    private lateinit var presets: Array<String>
    private lateinit var billingManager: BillingManager
    private var suppressSpinnerCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(bubbleColorThemeOverlayRes(TimerPrefs.getBubbleColor(this)), true)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_message)

        presets = resources.getStringArray(R.array.system_reminder_messages)
        billingManager = BillingManager(this) {}
        billingManager.startConnection()

        presetSpinner = findViewById(R.id.presetSpinner)
        customMessagesContainer = findViewById(R.id.customMessagesContainer)
        addCustomMessageButton = findViewById(R.id.addCustomMessageButton)
        customMessageLimitNote = findViewById(R.id.customMessageLimitNote)

        val previews = presets.map { previewFor(it) }
        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_spinner_item, previews
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(ContextCompat.getColor(context, R.color.timerText))
                view.textSize = 15f
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(ContextCompat.getColor(context, R.color.timerText))
                view.setBackgroundColor(ContextCompat.getColor(context, R.color.cardBackground))
                view.setPadding(24, 24, 24, 24)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerCallback) return
                TimerPrefs.setSelectedMessageId(this@ReminderMessageActivity, "system_$position")
                refreshCustomMessages()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        addCustomMessageButton.setOnClickListener {
            if (!TimerPrefs.isProUnlocked(this)) {
                showCustomMessageUpsell()
                return@setOnClickListener
            }
            val newId = TimerPrefs.addCustomMessage(this)
            if (newId != null) {
                TimerPrefs.setSelectedMessageId(this, newId)
                refreshAll()
            }
        }

        refreshAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
    }

    private fun showCustomMessageUpsell() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pro_upgrade_title)
            .setMessage(R.string.pro_required_custom_message)
            .setPositiveButton(R.string.pro_dialog_upgrade_button) { _, _ ->
                billingManager.launchPurchaseFlow(this) {
                    Toast.makeText(this, R.string.pro_store_not_ready, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.pro_dialog_not_now_button, null)
            .show()
    }

    private fun refreshAll() {
        val selectedId = TimerPrefs.getSelectedMessageId(this)
        suppressSpinnerCallback = true
        if (selectedId.startsWith("system_")) {
            val index = selectedId.removePrefix("system_").toIntOrNull() ?: 0
            presetSpinner.setSelection(index)
        }
        suppressSpinnerCallback = false
        refreshCustomMessages()
    }

    private fun refreshCustomMessages() {
        val selectedId = TimerPrefs.getSelectedMessageId(this)
        val ids = TimerPrefs.getCustomMessageIds(this)

        customMessagesContainer.removeAllViews()
        ids.forEach { id -> customMessagesContainer.addView(buildCustomMessageRow(id, selectedId)) }

        val atLimit = ids.size >= TimerPrefs.MAX_CUSTOM_MESSAGES
        addCustomMessageButton.visibility = if (atLimit) View.GONE else View.VISIBLE
        customMessageLimitNote.visibility = if (atLimit) View.VISIBLE else View.GONE
    }

    private fun buildCustomMessageRow(id: String, selectedId: String): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_custom_message, customMessagesContainer, false)
        val input = row.findViewById<EditText>(R.id.customMessageInput)
        val useButton = row.findViewById<TextView>(R.id.useCustomMessage)
        val deleteButton = row.findViewById<ImageButton>(R.id.deleteCustomMessageButton)

        input.setText(TimerPrefs.getCustomMessageText(this, id))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                TimerPrefs.setCustomMessageText(this@ReminderMessageActivity, id, s?.toString().orEmpty())
            }
        })

        val isSelected = selectedId == id
        useButton.isSelected = isSelected
        useButton.text = getString(if (isSelected) R.string.in_use else R.string.use_this_message)
        useButton.setOnClickListener {
            if (input.text.isNotBlank()) {
                TimerPrefs.setSelectedMessageId(this, id)
                refreshAll()
            }
        }

        deleteButton.setOnClickListener {
            TimerPrefs.deleteCustomMessage(this, id)
            refreshAll()
        }

        return row
    }

    private fun previewFor(template: String): String =
        template.replace("{app}", "Instagram").replace("{minutes}", "15").replace("{session}", "5")
}
