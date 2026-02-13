package com.darius.listmanager.data.repository

import com.darius.listmanager.data.speech.SpeechState
import kotlinx.coroutines.flow.Flow

interface SpeechRepository {
    val speechState: Flow<SpeechState>
    fun startListening()
    fun stopListening()
    fun release()
}