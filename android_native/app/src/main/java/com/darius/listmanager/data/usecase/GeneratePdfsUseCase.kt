package com.darius.listmanager.data.usecase

import com.darius.listmanager.data.repository.PdfItem
import com.darius.listmanager.data.repository.PdfRepository
import com.darius.listmanager.data.repository.SessionRepository
import java.io.File

class GeneratePdfsUseCase(
    private val sessionRepository: SessionRepository,
    private val pdfRepository: PdfRepository
) {

    /**
     * Generate PDFs for a session, grouped by distributor
     *
     * @param sessionId The session to generate PDFs for
     * @return Map of distributor names to generated PDF files
     */
    suspend fun execute(sessionId: Long): Map<String, File> {
        // Get all items from the session
        val sessionItems = sessionRepository.getSessionItems(sessionId)

        if (sessionItems.isEmpty()) {
            return emptyMap()
        }

        // Group items by distributor
        val itemsByDistributor = sessionItems.groupBy { it.distributorName }

        // Get session date
        val session = sessionRepository.getSessionById(sessionId)
        val sessionDate = session?.createdAt ?: System.currentTimeMillis()

        // Generate one PDF per distributor
        val generatedPdfs = mutableMapOf<String, File>()

        itemsByDistributor.forEach { (distributorName, items) ->
            val pdfItems = items.map { item ->
                PdfItem(
                    productName = item.productName,
                    quantity = item.quantity,
                    size = null, // Can be extended later
                    barcode = null // Can be extended later
                )
            }

            val pdfFile = pdfRepository.upsertDistributorPdf(
                distributorName = distributorName,
                sessionDate = sessionDate,
                items = pdfItems
            )

            generatedPdfs[distributorName] = pdfFile
        }

        return generatedPdfs
    }
}