package com.mmhw.csvtv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val sheetLink = sharedPrefs.getString("sheet_link", null)

        if (sheetLink.isNullOrBlank()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        if (savedInstanceState == null) {
            val fragment = MainFragment().apply {
                arguments = Bundle().apply {
                    putString("sheet_link", sheetLink)
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        }
    }
}