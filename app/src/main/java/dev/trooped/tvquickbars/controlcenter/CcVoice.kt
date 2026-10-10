package dev.trooped.tvquickbars.controlcenter

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.mutableStateOf

/**
 * Voice search for the Control Center (Kitchen > Dinners & shopping): the remote's mic button (KEYCODE_SEARCH, taken by
 * QuickBarService while [keyHandler] is set) or the "Add by voice" tile starts [VoiceActivity], which listens with Android's
 * speech recognizer (Google's, the same one the mic button normally uses, through the remote's microphone) and hands the words
 * back through [onText]. The Control Center overlay stays on screen and shows [ui] (listening / what was heard / an error).
 */
object CcVoice {
    data class Ui(val listening: Boolean = false, val heard: String = "", val error: String = "")
    val ui = mutableStateOf(Ui())
    /** Set by the Control Center while a page that takes voice is open; QuickBarService gives it the mic button. */
    @Volatile var keyHandler: (() -> Unit)? = null
    /** Where the recognised words go (the page that started listening). */
    @Volatile var onText: ((String) -> Unit)? = null
    internal var activity: Activity? = null

    fun start(context: Context, then: (String) -> Unit) {
        onText = then
        if (activity != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { ui.value = Ui(error = "Voice search isn't available on this TV"); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ui.value = Ui(error = "QuickBars needs the microphone permission for voice search"); return
        }
        ui.value = Ui(listening = true)
        try {
            context.startActivity(Intent(context, VoiceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (t: Throwable) { Log.e("CcVoice", "start failed", t); ui.value = Ui(error = "Couldn't start voice search") }
    }

    fun cancel() { activity?.finish(); activity = null; ui.value = Ui() }
    fun clearError() { if (ui.value.error.isNotEmpty()) ui.value = Ui() }
}

/** Invisible activity that runs one speech recognition (an activity so the app is "in use" for the microphone). */
class VoiceActivity : Activity() {
    private var sr: SpeechRecognizer? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CcVoice.activity = this
        val r = try { SpeechRecognizer.createSpeechRecognizer(this) } catch (t: Throwable) { null }
        if (r == null) { fail("Voice search isn't available on this TV"); return }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { CcVoice.ui.value = CcVoice.Ui(listening = true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) CcVoice.ui.value = CcVoice.Ui(listening = true, heard = t)
            }
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (t.isEmpty()) { fail("Didn't catch that - press the mic and try again"); return }
                done = true; CcVoice.ui.value = CcVoice.Ui(heard = t)
                CcVoice.onText?.invoke(t); finish()
            }
            override fun onError(e: Int) {
                fail(when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that - press the mic and try again"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "QuickBars needs the microphone permission for voice search"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> "Voice search couldn't reach Google - try again"
                    else -> "Voice search stopped (error $e) - try again"
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        try { r.startListening(i) } catch (t: Throwable) { fail("Couldn't start voice search") }
    }

    private fun fail(msg: String) { if (done) return; done = true; CcVoice.ui.value = CcVoice.Ui(error = msg); finish() }

    override fun onDestroy() {
        if (!done) { done = true; if (CcVoice.ui.value.listening) CcVoice.ui.value = CcVoice.Ui() }
        try { sr?.destroy() } catch (_: Throwable) {}
        if (CcVoice.activity === this) CcVoice.activity = null
        super.onDestroy()
    }
}
