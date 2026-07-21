package com.darius.listmanager.data.speech

sealed class SpeechState {
    object Idle : SpeechState()
    object Listening : SpeechState()
    data class Partial(val text: String) : SpeechState()
    /** [text] is the top hypothesis; [alternatives] are the recognizer's other N-best guesses. */
    data class Final(val text: String, val alternatives: List<String> = emptyList()) : SpeechState()
    data class Error(val message: String) : SpeechState()
}