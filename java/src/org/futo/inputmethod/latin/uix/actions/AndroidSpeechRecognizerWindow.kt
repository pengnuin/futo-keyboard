package org.futo.inputmethod.latin.uix.actions

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.MicPermissionActivity
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.SPEECH_RECOGNIZER_COMPONENT
import org.futo.inputmethod.latin.uix.SPEECH_RECOGNIZER_PARTIAL_RESULTS
import org.futo.inputmethod.latin.uix.SPEECH_RECOGNIZER_PREFER_OFFLINE
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import java.util.Locale

data class RecognitionServiceInfo(
    val componentName: String,
    val label: String,
    val packageName: String
)

fun queryRecognitionServices(context: Context): List<RecognitionServiceInfo> {
    val pm = context.packageManager
    val intent = Intent(RecognitionService.SERVICE_INTERFACE)
    val services = pm.queryIntentServices(intent, 0)
    return services.mapNotNull { resolveInfo ->
        val si = resolveInfo.serviceInfo ?: return@mapNotNull null
        val component = ComponentName(si.packageName, si.name).flattenToString()
        val appLabel = try {
            val appInfo = pm.getApplicationInfo(si.packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            si.packageName
        }
        val serviceLabel = resolveInfo.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
        val displayLabel = if (serviceLabel != null && serviceLabel != appLabel) {
            "$appLabel ($serviceLabel)"
        } else {
            appLabel
        }
        RecognitionServiceInfo(
            componentName = component,
            label = displayLabel,
            packageName = si.packageName
        )
    }.distinctBy { it.componentName }.sortedBy { it.label.lowercase(Locale.ROOT) }
}

private sealed interface RecognizerUiState {
    data object Initializing : RecognizerUiState
    data object Listening : RecognizerUiState
    data object Processing : RecognizerUiState
    data object NoPermission : RecognizerUiState
    data class Error(val message: String) : RecognizerUiState
}

internal class AndroidSpeechRecognizerWindow(
    private val manager: KeyboardManagerForAction,
    private val state: VoiceInputPersistentState,
    private val locales: List<Locale>
) : ActionWindow() {
    companion object {
        private const val TAG = "AndroidSpeechRecognizer"
    }

    private val context = manager.getContext()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var uiState by mutableStateOf<RecognizerUiState>(RecognizerUiState.Initializing)
    private var rmsNormalized by mutableFloatStateOf(0f)

    private var speechRecognizer: SpeechRecognizer? = null
    private var inputTransaction = manager.createInputTransaction()
    private var isClosed = false
    private var wasFinished = false
    private var cancelPlayed = false

    private val shouldPlaySounds = context.getSetting(ENABLE_SOUND)

    init {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            startRecognition()
        }
    }

    private fun startRecognition() {
        if (isClosed) return

        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            uiState = RecognizerUiState.NoPermission
            openMicPermissionActivity()
            return
        }

        val componentStr = context.getSetting(SPEECH_RECOGNIZER_COMPONENT)
        val component = componentStr.takeIf { it.isNotBlank() }
            ?.let { ComponentName.unflattenFromString(it) }

        val recognizer = try {
            if (component != null) {
                SpeechRecognizer.createSpeechRecognizer(context, component)
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer", e)
            null
        }

        if (recognizer == null) {
            uiState = RecognizerUiState.Error(
                context.getString(R.string.voice_input_settings_speech_recognizer_unavailable)
            )
            return
        }

        wasFinished = false
        cancelPlayed = false
        uiState = RecognizerUiState.Initializing

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isClosed) return
                uiState = RecognizerUiState.Listening
                if (shouldPlaySounds) {
                    state.soundPlayer.playStartSound()
                }
            }

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {
                if (isClosed) return
                // SpeechRecognizer typically reports rmsdB in [-2, 10]
                rmsNormalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                if (isClosed || wasFinished) return
                rmsNormalized = 0f
                uiState = RecognizerUiState.Processing
            }

            override fun onError(error: Int) {
                if (isClosed || wasFinished) return
                rmsNormalized = 0f
                if (shouldPlaySounds && !cancelPlayed) {
                    state.soundPlayer.playCancelSound()
                    cancelPlayed = true
                }
                inputTransaction.cancel()

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    uiState = RecognizerUiState.NoPermission
                } else {
                    uiState = RecognizerUiState.Error(mapErrorMessage(error))
                }
            }

            override fun onResults(results: Bundle?) {
                if (isClosed || wasFinished) return
                rmsNormalized = 0f
                val result = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()

                if (result.isNullOrEmpty()) {
                    inputTransaction.cancel()
                    uiState = RecognizerUiState.Error(
                        context.getString(R.string.speech_recognizer_error_no_match)
                    )
                    return
                }

                wasFinished = true
                val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
                inputTransaction.commit(sanitized)
                manager.announce(result)
                manager.closeActionWindow()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (isClosed || wasFinished) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!partial.isNullOrEmpty()) {
                    val sanitized = ModelOutputSanitizer.sanitize(partial, inputTransaction.textContext)
                    inputTransaction.updatePartial(sanitized)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                context.getSetting(SPEECH_RECOGNIZER_PARTIAL_RESULTS)
            )
            putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                context.getSetting(SPEECH_RECOGNIZER_PREFER_OFFLINE)
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            locales.firstOrNull()?.let { locale ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            }
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            inputTransaction.cancel()
            uiState = RecognizerUiState.Error(
                e.localizedMessage ?: context.getString(R.string.speech_recognizer_error_client)
            )
        }
    }

    private fun retryRecognition() {
        destroyRecognizerOnMain()
        inputTransaction.cancel()
        inputTransaction = manager.createInputTransaction()
        startRecognition()
    }

    private fun stopListening() {
        if (uiState != RecognizerUiState.Listening) return
        uiState = RecognizerUiState.Processing
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening failed", e)
        }
    }

    private fun openMicPermissionActivity() {
        val intent = Intent(context, MicPermissionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        context.startActivity(intent)
    }

    private fun mapErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> context.getString(R.string.speech_recognizer_error_audio)
        SpeechRecognizer.ERROR_CLIENT -> context.getString(R.string.speech_recognizer_error_client)
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            context.getString(R.string.speech_recognizer_permission_required)
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            context.getString(R.string.speech_recognizer_error_network)
        SpeechRecognizer.ERROR_NO_MATCH -> context.getString(R.string.speech_recognizer_error_no_match)
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> context.getString(R.string.speech_recognizer_error_busy)
        SpeechRecognizer.ERROR_SERVER, 11 /* ERROR_SERVER_DISCONNECTED */ ->
            context.getString(R.string.speech_recognizer_error_server)
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> context.getString(R.string.speech_recognizer_error_timeout)
        10 /* ERROR_TOO_MANY_REQUESTS */ ->
            context.getString(R.string.speech_recognizer_error_too_many_requests)
        12 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 13 /* ERROR_LANGUAGE_UNAVAILABLE */ ->
            context.getString(R.string.speech_recognizer_error_language)
        else -> context.getString(R.string.speech_recognizer_error_unknown, error)
    }

    private fun destroyRecognizerOnMain() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        val destroyAction = {
            try {
                recognizer.cancel()
            } catch (_: Exception) {
            }
            try {
                recognizer.destroy()
            } catch (_: Exception) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyAction()
        } else {
            mainHandler.post(destroyAction)
        }
    }

    @Composable
    override fun windowName(): String = stringResource(R.string.action_voice_input_title)

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val pulseScale by animateFloatAsState(
            targetValue = if (uiState == RecognizerUiState.Listening) 1f + rmsNormalized * 0.45f else 1f,
            label = "micPulse"
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    enabled = true,
                    onClickLabel = null,
                    onClick = {
                        when (uiState) {
                            RecognizerUiState.Listening -> stopListening()
                            RecognizerUiState.NoPermission -> openMicPermissionActivity()
                            is RecognizerUiState.Error -> retryRecognition()
                            RecognizerUiState.Initializing, RecognizerUiState.Processing -> Unit
                        }
                    },
                    role = null,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
                .semantics(mergeDescendants = true) {
                    traversalIndex = -1.0f
                }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (uiState == RecognizerUiState.Listening) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState is RecognizerUiState.Error || uiState == RecognizerUiState.NoPermission) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.mic_fill),
                            contentDescription = stringResource(R.string.action_voice_input_title),
                            tint = if (uiState is RecognizerUiState.Error || uiState == RecognizerUiState.NoPermission) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val statusText = when (val s = uiState) {
                    RecognizerUiState.Initializing -> stringResource(R.string.speech_recognizer_initializing)
                    RecognizerUiState.Listening -> stringResource(R.string.speech_recognizer_listening)
                    RecognizerUiState.Processing -> stringResource(R.string.speech_recognizer_processing)
                    RecognizerUiState.NoPermission -> stringResource(R.string.speech_recognizer_permission_required)
                    is RecognizerUiState.Error ->
                        "${s.message}\n${stringResource(R.string.speech_recognizer_error_tap_retry)}"
                }

                Text(
                    text = statusText,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { manager.closeActionWindow() }) {
                        Text(stringResource(R.string.speech_recognizer_cancel))
                    }
                }
            }
        }
    }

    override fun close(): CloseResult {
        isClosed = true
        if (!wasFinished) {
            inputTransaction.cancel()
        }
        destroyRecognizerOnMain()
        return CloseResult.Default
    }
}
