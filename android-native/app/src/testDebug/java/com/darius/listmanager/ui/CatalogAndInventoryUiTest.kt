package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import com.darius.listmanager.data.local.entity.DistributorEntity
import com.darius.listmanager.data.local.entity.InventoryItemEntity
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.speech.SpeechState
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.screens.CatalogEmptyState
import com.darius.listmanager.ui.screens.CatalogList
import com.darius.listmanager.ui.screens.CatalogSearchField
import com.darius.listmanager.ui.screens.CatalogSelectionTopBar
import com.darius.listmanager.ui.screens.EditProductContent
import com.darius.listmanager.ui.screens.EditProductFooter
import com.darius.listmanager.ui.screens.InventoryContent
import com.darius.listmanager.ui.screens.InventoryEditContent
import com.darius.listmanager.ui.screens.InventoryFooter
import com.darius.listmanager.ui.screens.applyInventoryEdit
import com.darius.listmanager.ui.screens.catalogSubtitle
import com.darius.listmanager.ui.screens.inventoryDetail
import com.darius.listmanager.ui.screens.inventorySubtitle
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.DistributorWithProducts
import com.darius.listmanager.ui.viewmodel.EditProductUiState
import com.darius.listmanager.ui.viewmodel.InventoryUiState
import com.darius.listmanager.ui.viewmodel.filterCatalog
import com.darius.listmanager.ui.viewmodel.productSearchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CatalogAndInventoryUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val bread = ProductEntity(1, "Pâine albă", 1, aliases = "franzelă")
    private val milk = ProductEntity(2, "Lapte 1 L", 1)
    private val flour = ProductEntity(3, "Făină albă 1 kg", 2)
    private val groupA = DistributorWithProducts(DistributorEntity(1, "Distribuitor A"), listOf(bread, milk))
    private val groupB = DistributorWithProducts(DistributorEntity(2, "Distribuitor B"), listOf(flour))
    private val catalog = listOf(groupA, groupB)
    private val keys = listOf(bread, milk, flour).associate { it.id to productSearchKey(it) }

    private val longPressed = mutableListOf<Long>()
    private val clicked = mutableListOf<Long>()
    private val edited = mutableListOf<Long>()

    @Before fun turnAnimationsOff() {
        Settings.Global.putFloat(rule.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    private fun shoot(name: String) {
        rule.waitForIdle()
        runCatching {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/ui-snapshots").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }.onFailure { println("Snapshot skipped: ${it.message}") }
    }

    @Composable
    private fun Page(dark: Boolean = false, content: @Composable () -> Unit) {
        AppTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }

    @Composable
    private fun Catalog(
        items: List<DistributorWithProducts> = catalog,
        admin: Boolean = true,
        selecting: Boolean = false,
        searching: Boolean = false,
        selected: Set<Long> = emptySet(),
        dark: Boolean = false,
    ) {
        Page(dark) {
            Column {
                AppTopBar("Catalog", subtitle = catalogSubtitle(3196, 67), onBack = {})
                CatalogSearchField(if (searching) "paine" else "", {}, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                CatalogList(
                    catalog = items,
                    searching = searching,
                    isAdmin = admin,
                    isSelectionMode = selecting,
                    selectedProductIds = selected,
                    onProductLongPress = { longPressed += it },
                    onProductClick = { clicked += it },
                    onEditProduct = { edited += it },
                )
            }
        }
    }

    // ---------- Search ----------

    @Test fun search_ignoresDiacriticsAndCase_inNamesAndSynonyms() {
        assertEquals(listOf(bread), filterCatalog(catalog, "PAINE", keys).single().products)
        assertEquals(listOf(bread), filterCatalog(catalog, "franzela", keys).single().products)
        assertEquals(listOf(flour), filterCatalog(catalog, "faina", keys).single().products)
    }

    @Test fun search_byDistributorName_keepsAllItsProducts() {
        assertEquals(listOf(bread, milk), filterCatalog(catalog, "distribuitor a", keys).single().products)
    }

    @Test fun search_withNoMatch_isEmpty_andABlankSearchShowsEverything() {
        assertTrue(filterCatalog(catalog, "vopsea", keys).isEmpty())
        assertEquals(catalog, filterCatalog(catalog, "   ", keys))
    }

    @Test fun subtitle_usesRomanianThousands() {
        assertEquals("3.196 produse · 67 distribuitori", catalogSubtitle(3196, 67))
        assertEquals("1 produs · 1 distribuitor", catalogSubtitle(1, 1))
    }

    // ---------- Catalog ----------

    @Test fun groups_startClosed_andOpenOnTap() {
        rule.setContent { Catalog() }

        rule.onNodeWithText("Pâine albă").assertDoesNotExist()
        shoot("catalog-closed")
        rule.onNodeWithText("Distribuitor A").performClick()
        rule.onNodeWithText("Pâine albă").assertExists()
        rule.onNodeWithText("Sinonime: franzelă").assertExists()
        shoot("catalog-open")
        rule.onNodeWithText("Distribuitor A").performClick()
        rule.onNodeWithText("Pâine albă").assertDoesNotExist()
    }

    @Test fun searching_opensTheGroupsThatMatch() {
        rule.setContent { Catalog(items = filterCatalog(catalog, "paine", keys), searching = true) }

        rule.onNodeWithText("Pâine albă").assertExists()
        rule.onNodeWithText("Distribuitor B").assertDoesNotExist()
        shoot("catalog-search")
    }

    @Test fun admin_canEditFromARow_andLongPressStartsSelecting() {
        rule.setContent { Catalog(searching = true) }

        rule.onNodeWithContentDescription("Editează Lapte 1 L").performClick()
        rule.onNodeWithText("Lapte 1 L").performTouchInput { longClick() }

        assertEquals(listOf(2L), edited)
        assertEquals(listOf(2L), longPressed)
    }

    @Test fun nonAdmin_seesNoEditButton() {
        rule.setContent { Catalog(admin = false, searching = true) }

        rule.onNodeWithText("Lapte 1 L").assertExists()
        rule.onNodeWithContentDescription("Editează Lapte 1 L").assertDoesNotExist()
    }

    @Test fun selection_showsWhatsChosen_inBothThemes() {
        rule.setContent { Catalog(selecting = true, selected = setOf(1L), dark = true) }

        rule.onNodeWithContentDescription("Pâine albă, selectat").assertExists()
        rule.onNodeWithText("Făină albă 1 kg").performClick()
        assertEquals(listOf(3L), clicked)
        shoot("catalog-selection-dark")
    }

    @Test fun selectionBar_canOnlyDeleteWithSomethingChosen() {
        var count by mutableStateOf(0)
        var cleared = 0
        var all = 0
        rule.setContent {
            Page { CatalogSelectionTopBar(count, 3, {}, {}, { all++ }, { cleared++ }) }
        }

        rule.onNodeWithContentDescription("Șterge produsele selectate").assertIsNotEnabled()
        rule.onNodeWithText("Toate").performClick()
        assertEquals(1, all)

        count = 3
        rule.onNodeWithContentDescription("Șterge produsele selectate").assertIsEnabled()
        rule.onNodeWithText("3 selectate").assertExists()
        rule.onNodeWithText("Niciunul").performClick()
        assertEquals(1, cleared)
    }

    @Test fun noResults_suggestsAnotherWord() {
        rule.setContent { Page { CatalogEmptyState("vopsea") } }

        rule.onNodeWithText("Niciun rezultat").assertExists()
    }

    // ---------- Editing a product ----------

    private val editing = EditProductUiState(
        productId = 1,
        productName = "Ciment Portland 40 kg",
        distributorName = "Distribuitor C",
        aliases = "ciment",
        isLoading = false,
        isAdmin = true,
    )

    @Composable
    private fun Edit(state: EditProductUiState, onSave: () -> Unit = {}) {
        Page {
            Column {
                AppTopBar("Editează produsul", onBack = {})
                EditProductContent(state, {}, {}, {}, Modifier.weight(1f))
                EditProductFooter(state, onSave)
            }
        }
    }

    @Test fun edit_savesOnlyRealChanges() {
        rule.setContent { Edit(editing) }

        rule.onNodeWithText("Salvează modificările").assertIsNotEnabled()
        shoot("product-edit")
    }

    @Test fun edit_withChanges_canSave_andSaysThereAreSome() {
        var saves = 0
        rule.setContent { Edit(editing.copy(hasChanges = true), onSave = { saves++ }) }

        rule.onNodeWithText("Ai modificări nesalvate.").assertExists()
        rule.onNodeWithText("Salvează modificările").assertIsEnabled().performClick()
        assertEquals(1, saves)
    }

    @Test fun edit_blankName_explainsAndBlocksSaving() {
        rule.setContent { Edit(editing.copy(productName = "  ", hasChanges = true)) }

        rule.onNodeWithText("Scrie numele produsului.").assertExists()
        rule.onNodeWithText("Salvează modificările").assertIsNotEnabled()
    }

    @Test fun edit_loadError_offersRetry() {
        var retries = 0
        rule.setContent {
            Page {
                EditProductContent(editing.copy(error = "Produsul nu mai există."), {}, {}, { retries++ })
            }
        }

        rule.onNodeWithText("Produsul nu mai există.").assertExists()
        rule.onNodeWithText("Încearcă din nou").performClick()
        assertEquals(1, retries)
    }

    // ---------- Inventory ----------

    private fun row(id: Long, name: String, quantity: Double?, priceBani: Long?) = InventoryItemEntity(
        id = id, name = name, quantity = quantity, priceBani = priceBani, createdAt = 0, itemUuid = "uuid-$id",
    )

    private val rows = listOf(
        row(1, "Pâine albă", 12.0, 450),
        row(2, "Lapte 1 L", 8.0, 720),
        row(3, "Făină albă 1 kg", 5.0, null),
        row(4, "Ulei de floarea-soarelui 1 L", null, 1190),
    )

    @Composable
    private fun Inventory(state: InventoryUiState, dark: Boolean = false, onEdit: (InventoryItemEntity) -> Unit = {}) {
        Page(dark) {
            Column {
                AppTopBar("Inventar", subtitle = inventorySubtitle(state.items.size), onBack = {})
                InventoryContent(state.items, onEdit, Modifier.weight(1f))
                InventoryFooter(state, {}, {}, {})
            }
        }
    }

    @Test fun inventory_showsTheSum_orWhatsMissing() {
        assertEquals("12 × 4,50 lei", inventoryDetail(rows[0]))
        assertEquals("Lipsește prețul", inventoryDetail(rows[2]))
        assertEquals("Lipsește cantitatea", inventoryDetail(rows[3]))
        assertEquals("Lipsesc cantitatea și prețul", inventoryDetail(row(5, "x", null, null)))
    }

    @Test fun inventory_rowsOpenTheirEditor() {
        var editedRow: InventoryItemEntity? = null
        rule.setContent { Inventory(InventoryUiState(items = rows, totalBani = 11160), onEdit = { editedRow = it }) }

        rule.onNodeWithText("111,60 lei").assertExists()
        shoot("inventory-light")
        rule.onNodeWithText("Lapte 1 L").performClick()
        assertEquals(rows[1], editedRow)
    }

    @Test fun inventory_darkTheme() {
        rule.setContent { Inventory(InventoryUiState(items = rows, totalBani = 11160), dark = true) }

        shoot("inventory-dark")
    }

    @Test fun inventory_empty_explainsWhatToSay_andCannotExport() {
        rule.setContent { Inventory(InventoryUiState()) }

        rule.onNodeWithText("Inventarul e gol").assertExists()
        rule.onNodeWithText("Export PDF").assertIsNotEnabled()
        shoot("inventory-empty")
    }

    @Test fun inventory_listening_showsWhatItHeard() {
        rule.setContent {
            Inventory(
                InventoryUiState(items = rows, speechState = SpeechState.Partial("zahăr zece"), partialText = "zahăr zece"),
            )
        }

        rule.onNodeWithText("zahăr zece").assertExists()
        shoot("inventory-listening")
    }

    @Test fun inventoryEdit_commaDecimals_andBlankMeansUnknown() {
        val item = rows[0]
        val updated = applyInventoryEdit(item, " Pâine ", "2,5", "4,50")
        assertEquals("Pâine", updated.name)
        assertEquals(2.5, updated.quantity!!, 0.0)
        assertEquals(450L, updated.priceBani)

        val blanked = applyInventoryEdit(item, "  ", "", "abc")
        assertEquals(item.name, blanked.name)
        assertNull(blanked.quantity)
        assertNull(blanked.priceBani)
    }

    @Test fun inventoryEdit_savesDeletesOrCancels() {
        var saved: InventoryItemEntity? = null
        var deletes = 0
        var cancels = 0
        rule.setContent {
            Page {
                val item = remember { rows[0] }
                InventoryEditContent(item, { saved = it }, { deletes++ }, { cancels++ })
            }
        }

        shoot("inventory-edit")
        rule.onNodeWithText("Salvează").performClick()
        rule.onNodeWithText("Șterge rândul").performClick()
        rule.onNodeWithText("Anulează").performClick()

        assertEquals(rows[0], saved)
        assertEquals(1, deletes)
        assertEquals(1, cancels)
    }
}
