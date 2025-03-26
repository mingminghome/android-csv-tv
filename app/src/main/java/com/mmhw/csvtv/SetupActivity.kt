package com.mmhw.csvtv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.FragmentActivity

class SetupActivity : FragmentActivity() {
    private val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/e/"
    private val SHEET_URL_SUFFIX = "/pub?gid=0&single=true&output=csv"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val sheetLinkInput = findViewById<EditText>(R.id.sheet_link_input)
        val saveButton = findViewById<Button>(R.id.save_button)
        val cancelButton = findViewById<Button>(R.id.cancel_button)

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val currentSheetLink = sharedPrefs.getString("sheet_link", null)

        // Set up input field and cancel button visibility
        currentSheetLink?.let {
            sheetLinkInput.setText(it)
            cancelButton.visibility = View.VISIBLE
        } ?: run {
            cancelButton.visibility = View.GONE
        }

        saveButton.setOnClickListener {
            val input = sheetLinkInput.text.toString().trim()
            val finalSheetLink = determineFinalSheetLink(input, currentSheetLink)

            // If using default CSV, immediately save and navigate
            if (finalSheetLink.startsWith("android.resource://")) {
                saveSheetLink(finalSheetLink)
                showToast("No sheet link provided. Using default CSV file.")
                navigateToMainActivity()
                return@setOnClickListener
            }

            // Validate and save sheet link
            validateAndSaveSheetLink(finalSheetLink)
        }

        cancelButton.setOnClickListener {
            navigateToMainActivity()
        }
    }

    private fun determineFinalSheetLink(input: String, currentSheetLink: String?): String {
        return when {
            input.isBlank() -> currentSheetLink ?: "android.resource://${packageName}/raw/default_csv"
            isGoogleSheetId(input) -> "$SHEET_URL_PREFIX$input$SHEET_URL_SUFFIX"
            else -> input
        }
    }

    private fun validateAndSaveSheetLink(finalSheetLink: String) {
        Utils.fetchSheetData(this, finalSheetLink) { videos, error ->
            val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

            if (error != null) {
                // Fall back to default CSV if sheet link is invalid
                val defaultCsvLink = "android.resource://${packageName}/raw/default_csv"
                saveSheetLink(defaultCsvLink)
                showToast("Invalid sheet link: $error. Using default CSV file.")
            } else {
                // Successfully loaded the sheet
                saveSheetLink(finalSheetLink)
                showToast("Sheet loaded successfully with ${videos.size} videos.")
            }

            // Always navigate to MainActivity
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

    // Check if the input is a Google Sheets ID (not a URL)
    private fun isGoogleSheetId(input: String): Boolean {
        return !input.contains("http://") && !input.contains("https://")
    }
}