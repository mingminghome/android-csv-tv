package com.mmhw.csvtv.receiver

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mmhw.csvtv.R

/**
 * Shared passphrase for LAN media encryption. Off unless the user turns it on.
 * The value is stored in app preferences and applied to the native library.
 */
object CastCrypto {
    private const val PREFS = "castify_crypto"
    private const val SECURE_PREFS = "castify_crypto_secure"
    private const val KEY_ON = "on"
    private const val KEY_PASS = "pass"
    private const val KEY_MIGRATED = "migrated"

    @Volatile
    private var cached: SharedPreferences? = null

    fun enabled(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getBoolean(KEY_ON, false) && passphrase(context).isNotEmpty()
    }

    fun passphrase(context: Context): String {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ON, false)) return ""
        return prefs.getString(KEY_PASS, "").orEmpty().trim()
    }

    fun buttonLabel(context: Context): String {
        return context.getString(
            if (enabled(context)) R.string.setup_encryption_on else R.string.setup_encryption_off
        )
    }

    /**
     * Flip encryption. Turning on with no saved passphrase opens the dialog
     * and returns false. Otherwise applies immediately and returns true.
     */
    fun toggle(context: Context, onSaved: () -> Unit = {}): Boolean {
        if (enabled(context)) {
            prefs(context).edit().putBoolean(KEY_ON, false).apply()
            apply(context)
            onSaved()
            return true
        }
        val saved = prefs(context).getString(KEY_PASS, "").orEmpty().trim()
        if (CastCryptoPolicy.needsPassphraseDialog(currentlyOn = false, savedPassphrase = saved)) {
            showDialog(context, onSaved)
            return false
        }
        prefs(context).edit().putBoolean(KEY_ON, true).apply()
        apply(context)
        onSaved()
        return true
    }

    /** Push the saved setting into the native library. Empty disables encryption. */
    fun apply(context: Context) {
        if (!NativeReceiver.available) return
        NativeReceiver.nativeSetPassphrase(passphrase(context))
    }

    fun clear(context: Context) {
        prefs(context).edit().putBoolean(KEY_ON, false).apply()
        apply(context)
    }

    fun showDialog(context: Context, onSaved: () -> Unit = {}) {
        val prefs = prefs(context)
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val check = CheckBox(context).apply {
            text = context.getString(R.string.crypto_enable)
            isChecked = enabled(context)
        }
        val field = EditText(context).apply {
            hint = context.getString(R.string.crypto_pass_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setText(prefs.getString(KEY_PASS, "").orEmpty())
        }
        box.addView(check)
        box.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.crypto_title)
            .setMessage(R.string.crypto_message)
            .setView(box)
            .setPositiveButton(R.string.crypto_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = field.text?.toString()?.trim().orEmpty()
                if (check.isChecked && typed.isEmpty()) {
                    Toast.makeText(context, R.string.crypto_need_pass, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val keep = typed.ifEmpty { prefs.getString(KEY_PASS, "").orEmpty() }
                prefs.edit()
                    .putBoolean(KEY_ON, check.isChecked)
                    .putString(KEY_PASS, keep)
                    .apply()
                apply(context)
                onSaved()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val app = context.applicationContext
                val opened = try {
                    val secure = openSecure(app)
                    migrate(app, secure)
                    secure
                } catch (e: Exception) {
                    Log.w("CastCrypto", "encrypted prefs unavailable", e)
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                }
                cached = opened
                opened
            }
        }
    }

    private fun openSecure(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrate(context: Context, secure: SharedPreferences) {
        if (secure.getBoolean(KEY_MIGRATED, false)) return
        val old = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = secure.edit().putBoolean(KEY_MIGRATED, true)
        if (old.contains(KEY_ON) || old.contains(KEY_PASS)) {
            editor
                .putBoolean(KEY_ON, old.getBoolean(KEY_ON, false))
                .putString(KEY_PASS, old.getString(KEY_PASS, "").orEmpty())
            old.edit().clear().apply()
        }
        editor.apply()
    }
}

object CastCryptoPolicy {
    fun needsPassphraseDialog(currentlyOn: Boolean, savedPassphrase: String): Boolean {
        return !currentlyOn && savedPassphrase.isBlank()
    }

    /** With a passphrase, Play is only for a matching encrypted receiver. */
    fun playAllowed(encryptionOn: Boolean, targetIsCastify: Boolean): Boolean {
        return !encryptionOn || targetIsCastify
    }
}
