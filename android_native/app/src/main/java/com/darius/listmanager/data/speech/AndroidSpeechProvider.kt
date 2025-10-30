package com.darius.listmanager.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO") // Romanian
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun startListening() {
        Log.d("Speech", "Starting to listen...")

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _speechState.value = SpeechState.Error("Speech recognition not available")
            return
        }

        // Always recreate the recognizer for a fresh start
        speechRecognizer?.destroy()
        speechRecognizer = null

        initializeSpeechRecognizer()

        try {
            speechRecognizer?.startListening(recognizerIntent)
            _speechState.value = SpeechState.Listening
        } catch (e: Exception) {
            Log.e("Speech", "Failed to start listening", e)
            _speechState.value = SpeechState.Error("Failed to start: ${e.message}")
        }
    }

    override fun stopListening() {
        Log.d("Speech", "Stopping listening")
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("Speech", "Error stopping listener", e)
        }
        _speechState.value = SpeechState.Idle
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("Speech", "Ready for speech")
                }

                override fun onBeginningOfSpeech() {
                    Log.d("Speech", "Beginning of speech")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Volume level changed
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Audio buffer received
                }

                override fun onEndOfSpeech() {
                    Log.d("Speech", "End of speech")
                }

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error - Try again"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error: $error"
                    }
                    Log.e("Speech", "Error: $errorMessage (code: $error)")

                    // Reset to idle state on error
                    _speechState.value = SpeechState.Idle
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d("Speech", "Final result: $text")

                    if (text.isNotBlank()) {
                        _speechState.value = SpeechState.Final(text)
                    } else {
                        _speechState.value = SpeechState.Idle
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d("Speech", "Partial result: $text")
                    _speechState.value = SpeechState.Partial(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Other events
                }
            })
        }
    }

    override fun release() {
        Log.d("Speech", "Releasing speech recognizer")
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}