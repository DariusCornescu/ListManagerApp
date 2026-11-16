package com.darius.listmanager.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A distributor's name is free text (catalog screen, server import), but it becomes part of the
 * order PDF's file name. A path separator in it used to point the PDF at a folder that does not
 * exist, so FileOutputStream threw and the whole session's PDF generation failed.
 */
class PdfFileNameTest {
    @get:Rule val folder = TemporaryFolder()

    private fun writable(name: String): File {
        val file = File(folder.root, name)
        assertEquals("'$name' must stay inside the PDF folder", folder.root, file.parentFile)
        file.outputStream().use { it.write("%PDF".toByteArray()) }
        return file
    }

    @Test
    fun ordinaryNameKeepsItsReadableShape() {
        assertEquals("Order_Lactate_SRL_42.pdf", PdfRepository.orderPdfFileName("Lactate SRL", 42))
        assertEquals("Order_Panificație_Ilie_42.pdf", PdfRepository.orderPdfFileName("Panificație Ilie", 42))
    }

    @Test
    fun distributorNameWithPathSeparatorStillGetsAWritablePdf() {
        listOf("Metro/Selgros", "A\\B Distribuție", "../escape", "S.C. X/Y S.R.L.").forEach { name ->
            val file = writable(PdfRepository.orderPdfFileName(name, 42))
            assertTrue(file.isFile)
            assertTrue("${file.name} keeps the .pdf extension", file.name.endsWith(".pdf"))
        }
    }
}
