package com.mmhw.csvtv

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity

class SetupActivity : FragmentActivity() {
    private val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/e/"
    private val SHEET_URL_SUFFIX = "/pub?gid=0&single=true&output=csv"
    private val SELECT_CSV_REQUEST_CODE = 42
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_setup)

        val sheetLinkInput = findViewById<EditText>(R.id.sheet_link_input)
        val saveButton = findViewById<Button>(R.id.save_button)
        val cancelButton = findViewById<Button>(R.id.cancel_button)
        val pasteButton = findViewById<Button>(R.id.paste_button)
        val selectCsvButton = findViewById<Button>(R.id.select_csv_button)
        val insertPathButton = findViewById<Button>(R.id.insert_path_button)
        val historyButton = findViewById<Button>(R.id.history_button)
        val purgeFreqButton = findViewById<Button>(R.id.purge_freq_button)

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val currentSheetLink = sharedPrefs.getString("sheet_link", null)

        currentSheetLink?.let {
            sheetLinkInput.setText(it)
            cancelButton.visibility = View.VISIBLE
        } ?: run {
            cancelButton.visibility = View.GONE
        }

        // Paste Button
        pasteButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    sheetLinkInput.setText(text)
                    showToast("Pasted from clipboard")
                } else {
                    showToast("Clipboard is empty or does not contain text")
                }
            } else {
                showToast("Clipboard is empty")
            }
        }

        // Select CSV (File Picker)
        selectCsvButton.setOnClickListener {
            openFilePicker()
        }

        // Use Default CSV
        insertPathButton.setOnClickListener {
            val defaultPath = "android.resource://${packageName}/raw/default_csv"
            sheetLinkInput.setText(defaultPath)
            showToast("Default CSV path inserted")
        }

        // History / Recent Sources Button
        historyButton.setOnClickListener {
            showHistoryDialog(sheetLinkInput)
        }

        // Reset Watch Frequency Button
        purgeFreqButton.setOnClickListener {
            showPurgeConfirmationDialog()
        }

        // Save Button
        saveButton.setOnClickListener {
            val input = sheetLinkInput.text.toString().trim()
            if (input.isBlank()) {
                showToast("Please enter a CSV URL or ID first")
                return@setOnClickListener
            }

            val candidates = determineFinalSheetLinks(input)
            if (candidates.isEmpty()) {
                showToast("Invalid CSV source format")
                return@setOnClickListener
            }

            // Start sequential validation
            validateCandidatesAndSave(candidates, 0, input)
        }

        // Cancel Button
        cancelButton.setOnClickListener {
            navigateToMainActivity()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            val mimeTypes = arrayOf("text/comma-separated-values", "text/csv", "application/csv")
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
        try {
            startActivityForResult(intent, SELECT_CSV_REQUEST_CODE)
        } catch (e: Exception) {
            Log.e("SetupActivity", "Failed to start file picker", e)
            showToast("Failed to open file picker: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SELECT_CSV_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    // Take persistable read permission
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w("SetupActivity", "Failed to take persistable URI permission", e)
                }
                findViewById<EditText>(R.id.sheet_link_input).setText(uri.toString())
                showToast("Selected CSV: " + uri.lastPathSegment)
            }
        }
    }

    private fun showHistoryDialog(input: EditText) {
        val historyList = getCsvHistory()
        if (historyList.isEmpty()) {
            showToast("No recent sources found")
            return
        }

        val items = historyList.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Recent Source")
            .setItems(items) { _, which ->
                input.setText(items[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPurgeConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Watch Frequency")
            .setMessage("Are you sure you want to reset the watch frequency of all videos? This will also clear the persistent resolution cache.")
            .setPositiveButton("Reset") { _, _ ->
                Utils.purgeWatchCount(this)
                showToast("Watch frequency and resolution cache have been reset.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getCsvHistory(): List<String> {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val historyJson = prefs.getString("sheet_link_history", "[]")
        val list = mutableListOf<String>()
        try {
            val array = org.json.JSONArray(historyJson)
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
        } catch (e: Exception) {
            Log.e("SetupActivity", "Error parsing CSV history", e)
        }
        return list
    }

    private fun addCsvToHistory(link: String) {
        if (link.isBlank() || link.startsWith("android.resource://")) return
        val currentHistory = getCsvHistory().toMutableList()
        currentHistory.remove(link)
        currentHistory.add(0, link)
        if (currentHistory.size > 10) {
            currentHistory.removeAt(currentHistory.size - 1)
        }
        val array = org.json.JSONArray()
        for (item in currentHistory) {
            array.put(item)
        }
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .edit()
            .putString("sheet_link_history", array.toString())
            .apply()
    }

    private fun determineFinalSheetLinks(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            return emptyList()
        }
        if (trimmed.startsWith("http://") || 
            trimmed.startsWith("https://") || 
            trimmed.startsWith("content://") || 
            trimmed.startsWith("android.resource://")) {
            return listOf(trimmed)
        }
        if (trimmed.startsWith("bit.ly/") || trimmed.startsWith("tinyurl.com/")) {
            return listOf("https://$trimmed")
        }
        
        // Google Sheet ID check
        if (trimmed.startsWith("2PACX-") || trimmed.length > 25) {
            return listOf("$SHEET_URL_PREFIX$trimmed$SHEET_URL_SUFFIX")
        }
        
        // Otherwise, test both candidate URLs
        return listOf(
            "https://bit.ly/$trimmed",
            "https://tinyurl.com/$trimmed"
        )
    }

    private fun validateCandidatesAndSave(candidates: List<String>, index: Int, originalInput: String) {
        if (index >= candidates.size) {
            dismissLoading()
            AlertDialog.Builder(this)
                .setTitle("Validation Failed")
                .setMessage("Failed to load CSV: No working source link could be resolved. Please verify your URL or ID and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val currentCandidate = candidates[index]
        showLoading("Verifying source (${index + 1}/${candidates.size})...\n$currentCandidate")

        Utils.fetchSheetData(this, currentCandidate) { videos, error ->
            if (error == null && videos.isNotEmpty()) {
                dismissLoading()
                saveSheetLink(currentCandidate)
                addCsvToHistory(currentCandidate)
                showToast("Sheet loaded successfully with ${videos.size} videos.")
                navigateToMainActivity()
            } else {
                Log.w("SetupActivity", "Candidate failed: $currentCandidate, error: $error")
                // Try the next candidate URL on the main thread/recursive call
                runOnUiThread {
                    validateCandidatesAndSave(candidates, index + 1, originalInput)
                }
            }
        }
    }

    private fun showLoading(message: String) {
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            dismissLoading()
            val builder = AlertDialog.Builder(this)
            val view = layoutInflater.inflate(R.layout.loading_dialog, null)
            val textView = view.findViewById<TextView>(R.id.loading_message)
            if (textView != null) {
                textView.text = message
            }
            builder.setView(view)
            builder.setCancelable(false)
            progressDialog = builder.create()
            progressDialog?.show()
            progressDialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
    }

    private fun dismissLoading() {
        runOnUiThread {
            progressDialog?.dismiss()
            progressDialog = null
        }
    }

    private fun saveSheetLink(link: String) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .edit()
            .putString("sheet_link", link)
            .apply()
    }

    private fun showToast(message: String) {
        runOnUiThread {
            if (!isFinishing) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun navigateToMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}