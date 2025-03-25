package com.mmhw.csvtv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class SetupActivity : AppCompatActivity() {
    private val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/e/"
    private val SHEET_URL_SUFFIX = "/pub?gid=0&single=true&output=csv"
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val sheetLinkInput = findViewById<EditText>(R.id.sheet_link_input)
        val saveButton = findViewById<Button>(R.id.save_button)

        // Load the current sheet link from SharedPreferences, if it exists
        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val currentSheetLink = sharedPrefs.getString("sheet_link", null)
        if (currentSheetLink != null) {
            sheetLinkInput.setText(currentSheetLink)
        }

        saveButton.setOnClickListener {
            val input = sheetLinkInput?.text?.toString()?.trim() ?: ""

            // Determine the final sheet link
            val finalSheetLink = when {
                input.isBlank() -> {
                    // If input is blank, check if a sheet link is saved in SharedPreferences
                    if (currentSheetLink != null) {
                        currentSheetLink // Use the saved sheet link
                    } else {
                        // If no saved sheet link, use the default CSV file
                        "android.resource://${packageName}/raw/default_csv"
                    }
                }
                isGoogleSheetId(input) -> {
                    // If input is a sheet ID (not a URL), construct the full CSV URL
                    "$SHEET_URL_PREFIX$input$SHEET_URL_SUFFIX"
                }
                else -> {
                    // Use the input as the sheet link (we'll validate it below)
                    input
                }
            }

            // If the finalSheetLink is the default CSV, save it and proceed
            if (finalSheetLink.startsWith("android.resource://")) {
                sharedPrefs.edit().putString("sheet_link", finalSheetLink).apply()
                Toast.makeText(
                    this,
                    "No sheet link provided. Using default CSV file.",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                return@setOnClickListener
            }

            // Test the sheet link by fetching the data
            Log.d("SetupActivity", "Testing sheet link: $finalSheetLink")
            Utils.fetchSheetData(this, finalSheetLink) { videos, error ->
                val sharedPrefsEditor = sharedPrefs.edit()
                if (error != null) {
                    // Failed to load the sheet, fall back to default CSV
                    Log.e("SetupActivity", "Failed to load sheet: $error")
                    Toast.makeText(
                        this,
                        "Invalid sheet link: $error. Using default CSV file.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Save the default CSV link to ensure MainFragment falls back to it
                    sharedPrefsEditor.putString("sheet_link", "android.resource://${packageName}/raw/default_csv")
                } else {
                    // Successfully loaded the sheet
                    Log.d("SetupActivity", "Successfully loaded sheet with ${videos.size} videos")
                    Toast.makeText(
                        this,
                        "Sheet loaded successfully with ${videos.size} videos.",
                        Toast.LENGTH_LONG
                    ).show()
                    // Save the sheet link
                    sharedPrefsEditor.putString("sheet_link", finalSheetLink)
                }
                sharedPrefsEditor.apply()

                // Navigate to MainActivity
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }
    }

    // Check if the input is a Google Sheets ID (not a URL)
    private fun isGoogleSheetId(input: String): Boolean {
        return !input.contains("http://") && !input.contains("https://")
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}