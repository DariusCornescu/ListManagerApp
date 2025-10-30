package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.ProductRepository

/**
 * Use case for editing an existing product
 */
class EditProductUseCase(
    private val productRepository: ProductRepository
) {

    /**
     * Update an existing product
     */
    suspend fun execute(
        productId: Long,
        productName: String,
        aliases: String
    ) {
        require(productName.isNotBlank()) { "Product name cannot be blank" }

        val product = productRepository.getById(productId)
            ?: throw IllegalArgumentException("Product not found")

        val updatedProduct = product.copy(
            name = productName.trim(),
            aliases = aliases.trim()
        )

        productRepository.update(updatedProduct)
    }
}