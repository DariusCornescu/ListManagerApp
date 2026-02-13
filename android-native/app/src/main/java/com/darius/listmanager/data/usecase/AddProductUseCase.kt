package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.repository.SessionRepository

class AddProductUseCase(
    private val sessionRepository: SessionRepository
) {

    /**
     * Add product to session with specified quantity
     * If product already exists, increments quantity
     */
    suspend fun execute(sessionId: Long, productId: Long, quantity: Int = 1) {
        require(quantity > 0) { "Quantity must be positive" }

        sessionRepository.addOrIncrementItem(sessionId, productId, quantity)
    }
}