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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity

class SetupActivity : FragmentActivity() {
    private val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/e/"
    private val SHEET_URL_SUFFIX = "/pub?gid=0&single=true&output=csv"
    private var progressDialog: AlertDialog? = null

    private lateinit var sheetLinkInput: EditText
    private lateinit var defaultBrowserToggle: Button
    private lateinit var closeButton: Button
    private lateinit var setupTitle: TextView
    private lateinit var setupSubtitle: TextView

    /**
     * True when no CSV is configured yet (first launch / re-init).
     * False when opened from main Settings with an existing source.
     */
    private var isInitMode = false

    /** Double Back/Exit confirmation when quitting with no CSV. */
    private var lastExitAttemptMs = 0L

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w("SetupActivity", "Failed to take persistable URI permission", e)
                }
                sheetLinkInput.setText(uri.toString())
                showToast("Selected CSV: " + uri.lastPathSegment)
                triggerApplySource()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_setup)

        sheetLinkInput = findViewById(R.id.sheet_link_input)
        closeButton = findViewById(R.id.cancel_button)
        setupTitle = findViewById(R.id.setup_title)
        setupSubtitle = findViewById(R.id.setup_subtitle)
        val pasteButton = findViewById<Button>(R.id.paste_button)
        val selectCsvButton = findViewById<Button>(R.id.select_csv_button)
        val historyButton = findViewById<Button>(R.id.history_button)
        val purgeFreqButton = findViewById<Button>(R.id.purge_freq_button)
        val resetAllButton = findViewById<Button>(R.id.reset_all_button)
        val sourceDetailsToggle = findViewById<Button>(R.id.source_details_toggle)
        defaultBrowserToggle = findViewById(R.id.default_browser_toggle)
        val applyButton = findViewById<Button>(R.id.apply_button)
        val aboutButton = findViewById<Button>(R.id.about_button)

        applyButton.setOnClickListener {
            triggerApplySource()
        }

        // Define update for browser early so all listeners can see it
        fun updateBrowserToggleText() {
            val current = Utils.getDefaultBrowserPage(this)
            val engine = if (current.contains("google", ignoreCase = true)) "google" else "duckduckgo"
            val display = Utils.getSearchEngineDisplayName(engine)
            defaultBrowserToggle.text = "Default Search: $display"
        }
        updateBrowserToggleText()

        fun updateDetailsToggleText() {
            val enabled = Utils.isShowSourceDetailsEnabled(this)
            sourceDetailsToggle.text = "Show Source Details: ${if (enabled) "ON" else "OFF"}"
        }
        updateDetailsToggleText()

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val currentSheetLink = sharedPrefs.getString("sheet_link", null)

        // Prefer explicit intent flag; fall back to prefs (no CSV → init).
        isInitMode = if (intent.hasExtra(EXTRA_INIT_MODE)) {
            intent.getBooleanExtra(EXTRA_INIT_MODE, currentSheetLink.isNullOrBlank())
        } else {
            currentSheetLink.isNullOrBlank()
        }

        currentSheetLink?.let {
            sheetLinkInput.setText(it)
        }
        closeButton.visibility = View.VISIBLE
        applyInitOrSettingsChrome()

        // Auto-apply on keyboard "Done" or "Enter" actions (validates)
        sheetLinkInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || 
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                triggerApplySource()
                true
            } else {
                false
            }
        }

        sheetLinkInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_UP) {
                triggerApplySource()
                true
            } else {
                false
            }
        }

        // Disable autofill to avoid system clipboard access that can cause "not in focus" denials
        // during focus transitions or when activity not considered foreground.
        sheetLinkInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            sheetLinkInput.setAutofillHints()
        }

        // Paste Button
        pasteButton.setOnClickListener {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()?.trim()
                    if (!text.isNullOrBlank()) {
                        sheetLinkInput.setText(text)
                        showToast("Pasted from clipboard")
                        triggerApplySource() // Auto-apply + validate on paste
                    } else {
                        showToast("Clipboard is empty or does not contain text")
                    }
                } else {
                    showToast("Clipboard is empty")
                }
            } catch (e: SecurityException) {
                showToast("Clipboard access denied (app not in focus)")
            } catch (e: Exception) {
                showToast("Failed to access clipboard")
            }
        }

        // Select CSV (File Picker)
        selectCsvButton.setOnClickListener {
            openFilePicker()
        }

        // History / Recent Sources Button
        historyButton.setOnClickListener {
            showHistoryDialog(sheetLinkInput)
        }

        // Reset Watch Frequency Button
        purgeFreqButton.setOnClickListener {
            showPurgeConfirmationDialog()
        }

        resetAllButton.setOnClickListener {
            // Reset all settings to defaults
            Utils.setShowSourceDetailsEnabled(this, false)
            Utils.setDefaultBrowserPage(this, "https://duckduckgo.com/")
            updateDetailsToggleText()
            updateBrowserToggleText()
            showToast("All settings reset to default")
        }

        // Source Details Toggle Button
        sourceDetailsToggle.setOnClickListener {
            val enabled = Utils.isShowSourceDetailsEnabled(this)
            Utils.setShowSourceDetailsEnabled(this, !enabled)
            updateDetailsToggleText()
            showToast("Show source details set to ${if (!enabled) "ON" else "OFF"}")
        }

        defaultBrowserToggle.setOnClickListener {
            val current = Utils.getDefaultBrowserPage(this)
            val newPage = if (current.contains("google", ignoreCase = true)) "https://duckduckgo.com/" else "https://www.google.com/"
            Utils.setDefaultBrowserPage(this, newPage)
            updateBrowserToggleText()
            val engine = if (newPage.contains("google", ignoreCase = true)) "google" else "duckduckgo"
            val display = Utils.getSearchEngineDisplayName(engine)
            showToast("Default search set to $display")
        }

        // About button - click to show dialog
        aboutButton.setOnClickListener {
            showAboutDialog()
        }

        // Close / Exit / Back: same path (save/validate, return to main, or confirm quit if no CSV).
        closeButton.setOnClickListener {
            attemptAutoSaveAndClose()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                attemptAutoSaveAndClose()
            }
        })
    }

    /** Title, subtitle, and primary exit label for init vs settings. */
    private fun applyInitOrSettingsChrome() {
        if (isInitMode) {
            setupTitle.text = "Setup"
            setupSubtitle.text = "Add your CSV source to get started"
            closeButton.text = "Exit app"
        } else {
            setupTitle.text = "Settings"
            setupSubtitle.text = "Change your CSV source and preferences"
            closeButton.text = "Back to main"
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
            filePickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("SetupActivity", "Failed to start file picker", e)
            showToast("Failed to open file picker: ${e.message}")
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
                triggerApplySource() // Auto-apply + validate on select
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

    private fun showAboutDialog() {
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val version = packageInfo?.versionName ?: "1.2.0"
        AlertDialog.Builder(this)
            .setTitle("About")
            .setMessage("Version: $version\nDeveloper: MingMingHomeWork")
            .setPositiveButton("OK", null)
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

    private fun triggerApplySource() {
        val input = sheetLinkInput.text.toString().trim()
        if (input.isBlank()) {
            showToast("Please enter a CSV URL or ID first")
            return
        }

        val candidates = determineFinalSheetLinks(input)
        if (candidates.isEmpty()) {
            showToast("Invalid CSV source format")
            return
        }

        // Start sequential validation (shows error dialog on failure)
        validateCandidatesAndSave(candidates, 0, input, autoClose = false)
    }

    private fun attemptAutoSaveAndClose() {
        val input = sheetLinkInput.text.toString().trim()
        val currentSaved = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            .getString("sheet_link", null)

        if (input.isBlank()) {
            if (currentSaved.isNullOrBlank()) {
                // Init / no source: require double Back or Exit to leave the app.
                requestQuitWithNoCsv()
            } else {
                navigateToMainActivity()
            }
            return
        }

        if (input == currentSaved) {
            navigateToMainActivity()
            return
        }

        // Auto save + validate changed source on close
        val candidates = determineFinalSheetLinks(input)
        if (candidates.isEmpty()) {
            if (currentSaved.isNullOrBlank()) {
                showToast("Invalid CSV source format. Enter a valid URL or ID.")
                return
            }
            showToast("Invalid CSV source format - using previous.")
            navigateToMainActivity()
            return
        }

        validateCandidatesAndSave(candidates, 0, input, autoClose = true)
    }

    /**
     * When no CSV is configured, first Back/Exit prompts; second within the window quits.
     * Avoids accidental app exit on TV remotes.
     */
    private fun requestQuitWithNoCsv() {
        val now = System.currentTimeMillis()
        if (now - lastExitAttemptMs <= EXIT_CONFIRM_WINDOW_MS) {
            finishAffinity()
            return
        }
        lastExitAttemptMs = now
        showToast("Add a CSV source, or press Back / Exit again to leave")
        // Keep focus on exit control for a quick second press on TV.
        closeButton.requestFocus()
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

    private fun validateCandidatesAndSave(candidates: List<String>, index: Int, originalInput: String, autoClose: Boolean = false) {
        if (index >= candidates.size) {
            dismissLoading()
            if (autoClose) {
                val hasPrevious = !getSharedPreferences("AppPrefs", MODE_PRIVATE)
                    .getString("sheet_link", null).isNullOrBlank()
                if (hasPrevious) {
                    showToast("Failed to load CSV from new source. Keeping previous source.")
                    navigateToMainActivity()
                } else {
                    // Init mode: stay here so the user can fix the source.
                    showToast("Failed to load CSV. Check the URL or ID and try again.")
                }
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Validation Failed")
                    .setMessage("Failed to load CSV: No working source link could be resolved. Please verify your URL or ID and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            return
        }

        val currentCandidate = candidates[index]
        showLoading("Verifying source (${index + 1}/${candidates.size})...\n$currentCandidate")

        Utils.fetchSheetData(this, currentCandidate) { videos, error ->
            if (error == null && videos.isNotEmpty()) {
                dismissLoading()

                // If the candidate was a short URL (tinyurl etc), fetchSheetData has already
                // resolved it and overwritten the "sheet_link" pref with the final long URL.
                // Prefer the resolved value so we store the direct link.
                val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                val linkToSave = if (Utils.isShortUrl(currentCandidate)) {
                    prefs.getString("sheet_link", currentCandidate) ?: currentCandidate
                } else {
                    currentCandidate
                }

                saveSheetLink(linkToSave)
                addCsvToHistory(linkToSave)
                showToast("Sheet loaded successfully with ${videos.size} videos.")
                navigateToMainActivity()
            } else {
                Log.w("SetupActivity", "Candidate failed: $currentCandidate, error: $error")
                // Try the next candidate URL on the main thread/recursive call
                runOnUiThread {
                    validateCandidatesAndSave(candidates, index + 1, originalInput, autoClose)
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
            progressDialog?.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                // Remove any default dialog border/background/frame around the custom CardView
                decorView?.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }
            val content = progressDialog?.findViewById<android.view.View>(android.R.id.content)
            content?.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            (content?.parent as? android.view.View)?.background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
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

    companion object {
        /** true = first-run / no CSV init; false = settings from main. */
        const val EXTRA_INIT_MODE = "extra_init_mode"

        private const val EXIT_CONFIRM_WINDOW_MS = 2500L

        fun createIntent(context: Context, initMode: Boolean): Intent {
            return Intent(context, SetupActivity::class.java).apply {
                putExtra(EXTRA_INIT_MODE, initMode)
            }
        }
    }
}