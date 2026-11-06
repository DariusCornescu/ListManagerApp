package com.darius.listmanager.usecase

import com.darius.listmanager.data.usecase.UndoAddUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UndoAddUseCaseTest {
    private var written: Int? = null

    private fun undo(current: Int?, added: Int) = runBlocking {
        UndoAddUseCase(
            currentQuantity = { _, _ -> current },
            setQuantity = { _, _, quantity -> written = quantity },
        ).execute(sessionId = 1, productId = 2, added = added)
    }

    @Test fun removesTheProduct_whenThisAddWasAllThereWas() {
        undo(current = 2, added = 2)
        assertEquals(0, written)
    }

    @Test fun keepsWhatWasThereBeforeTheAdd() {
        undo(current = 5, added = 2)
        assertEquals(3, written)
    }

    @Test fun keepsWhatSomeoneElseAddedAfterwards() {
        undo(current = 9, added = 1)
        assertEquals(8, written)
    }

    @Test fun neverGoesBelowZero_whenSomeoneLoweredItMeanwhile() {
        undo(current = 1, added = 3)
        assertEquals(0, written)
    }

    @Test fun doesNothing_whenTheProductIsAlreadyGone() {
        undo(current = null, added = 2)
        assertNull(written)
    }
}
