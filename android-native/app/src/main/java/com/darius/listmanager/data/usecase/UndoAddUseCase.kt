package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.repository.SessionRepository

/**
 * Takes back one add from a shared list. It removes only what this add put there, because
 * someone else may have added to the same product since. If the product is already gone,
 * there's nothing to take back.
 */
class UndoAddUseCase internal constructor(
    private val currentQuantity: suspend (sessionId: Long, productId: Long) -> Int?,
    private val setQuantity: suspend (sessionId: Long, productId: Long, quantity: Int) -> Unit,
) {
    constructor(sessionRepository: SessionRepository) : this(
        currentQuantity = { sessionId, productId ->
            sessionRepository.getSessionItems(sessionId).firstOrNull { it.productId == productId }?.quantity
        },
        setQuantity = { sessionId, productId, quantity ->
            sessionRepository.setItemQuantity(sessionId, productId, quantity)
        },
    )

    suspend fun execute(sessionId: Long, productId: Long, added: Int) {
        val current = currentQuantity(sessionId, productId) ?: return
        setQuantity(sessionId, productId, (current - added).coerceAtLeast(0))
    }
}
