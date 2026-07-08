package com.darius.listmanager.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfRepositoryPaginationTest {

    @Test
    fun maxRowsPerPage_firstPageCarriesDocumentHeader() {
        assertEquals(20, PdfRepository.maxRowsPerPage(firstPage = true))
        assertEquals(23, PdfRepository.maxRowsPerPage(firstPage = false))
    }

    @Test
    fun countPages_emptyListIsOnePage() {
        assertEquals(1, PdfRepository.countPages(0))
    }

    @Test
    fun countPages_upToFirstPageCapacityIsOnePage() {
        assertEquals(1, PdfRepository.countPages(1))
        assertEquals(1, PdfRepository.countPages(20))
    }

    @Test
    fun countPages_continuationPagesFitMoreRows() {
        assertEquals(2, PdfRepository.countPages(21))
        // 41 items = 20 on page 1 + 21 on page 2; ceil(41 / 20) would wrongly say 3
        assertEquals(2, PdfRepository.countPages(41))
        assertEquals(2, PdfRepository.countPages(43)) // 20 + 23 exactly
        assertEquals(3, PdfRepository.countPages(44))
    }
}
