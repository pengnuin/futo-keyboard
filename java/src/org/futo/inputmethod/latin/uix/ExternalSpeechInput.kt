package org.futo.inputmethod.latin.uix

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer

/**
 * With "Disable built-in voice input" on, the mic key normally hands the whole keyboard over to
 * another app's voice IME (the legacy CODE_SHORTCUT path). With this setting on, FUTO Keyboard
 * instead opens the standard android.speech.action.RECOGNIZE_SPEECH panel (FUTO Voice Input,
 * Notune, ...), stays the active IME, and types the returned text itself.
 */
val USE_SPEECH_INTENT_FOR_SYSTEM_VOICE_INPUT = SettingsKey(
    key = booleanPreferencesKey("use_speech_intent_for_system_voice_input"),
    default = false
)

object ExternalSpeechInput {
    private const val TAG = "ExternalSpeechInput"

    /** Give up on a speech panel that never answers. */
    private const val SESSION_TIMEOUT_MS = 5 * 60_000L

    /** Drop a transcript that could not be typed anywhere for this long. */
    private const val RESULT_TTL_MS = 30_000L

    /** Let FUTO finish starting input for the field before typing into it. */
    private const val KEYBOARD_SETTLE_MS = 150L

    /** If the keyboard is not shown again, type into the re-bound field after this long. */
    private const val FALLBACK_DELAY_MS = 1_500L
    private const val FALLBACK_RETRY_MS = 500L

    private class Session(val ime: LatinIME, val packageName: String?, val fieldId: Int) {
        var text: String? = null
        var deliveredAt = 0L
        var observer: LifecycleEventObserver? = null
        var timeoutJob: Job? = null
        var fallbackJob: Job? = null
    }

    private var session: Session? = null

    /**
     * Opens the speech panel. Returns false if that was not possible, so the caller can fall back
     * to the old keyboard-switching behaviour.
     */
    fun start(manager: KeyboardManagerForAction): Boolean {
        val ime = manager.getContext() as? LatinIME ?: return false
        endSession()

        val editor = ime.getBaseInputEditorInfo()
        val s = Session(ime, editor?.packageName, editor?.fieldId ?: -1)

        val intent = Intent(ime, ExternalSpeechInputActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
        try {
            ime.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the speech panel", e)
            return false
        }

        // The speech panel takes window focus, which hides the keyboard. When the keyboard window
        // comes back, type the transcript through FUTO's normal input-transaction path.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onKeyboardShown(s)
        }
        s.observer = observer
        s.timeoutJob = ime.lifecycleScope.launch {
            delay(SESSION_TIMEOUT_MS)
            if (session === s) endSession()
        }
        session = s
        ime.lifecycle.addObserver(observer)
        return true
    }

    /** Called by [ExternalSpeechInputActivity]. [text] is null if cancelled or nothing was heard. */
    internal fun onResult(text: String?) {
        val s = session ?: return
        val cleaned = text?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            endSession()
            return
        }

        s.text = cleaned
        s.deliveredAt = SystemClock.elapsedRealtime()

        // Fallback for when the keyboard window is not shown again (some apps do not bring it
        // back): type into the field once it is bound again.
        s.fallbackJob = s.ime.lifecycleScope.launch {
            delay(FALLBACK_DELAY_MS)
            while (session === s && s.text != null) {
                if (SystemClock.elapsedRealtime() - s.deliveredAt > RESULT_TTL_MS) {
                    Log.w(TAG, "Dropping transcript, the original field never came back")
                    endSession()
                    return@launch
                }
                if (typeDirectly(s)) return@launch
                delay(FALLBACK_RETRY_MS)
            }
        }
    }

    private fun onKeyboardShown(s: Session) {
        if (session !== s || s.text == null) return
        s.ime.lifecycleScope.launch(Dispatchers.Main) {
            delay(KEYBOARD_SETTLE_MS)
            typeThroughTransaction(s)
        }
    }

    private fun isSameField(s: Session): Boolean {
        val info = s.ime.getBaseInputEditorInfo() ?: return false
        return info.packageName == s.packageName && info.fieldId == s.fieldId
    }

    /** Same path the built-in voice input uses, so spacing and capitalisation behave the same. */
    private fun typeThroughTransaction(s: Session) {
        if (session !== s || !isSameField(s) || !s.ime.isInputViewShown) return
        val text = s.text ?: return
        s.text = null
        endSession()

        try {
            val tx = s.ime.imeManager.createInputTransaction()
            try {
                tx.commit(ModelOutputSanitizer.sanitize(text, tx.textContext))
            } finally {
                // No-op after commit(); makes sure a transaction can never be left open.
                tx.cancel()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Input transaction failed, typing directly", e)
            typeDirectly(s.ime, text)
        }
    }

    private fun typeDirectly(s: Session): Boolean {
        val text = s.text ?: return false
        if (!isSameField(s)) return false
        if (!typeDirectly(s.ime, text)) return false
        s.text = null
        endSession()
        return true
    }

    private fun typeDirectly(ime: LatinIME, text: String): Boolean {
        val ic = ime.getBaseInputConnection() ?: return false
        val before = ic.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && !before.last().isWhitespace()
        return ic.commitText(if (needsSpace) " $text" else text, 1)
    }

    private fun endSession() {
        val s = session ?: return
        session = null
        s.timeoutJob?.cancel()
        s.fallbackJob?.cancel()
        s.observer?.let { s.ime.lifecycle.removeObserver(it) }
    }
}

/**
 * Invisible helper. An input method cannot receive activity results, so this translucent activity
 * starts the speech panel and hands the transcript back to [ExternalSpeechInput].
 */
class ExternalSpeechInputActivity : ComponentActivity() {
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else {
            null
        }
        ExternalSpeechInput.onResult(text)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // After recreation the pending result is delivered to speechLauncher; do not start again.
        if (savedInstanceState != null) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.external_voice_input_no_app, Toast.LENGTH_LONG).show()
            ExternalSpeechInput.onResult(null)
            finish()
        }
    }
}
