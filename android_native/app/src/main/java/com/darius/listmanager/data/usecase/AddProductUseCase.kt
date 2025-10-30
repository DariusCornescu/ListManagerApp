package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.repository.SessionRepository

class AddProductUseCase(
    private val sessionRepository: SessionRepository
) {

    /**
     * Add product to session with specified quantity
     * If product already exists, increments quantity
     *
     * @param sessionId The session to add to
     * @param productId The product to add
     * @param quantity Quantity to add (default 1)
     */
    suspend fun execute(sessionId: Long, productId: Long, quantity: Int = 1) {
        require(quantity > 0) { "Quantity must be positive" }

        sessionRepository.addOrIncrementItem(sessionId, productId, quantity)
    }
}