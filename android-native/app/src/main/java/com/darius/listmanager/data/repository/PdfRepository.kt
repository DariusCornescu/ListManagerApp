package com.darius.listmanager.data.repository

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PdfRepository(private val context: Context) {

    companion object {
        private const val PDF_DIR = "ListManager_PDFs"
        private const val PAGE_WIDTH = 595 // A4 width in points
        private const val PAGE_HEIGHT = 842 // A4 height in points
        private const val MARGIN = 50f
        private const val HEADER_HEIGHT = 120f
        private const val FOOTER_HEIGHT = 50f
        private const val ROW_HEIGHT = 30f
        private const val CELL_PADDING = 8f
    }

    suspend fun upsertDistributorPdf(
        distributorName: String,
        sessionDate: Long,
        items: List<PdfItem>
    ): File = withContext(Dispatchers.IO) {

        val pdfDocument = PdfDocument()
        
        // Paint objects
        val titlePaint = Paint().apply {
            textSize = 24f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val headerPaint = Paint().apply {
            textSize = 16f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            textSize = 12f
            isAntiAlias = true
        }

        val smallPaint = Paint().apply {
            textSize = 10f
            color = android.graphics.Color.GRAY
            isAntiAlias = true
        }

        val tableBorderPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }

        val tableHeaderBgPaint = Paint().apply {
            color = android.graphics.Color.LTGRAY
            style = Paint.Style.FILL
        }

        // Calculate column widths
        val tableWidth = PAGE_WIDTH - (2 * MARGIN)
        val qtyColWidth = 80f
        val sizeColWidth = 120f
        val productColWidth = tableWidth - qtyColWidth - sizeColWidth

        val qtyColStart = MARGIN
        val productColStart = qtyColStart + qtyColWidth
        val sizeColStart = productColStart + productColWidth

        // Calculate total pages needed
        val maxItemsPerPage = ((PAGE_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 60f) / ROW_HEIGHT).toInt()
        val totalPages = kotlin.math.ceil(items.size.toDouble() / maxItemsPerPage).toInt()

        var currentPage = 1
        var itemIndex = 0

        while (itemIndex < items.size) {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, currentPage).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            var yPosition = MARGIN + 30

            // === HEADER SECTION (only on first page) ===
            if (currentPage == 1) {
                canvas.drawText("Order List", MARGIN, yPosition, titlePaint)
                yPosition += 40

                canvas.drawText("Distributor: $distributorName", MARGIN, yPosition, headerPaint)
                yPosition += 25

                val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                canvas.drawText("Date: ${dateFormat.format(Date(sessionDate))}", MARGIN, yPosition, bodyPaint)
                yPosition += 30

                canvas.drawLine(MARGIN, yPosition, PAGE_WIDTH - MARGIN, yPosition, bodyPaint)
                yPosition += 20
            } else {
                // Continuation page - just add spacing
                yPosition = MARGIN + 20
            }

            // === TABLE HEADER ===
            val tableHeaderY = yPosition

            // Draw header background
            canvas.drawRect( qtyColStart,tableHeaderY,PAGE_WIDTH - MARGIN,tableHeaderY + ROW_HEIGHT,tableHeaderBgPaint)

            // Draw header borders
            canvas.drawRect(qtyColStart,tableHeaderY,PAGE_WIDTH - MARGIN,tableHeaderY + ROW_HEIGHT,tableBorderPaint)

            // Vertical separators in header
            canvas.drawLine(productColStart, tableHeaderY, productColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(sizeColStart, tableHeaderY, sizeColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)

            // Header text (centered vertically in cell)
            val headerTextY = tableHeaderY + (ROW_HEIGHT / 2) + 5
            canvas.drawText("Qty", qtyColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Product", productColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Size/Type", sizeColStart + CELL_PADDING, headerTextY, headerPaint)

            yPosition = tableHeaderY + ROW_HEIGHT

            // === TABLE BODY ===
            val pageItemLimit = if (currentPage == 1) {
                ((PAGE_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 60f) / ROW_HEIGHT).toInt()
            } else {
                ((PAGE_HEIGHT - FOOTER_HEIGHT - 80f) / ROW_HEIGHT).toInt()
            }

            var rowCount = 0
            while (itemIndex < items.size && rowCount < pageItemLimit) {
                val item = items[itemIndex]
                val rowY = yPosition

                // Draw row border
                canvas.drawRect( qtyColStart, rowY, PAGE_WIDTH - MARGIN, rowY + ROW_HEIGHT, tableBorderPaint )

                // Vertical separators
                canvas.drawLine(productColStart, rowY, productColStart, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(sizeColStart, rowY, sizeColStart, rowY + ROW_HEIGHT, tableBorderPaint)

                // Cell content (centered vertically)
                val textY = rowY + (ROW_HEIGHT / 2) + 5

                // Quantity (right-aligned in its column)
                val qtyText = "${item.quantity}"
                val qtyTextWidth = bodyPaint.measureText(qtyText)
                canvas.drawText( qtyText, productColStart - qtyTextWidth - CELL_PADDING, textY, bodyPaint )

                // Product name (with text truncation if needed)
                val productText = truncateText(item.productName, productColWidth - (2 * CELL_PADDING), bodyPaint)
                canvas.drawText(productText, productColStart + CELL_PADDING, textY, bodyPaint)

                // Size/Type
                if (item.size != null) {
                    val sizeText = truncateText(item.size, sizeColWidth - (2 * CELL_PADDING), bodyPaint)
                    canvas.drawText(sizeText, sizeColStart + CELL_PADDING, textY, bodyPaint)
                }

                yPosition += ROW_HEIGHT
                rowCount++
                itemIndex++
            }

            // === FOOTER SECTION ===
            // Summary on last page only
            if (itemIndex >= items.size) {
                yPosition += 20

                // Draw total row
                canvas.drawRect( qtyColStart, yPosition, PAGE_WIDTH - MARGIN, yPosition + ROW_HEIGHT, tableHeaderBgPaint )
                canvas.drawRect( qtyColStart, yPosition, PAGE_WIDTH - MARGIN, yPosition + ROW_HEIGHT, tableBorderPaint )

                val totalItems = items.sumOf { it.quantity }
                val totalTextY = yPosition + (ROW_HEIGHT / 2) + 5
                canvas.drawText( "Total Items: $totalItems", qtyColStart + CELL_PADDING, totalTextY, headerPaint )
            }

            canvas.drawText( "Page $currentPage of $totalPages", PAGE_WIDTH - MARGIN - 80, PAGE_HEIGHT - MARGIN + 20, smallPaint )
            canvas.drawText( "Generated by List Manager App", MARGIN, PAGE_HEIGHT - MARGIN + 20, smallPaint )
            pdfDocument.finishPage(page)
            currentPage++
        }

        val fileName = "Order_${distributorName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
        val file = File(getPdfDirectory(), fileName)

        FileOutputStream(file).use { outputStream ->
            pdfDocument.writeTo(outputStream)
        }

        pdfDocument.close()

        return@withContext file
    }

    private fun truncateText(text: String, maxWidth: Float, paint: Paint): String {
        val textWidth = paint.measureText(text)
        if (textWidth <= maxWidth) return text

        var truncated = text
        val ellipsis = "..."
        while (paint.measureText(truncated + ellipsis) > maxWidth && truncated.isNotEmpty()) {
            truncated = truncated.dropLast(1)
        }
        return truncated + ellipsis
    }

    private fun getPdfDirectory(): File {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            PDF_DIR
        )

        if (!directory.exists()) {
            directory.mkdirs()
        }

        return directory
    }

    suspend fun getAllPdfs(): List<File> = withContext(Dispatchers.IO) {
        val directory = getPdfDirectory()
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    /**
     * Render the inventory list as one PDF: Produs | Cant. | Pret | Valoare
     * with a grand-total row on the last page. Rows arrive pre-formatted.
     */
    suspend fun createInventoryPdf(
        sessionDate: Long,
        items: List<InventoryPdfRow>,
        totalText: String
    ): File = withContext(Dispatchers.IO) {

        val pdfDocument = PdfDocument()

        val titlePaint = Paint().apply { textSize = 24f; isFakeBoldText = true; isAntiAlias = true }
        val headerPaint = Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true }
        val bodyPaint = Paint().apply { textSize = 12f; isAntiAlias = true }
        val smallPaint = Paint().apply { textSize = 10f; color = android.graphics.Color.GRAY; isAntiAlias = true }
        val tableBorderPaint = Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = 1f; style = Paint.Style.STROKE }
        val tableHeaderBgPaint = Paint().apply { color = android.graphics.Color.LTGRAY; style = Paint.Style.FILL }

        // Columns: name (flexible) | qty 70 | price 90 | value 100
        val qtyColWidth = 70f
        val priceColWidth = 90f
        val valueColWidth = 100f
        val tableWidth = PAGE_WIDTH - (2 * MARGIN)
        val nameColWidth = tableWidth - qtyColWidth - priceColWidth - valueColWidth

        val nameColStart = MARGIN
        val qtyColStart = nameColStart + nameColWidth
        val priceColStart = qtyColStart + qtyColWidth
        val valueColStart = priceColStart + priceColWidth
        val tableEnd = PAGE_WIDTH - MARGIN

        val maxItemsPerPage = ((PAGE_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 60f) / ROW_HEIGHT).toInt()
        val totalPages = maxOf(1, kotlin.math.ceil(items.size.toDouble() / maxItemsPerPage).toInt())

        var currentPage = 1
        var itemIndex = 0

        do {
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, currentPage).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            var yPosition = MARGIN + 30

            if (currentPage == 1) {
                canvas.drawText("Inventar", MARGIN, yPosition, titlePaint)
                yPosition += 40
                val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                canvas.drawText("Data: ${dateFormat.format(Date(sessionDate))}", MARGIN, yPosition, bodyPaint)
                yPosition += 30
                canvas.drawLine(MARGIN, yPosition, tableEnd, yPosition, bodyPaint)
                yPosition += 20
            } else {
                yPosition = MARGIN + 20
            }

            // table header
            val tableHeaderY = yPosition
            canvas.drawRect(nameColStart, tableHeaderY, tableEnd, tableHeaderY + ROW_HEIGHT, tableHeaderBgPaint)
            canvas.drawRect(nameColStart, tableHeaderY, tableEnd, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(qtyColStart, tableHeaderY, qtyColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(priceColStart, tableHeaderY, priceColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)
            canvas.drawLine(valueColStart, tableHeaderY, valueColStart, tableHeaderY + ROW_HEIGHT, tableBorderPaint)

            val headerTextY = tableHeaderY + (ROW_HEIGHT / 2) + 5
            canvas.drawText("Produs", nameColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Cant.", qtyColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Pret", priceColStart + CELL_PADDING, headerTextY, headerPaint)
            canvas.drawText("Valoare", valueColStart + CELL_PADDING, headerTextY, headerPaint)

            yPosition = tableHeaderY + ROW_HEIGHT

            val pageItemLimit = if (currentPage == 1) {
                ((PAGE_HEIGHT - HEADER_HEIGHT - FOOTER_HEIGHT - 60f) / ROW_HEIGHT).toInt()
            } else {
                ((PAGE_HEIGHT - FOOTER_HEIGHT - 80f) / ROW_HEIGHT).toInt()
            }

            var rowCount = 0
            while (itemIndex < items.size && rowCount < pageItemLimit) {
                val item = items[itemIndex]
                val rowY = yPosition

                canvas.drawRect(nameColStart, rowY, tableEnd, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(qtyColStart, rowY, qtyColStart, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(priceColStart, rowY, priceColStart, rowY + ROW_HEIGHT, tableBorderPaint)
                canvas.drawLine(valueColStart, rowY, valueColStart, rowY + ROW_HEIGHT, tableBorderPaint)

                val textY = rowY + (ROW_HEIGHT / 2) + 5
                val nameText = truncateText(item.name, nameColWidth - (2 * CELL_PADDING), bodyPaint)
                canvas.drawText(nameText, nameColStart + CELL_PADDING, textY, bodyPaint)

                // numbers right-aligned within their columns
                val qtyW = bodyPaint.measureText(item.quantityText)
                canvas.drawText(item.quantityText, priceColStart - qtyW - CELL_PADDING, textY, bodyPaint)
                val priceW = bodyPaint.measureText(item.priceText)
                canvas.drawText(item.priceText, valueColStart - priceW - CELL_PADDING, textY, bodyPaint)
                val valueW = bodyPaint.measureText(item.valueText)
                canvas.drawText(item.valueText, tableEnd - valueW - CELL_PADDING, textY, bodyPaint)

                yPosition += ROW_HEIGHT
                rowCount++
                itemIndex++
            }

            // grand total on the last page
            if (itemIndex >= items.size) {
                yPosition += 20
                canvas.drawRect(nameColStart, yPosition, tableEnd, yPosition + ROW_HEIGHT, tableHeaderBgPaint)
                canvas.drawRect(nameColStart, yPosition, tableEnd, yPosition + ROW_HEIGHT, tableBorderPaint)
                val totalTextY = yPosition + (ROW_HEIGHT / 2) + 5
                canvas.drawText("Total: $totalText", nameColStart + CELL_PADDING, totalTextY, headerPaint)
            }

            canvas.drawText("Page $currentPage of $totalPages", PAGE_WIDTH - MARGIN - 80, PAGE_HEIGHT - MARGIN + 20, smallPaint)
            canvas.drawText("Generated by List Manager App", MARGIN, PAGE_HEIGHT - MARGIN + 20, smallPaint)
            pdfDocument.finishPage(page)
            currentPage++
        } while (itemIndex < items.size)

        val fileName = "Inventar_${System.currentTimeMillis()}.pdf"
        val file = File(getPdfDirectory(), fileName)
        FileOutputStream(file).use { outputStream -> pdfDocument.writeTo(outputStream) }
        pdfDocument.close()

        return@withContext file
    }
}

data class PdfItem(
    val productName: String,
    val quantity: Int,
    val size: String? = null,
    val barcode: String? = null
)

/** One pre-formatted inventory table row. Formatting lives in InventoryMath. */
data class InventoryPdfRow(
    val name: String,
    val quantityText: String,
    val priceText: String,
    val valueText: String
)