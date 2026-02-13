package com.darius.listmanager.data.speech

sealed class SpeechState {
    object Idle : SpeechState()
    object Listening : SpeechState()
    data class Partial(val text: String) : SpeechState()
    data class Final(val text: String) : SpeechState()
    data class Error(val message: String) : SpeechState()
}