package com.darius.listmanager.data.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningLoopPolicyTest {

    @Test
    fun explicitStop_invalidatesCallbacksAndRetriesFromCurrentRecognizer() {
        val gate = RecognitionSessionGate()
        val generation = gate.start()

        gate.stop()

        assertEquals(false, gate.isCurrent(generation))
        assertEquals(false, gate.stopIfCurrent(generation))
    }

    @Test
    fun newStart_rejectsLateCallbackFromPreviousRecognizer() {
        val gate = RecognitionSessionGate()
        val first = gate.start()
        val second = gate.start()

        assertEquals(false, gate.isCurrent(first))
        assertEquals(true, gate.isCurrent(second))
    }

    @Test
    fun fatalCallback_stopsOnlyItsOwnSession() {
        val gate = RecognitionSessionGate()
        val first = gate.start()
        val second = gate.start()

        assertEquals(false, gate.stopIfCurrent(first))
        assertEquals(true, gate.isCurrent(second))
        assertEquals(true, gate.stopIfCurrent(second))
        assertEquals(false, gate.isCurrent(second))
    }

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
