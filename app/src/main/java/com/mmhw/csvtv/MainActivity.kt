package com.mmhw.csvtv

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val sheetLink = sharedPrefs.getString("sheet_link", null)

        if (sheetLink.isNullOrBlank()) {
            // No CSV yet → Setup as first-run init (not empty main).
            startActivity(SetupActivity.createIntent(this, initMode = true))
            finish()
            return
        }
        // Warm adblock rule engine + auto-download remote lists when stale
        AdBlocker.ensureLoaded(applicationContext)

        if (savedInstanceState == null) {
            val fragment = MainFragment().apply {
                arguments = Bundle().apply {
                    putString("sheet_link", sheetLink)
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment, MAIN_FRAGMENT_TAG)
                .commitNow()
            dispatchPlayIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchPlayIntent(intent)
    }

    private fun dispatchPlayIntent(intent: Intent?) {
        val play = HomeScreenPublisher.parsePlayUri(intent?.data) ?: return
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) !is MainFragment) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        val main = supportFragmentManager.findFragmentById(R.id.fragment_container) as? MainFragment
            ?: supportFragmentManager.findFragmentByTag(MAIN_FRAGMENT_TAG) as? MainFragment
        main?.playVideoFromDeepLink(play.first, play.second)
        intent?.data = null
    }

    companion object {
        const val MAIN_FRAGMENT_TAG = "main"
    }
}