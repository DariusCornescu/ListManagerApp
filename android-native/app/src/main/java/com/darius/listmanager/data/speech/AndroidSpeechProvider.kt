package com.darius.listmanager.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.darius.listmanager.data.repository.SpeechRepository
import com.darius.listmanager.util.SensitiveLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechProvider(private val context: Context) : SpeechRepository {

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionGate = RecognitionSessionGate()

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO") // Romanian
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        // Ask for several hypotheses so the matcher can rank the catalog against
        // all of them (N-best), not just the recognizer's top guess.
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
    }

    override fun startListening() {
        Log.d("Speech", "Starting continuous listening...")

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            sessionGate.stop()
            _speechState.value = SpeechState.Error("Recunoașterea vocală nu este disponibilă pe acest telefon.")
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        val generation = sessionGate.start()
        beginRecognition(generation)
    }

    override fun stopListening() {
        Log.d("Speech", "Stopping continuous listening")
        sessionGate.stop()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e("Speech", "Error stopping listener", e)
        }
        _speechState.value = SpeechState.Idle
    }

    /** (Re)create the recognizer for one utterance in the same explicit session. */
    private fun beginRecognition(generation: Long) {
        if (!sessionGate.isCurrent(generation)) return
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        initializeSpeechRecognizer(generation)
        try {
            if (!sessionGate.isCurrent(generation)) return
            speechRecognizer?.startListening(recognizerIntent)
            _speechState.value = SpeechState.Listening
        } catch (e: Exception) {
            Log.e("Speech", "Failed to start listening", e)
            if (sessionGate.stopIfCurrent(generation)) {
                _speechState.value = SpeechState.Error("Recunoașterea vocală nu a pornit. Încearcă din nou.")
            }
        }
    }

    /** Apply the loop policy for [event]; restart, retry after a delay, or stop. */
    private fun applyPolicy(
        generation: Long,
        event: RecognizerEvent,
        errorMessage: String? = null,
    ) {
        if (!sessionGate.isCurrent(generation)) return
        when (ListeningLoopPolicy.decide(event, wantListening = true)) {
            LoopAction.RESTART -> mainHandler.post {
                if (sessionGate.isCurrent(generation)) beginRecognition(generation)
            }
            LoopAction.RETRY_SOON -> mainHandler.postDelayed({
                if (sessionGate.isCurrent(generation)) beginRecognition(generation)
            }, 300)
            LoopAction.STOP -> {
                if (errorMessage != null && sessionGate.isCurrent(generation)) {
                    // Fatal error while the user still wanted to listen: surface it.
                    _speechState.value = SpeechState.Error(errorMessage)
                } else {
                    _speechState.value = SpeechState.Idle
                }
                sessionGate.stopIfCurrent(generation)
            }
        }
    }

    private fun initializeSpeechRecognizer(generation: Long) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val event = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> RecognizerEvent.NO_MATCH
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognizerEvent.SPEECH_TIMEOUT
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognizerEvent.RECOGNIZER_BUSY
                        // Transient network/server hiccups (incl. code 11
                        // SERVER_DISCONNECTED) — keep the continuous loop alive
                        // instead of killing the whole session.
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER,
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> RecognizerEvent.TRANSIENT_ERROR
                        else -> RecognizerEvent.FATAL_ERROR
                    }
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Microfonul nu a putut înregistra."
                        SpeechRecognizer.ERROR_CLIENT -> "Recunoașterea vocală s-a oprit. Încearcă din nou."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Am nevoie de acces la microfon."
                        SpeechRecognizer.ERROR_NETWORK -> "Nu există conexiune la internet."
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Conexiunea la internet e prea lentă."
                        SpeechRecognizer.ERROR_SERVER -> "Serviciul de recunoaștere nu răspunde."
                        else -> "Recunoașterea vocală a eșuat (cod $error)."
                    }
                    Log.d("Speech", "onError code=$error -> $event")
                    applyPolicy(generation, event, errorMessage = message)
                }

                override fun onResults(results: Bundle?) {
                    if (!sessionGate.isCurrent(generation)) return
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    val hypothesisCount = matches?.size ?: 0
                    SensitiveLog.d("Speech", "Final result received ($hypothesisCount hypotheses)") {
                        "Final result: '$text' ($hypothesisCount hypotheses)"
                    }
                    if (text.isNotBlank()) {
                        val alternatives = matches?.drop(1)?.filter { it.isNotBlank() } ?: emptyList()
                        _speechState.value = SpeechState.Final(text, alternatives)
                    }
                    applyPolicy(generation, RecognizerEvent.RESULTS)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!sessionGate.isCurrent(generation)) return
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        _speechState.value = SpeechState.Partial(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    override fun release() {
        Log.d("Speech", "Releasing speech recognizer")
        sessionGate.stop()
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
