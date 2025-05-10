package com.mmhw.csvtv

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity

class SetupActivity : FragmentActivity() {
    private val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/e/"
    private val SHEET_URL_SUFFIX = "/pub?gid=0&single=true&output=csv"
    private lateinit var sheetLinkInput: EditText
    private val DEFAULT_FILE_PATH = "file:///sdcard/Download/"

    private val pickCsvFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    showToast("Failed to read selected CSV file")
                    return@let
                }
                val csvData = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                validateAndSaveLocalCsv(uri.toString(), csvData)
            } catch (e: Exception) {
                showToast("Error reading CSV file: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        sheetLinkInput = findViewById<EditText>(R.id.sheet_link_input)
        val saveButton = findViewById<Button>(R.id.save_button)
        val cancelButton = findViewById<Button>(R.id.cancel_button)
        val pasteButton = findViewById<Button>(R.id.paste_button)
        val selectCsvButton = findViewById<Button>(R.id.select_csv_button)
        val insertPathButton = findViewById<Button>(R.id.insert_path_button) // New button

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val currentSheetLink = sharedPrefs.getString("sheet_link", null)

        currentSheetLink?.let {
            sheetLinkInput.setText(it)
            cancelButton.visibility = View.VISIBLE
        } ?: run {
            cancelButton.visibility = View.GONE
        }

        pasteButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip() && clipboard.primaryClip?.itemCount ?: 0 > 0) {
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!clipText.isNullOrBlank()) {
                    sheetLinkInput.setText(clipText)
                    showToast("Pasted URL from clipboard")
                } else {
                    showToast("Clipboard is empty or contains invalid data")
                }
            } else {
                showToast("Clipboard is empty")
            }
        }

        selectCsvButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "text/csv"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            if (intent.resolveActivity(packageManager) != null) {
                pickCsvFile.launch("text/csv")
            } else {
                showToast("No file picker available. Please enter the CSV file path manually or install a file manager.")
            }
        }

        insertPathButton.setOnClickListener {
            sheetLinkInput.setText(DEFAULT_FILE_PATH)
            sheetLinkInput.setSelection(DEFAULT_FILE_PATH.length) // Move cursor to end
            showToast("Inserted default file path. Append your CSV filename.")
        }

        saveButton.setOnClickListener {
            val input = sheetLinkInput.text.toString().trim()
            val finalSheetLink = determineFinalSheetLink(input, currentSheetLink)

            if (finalSheetLink.startsWith("android.resource://")) {
                saveSheetLink(finalSheetLink)
                showToast("No sheet link provided. Using default CSV file.")
                navigateToMainActivity()
                return@setOnClickListener
            }

            validateAndSaveSheetLink(finalSheetLink)
        }

        cancelButton.setOnClickListener {
            navigateToMainActivity()
        }
    }

    private fun validateAndSaveLocalCsv(uri: String, csvData: String) {
        Utils.parseCsvData(csvData) { videos, error ->
            if (error != null || videos.isEmpty()) {
                showToast("Invalid CSV file: $error")
                return@parseCsvData
            }
            saveSheetLink(uri)
            showToast("Local CSV loaded successfully with ${videos.size} videos.")
            navigateToMainActivity()
        }
    }

    private fun determineFinalSheetLink(input: String, currentSheetLink: String?): String {
        return when {
            input.isBlank() -> currentSheetLink ?: "android.resource://${packageName}/raw/default_csv"
            isGoogleSheetId(input) -> "$SHEET_URL_PREFIX$input$SHEET_URL_SUFFIX"
            input.startsWith("file://") || input.startsWith("content://") -> input
            else -> input
        }
    }

    private fun validateAndSaveSheetLink(finalSheetLink: String) {
        Utils.fetchSheetData(this, finalSheetLink) { videos, error ->
            val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

            if (error != null) {
                val defaultCsvLink = "android.resource://${packageName}/raw/default_csv"
                saveSheetLink(defaultCsvLink)
                showToast("Invalid sheet link: $error")
            } else {
                saveSheetLink(finalSheetLink)
                showToast("Sheet loaded successfully with ${videos.size} videos.")
            }

            navigateToMainActivity()
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

    private fun isGoogleSheetId(input: String): Boolean {
        return !input.contains("http://") && !input.contains("https://") &&
                !input.startsWith("file://") && !input.startsWith("content://")
    }
}