package com.darius.listmanager.data.speech

/** A recognizer lifecycle event the continuous loop reacts to. */
enum class RecognizerEvent {
    RESULTS,          // onResults fired (one utterance finished)
    NO_MATCH,         // ERROR_NO_MATCH
    SPEECH_TIMEOUT,   // ERROR_SPEECH_TIMEOUT (silence)
    RECOGNIZER_BUSY,  // ERROR_RECOGNIZER_BUSY
    TRANSIENT_ERROR,  // network / server / server-disconnected (code 11) / too-many-requests
    FATAL_ERROR       // permissions / audio / client
}

/** What the provider should do next. */
enum class LoopAction {
    RESTART,     // begin a new recognition immediately
    RETRY_SOON,  // begin a new recognition after a short delay
    STOP         // stop the loop and go Idle/Error
}

/**
 * Pure decision logic for the continuous ("one big listening") restart loop.
 * Extracted from [AndroidSpeechProvider] so it can be unit-tested without the
 * Android framework.
 */
object ListeningLoopPolicy {
    fun decide(event: RecognizerEvent, wantListening: Boolean): LoopAction {
        if (!wantListening) return LoopAction.STOP
        return when (event) {
            RecognizerEvent.RESULTS -> LoopAction.RESTART
            RecognizerEvent.NO_MATCH -> LoopAction.RESTART
            RecognizerEvent.SPEECH_TIMEOUT -> LoopAction.RESTART
            RecognizerEvent.RECOGNIZER_BUSY -> LoopAction.RETRY_SOON
            RecognizerEvent.TRANSIENT_ERROR -> LoopAction.RETRY_SOON
            RecognizerEvent.FATAL_ERROR -> LoopAction.STOP
        }
    }
}
