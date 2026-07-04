package com.darius.listmanager.data.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningLoopPolicyTest {

    @Test
    fun results_whileListening_restarts() {
        assertEquals(LoopAction.RESTART, ListeningLoopPolicy.decide(RecognizerEvent.RESULTS, true))
    }

    @Test
    fun noMatch_whileListening_restarts() {
        assertEquals(LoopAction.RESTART, ListeningLoopPolicy.decide(RecognizerEvent.NO_MATCH, true))
    }

    @Test
    fun speechTimeout_whileListening_restarts() {
        assertEquals(LoopAction.RESTART, ListeningLoopPolicy.decide(RecognizerEvent.SPEECH_TIMEOUT, true))
    }

    @Test
    fun recognizerBusy_whileListening_retriesSoon() {
        assertEquals(LoopAction.RETRY_SOON, ListeningLoopPolicy.decide(RecognizerEvent.RECOGNIZER_BUSY, true))
    }

    @Test
    fun fatalError_whileListening_stops() {
        assertEquals(LoopAction.STOP, ListeningLoopPolicy.decide(RecognizerEvent.FATAL_ERROR, true))
    }

    @Test
    fun results_whenNotListening_stops() {
        assertEquals(LoopAction.STOP, ListeningLoopPolicy.decide(RecognizerEvent.RESULTS, false))
    }

    @Test
    fun timeout_whenNotListening_stops() {
        assertEquals(LoopAction.STOP, ListeningLoopPolicy.decide(RecognizerEvent.SPEECH_TIMEOUT, false))
    }

    @Test
    fun transientError_whileListening_retriesSoon() {
        assertEquals(LoopAction.RETRY_SOON, ListeningLoopPolicy.decide(RecognizerEvent.TRANSIENT_ERROR, true))
    }
}
