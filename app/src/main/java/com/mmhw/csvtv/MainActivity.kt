package com.mmhw.csvtv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.mmhw.csvtv.receiver.ShareShortcut

class MainActivity : FragmentActivity() {
    var pip: PipController? = null
        private set

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controller = pip
        if (controller != null) {
            val handled = when (event.action) {
                KeyEvent.ACTION_DOWN -> controller.onKeyDown(event)
                KeyEvent.ACTION_UP -> controller.onKeyUp(event)
                else -> false
            }
            if (handled) return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE && event.repeatCount == 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppChrome.prepare(this)
        ShareShortcut.publish(this)
        setContentView(R.layout.activity_main)

        val sharedPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val sheetLink = sharedPrefs.getString("sheet_link", null)

        if (sheetLink.isNullOrBlank() && !Utils.isSetupSkipped(this)) {
            // No CSV yet → Setup as first-run init (not empty main).
            startActivity(SetupActivity.createIntent(this, initMode = true))
            finish()
            return
        }
        // Warm adblock rule engine + auto-download remote lists when stale
        AdBlocker.ensureLoaded(applicationContext)

        val controller = PipController(this)
        controller.install()
        pip = controller

        if (savedInstanceState == null) {
            val fragment = MainFragment().apply {
                arguments = Bundle().apply {
                    putString("sheet_link", sheetLink)
                }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment, MAIN_FRAGMENT_TAG)
                .commitNow()
            controller.attach(fragment)
            dispatchPlayIntent(intent)
        } else {
            // Corner windows are not restored. Drop any media the fragment
            // manager brought back so a full-screen player is not left unwired.
            val fm = supportFragmentManager
            val tx = fm.beginTransaction()
            var main: MainFragment? = null
            fm.fragments.toList().forEach { fragment ->
                if (fragment is MainFragment) {
                    main = fragment
                    tx.show(fragment)
                } else if (fragment.isAdded) {
                    tx.remove(fragment)
                }
            }
            tx.commitNow()
            if (main == null) {
                val fragment = MainFragment().apply {
                    arguments = Bundle().apply {
                        putString("sheet_link", sheetLink)
                    }
                }
                fm.beginTransaction()
                    .replace(R.id.fragment_container, fragment, MAIN_FRAGMENT_TAG)
                    .commitNow()
                controller.attach(fragment)
            } else {
                controller.attach(main!!)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pip?.syncPlaybackFullscreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) pip?.syncPlaybackFullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchPlayIntent(intent)
    }

    private fun dispatchPlayIntent(intent: Intent?) {
        val play = HomeScreenPublisher.parsePlayUri(intent?.data) ?: return
        val controller = pip
        if (controller != null &&
            controller.model.stage != PipPolicy.Kind.NONE &&
            controller.model.corner != PipPolicy.Kind.NONE
        ) {
            Toast.makeText(this, "Close the small window first.", Toast.LENGTH_SHORT).show()
            intent?.data = null
            return
        }
        val main = supportFragmentManager.findFragmentByTag(MAIN_FRAGMENT_TAG) as? MainFragment
            ?: supportFragmentManager.fragments.filterIsInstance<MainFragment>().firstOrNull()
        main?.playVideoFromDeepLink(play.first, play.second)
        intent?.data = null
    }

    companion object {
        const val MAIN_FRAGMENT_TAG = "main"
    }
}