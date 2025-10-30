package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.repository.DistributorRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.UnknownRepository

class SaveProductToCatalogUseCase(
    private val productRepository: ProductRepository,
    private val distributorRepository: DistributorRepository,
    private val unknownRepository: UnknownRepository
) {

    /**
     * Save product to catalog
     *
     * @param productName Name of the product
     * @param distributorName Name of the distributor
     * @param aliases Comma-separated list of aliases (optional)
     * @param unknownProductId ID of unknown product to remove (optional)
     * @return The ID of the newly created product
     */
    suspend fun execute(
        productName: String,
        distributorName: String,
        aliases: String = "",
        unknownProductId: Long? = null
    ): Long {
        require(productName.isNotBlank()) { "Product name cannot be blank" }
        require(distributorName.isNotBlank()) { "Distributor name cannot be blank" }

        // Step 1: Upsert distributor (get existing or create new)
        val distributorId = distributorRepository.upsertByName(distributorName.trim())

        // Step 2: Create product
        val product = ProductEntity(
            name = productName.trim(),
            distributorId = distributorId,
            aliases = aliases.trim()
        )

        val productId = productRepository.insert(product)

        // Step 3: Remove from unknown list if ID provided
        unknownProductId?.let { id ->
            unknownRepository.deleteById(id)
        }

        return productId
    }
}