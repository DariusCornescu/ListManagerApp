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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechProvider(private val context: Context) : SpeechRepository {

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the user wants a continuous session (between Record and Stop). */
    @Volatile
    private var wantListening = false

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO") // Romanian
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun startListening() {
        Log.d("Speech", "Starting continuous listening...")

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            wantListening = false
            _speechState.value = SpeechState.Error("Speech recognition not available")
            return
        }

        wantListening = true
        beginRecognition()
    }

    override fun stopListening() {
        Log.d("Speech", "Stopping continuous listening")
        wantListening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("Speech", "Error stopping listener", e)
        }
        _speechState.value = SpeechState.Idle
    }

    /** (Re)create the recognizer and start one utterance. Does NOT change [wantListening]. */
    private fun beginRecognition() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        initializeSpeechRecognizer()
        try {
            speechRecognizer?.startListening(recognizerIntent)
            _speechState.value = SpeechState.Listening
        } catch (e: Exception) {
            Log.e("Speech", "Failed to start listening", e)
            wantListening = false
            _speechState.value = SpeechState.Error("Failed to start: ${e.message}")
        }
    }

    /** Apply the loop policy for [event]; restart, retry after a delay, or stop. */
    private fun applyPolicy(event: RecognizerEvent, errorMessage: String? = null) {
        when (ListeningLoopPolicy.decide(event, wantListening)) {
            LoopAction.RESTART -> mainHandler.post { if (wantListening) beginRecognition() }
            LoopAction.RETRY_SOON -> mainHandler.postDelayed({ if (wantListening) beginRecognition() }, 300)
            LoopAction.STOP -> {
                if (errorMessage != null && wantListening) {
                    // Fatal error while the user still wanted to listen: surface it.
                    _speechState.value = SpeechState.Error(errorMessage)
                } else {
                    _speechState.value = SpeechState.Idle
                }
                wantListening = false
            }
        }
    }

    private fun initializeSpeechRecognizer() {
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
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        else -> "Recognition error: $error"
                    }
                    Log.d("Speech", "onError code=$error -> $event")
                    applyPolicy(event, errorMessage = message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d("Speech", "Final result: $text")
                    if (text.isNotBlank()) {
                        _speechState.value = SpeechState.Final(text)
                    }
                    applyPolicy(RecognizerEvent.RESULTS)
                }

                override fun onPartialResults(partialResults: Bundle?) {
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
        wantListening = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
