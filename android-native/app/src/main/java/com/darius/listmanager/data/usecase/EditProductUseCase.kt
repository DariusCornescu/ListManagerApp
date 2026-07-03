package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.RepoResult

class EditProductUseCase(
    private val productRepository: ProductRepository
) {

    suspend fun execute( productId: Long, productName: String, aliases: String): RepoResult {
        require(productName.isNotBlank()) { "Product name cannot be blank" }

        val product = productRepository.getById(productId)
            ?: throw IllegalArgumentException("Product not found")

        val updatedProduct = product.copy(
            name = productName.trim(),
            aliases = aliases.trim()
        )

        return productRepository.update(updatedProduct)
    }
}