package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.darius.listmanager.data.local.entity.ProductEntity
import com.darius.listmanager.data.local.entity.UnknownProductEntity
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.ProductFormContent
import com.darius.listmanager.ui.components.productFormCanSave
import com.darius.listmanager.ui.screens.ResolveContent
import com.darius.listmanager.ui.screens.resolveSubtitle
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.ReviewItem
import com.darius.listmanager.util.RankedProduct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ResolveScreenUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sugar = RankedProduct(ProductEntity(1, "Zahăr 1 kg", 1), 70.0)
    private val brownSugar = RankedProduct(ProductEntity(2, "Zahăr brun 500 g", 1), 65.0)
    private val review = ReviewItem(10, "zahar", listOf(sugar, brownSugar))
    private val paint = UnknownProductEntity(id = 20, spokenText = "vopsea")

    private val picked = mutableListOf<RankedProduct>()
    private val movedToUnknown = mutableListOf<Long>()
    private val added = mutableListOf<Long>()
    private val deleted = mutableListOf<Long>()

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
    private fun Screen(
        reviewItems: List<ReviewItem> = listOf(review),
        unknownItems: List<UnknownProductEntity> = listOf(paint),
        admin: Boolean = true,
        dark: Boolean = false,
        loading: Boolean = false,
    ) {
        AppTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column {
                    AppTopBar("De rezolvat", subtitle = resolveSubtitle(reviewItems.size + unknownItems.size), onBack = {})
                    ResolveContent(
                        reviewItems = reviewItems,
                        unknownItems = unknownItems,
                        isLoading = loading,
                        hasError = false,
                        canAddToCatalog = admin,
                        onPick = { _, candidate -> picked += candidate },
                        onNoneOfThese = { movedToUnknown += it.id },
                        onAdd = { added += it.id },
                        onDelete = { deleted += it.id },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Test fun bothKinds_appearOnOneScreen_withTheirActions() {
        rule.setContent { Screen() }

        rule.onNodeWithText("De verificat").assertExists()
        rule.onNodeWithText("Necunoscute").assertExists()
        rule.onNodeWithText("2 lucruri").assertExists()
        shoot("resolve-light")

        rule.onNodeWithText("Zahăr brun 500 g").performClick()
        rule.onNodeWithText("Niciunul, mută la necunoscute").performClick()
        rule.onNodeWithText("Adaugă").performClick()
        rule.onNodeWithContentDescription("Șterge „vopsea”").performClick()

        assertEquals(listOf(brownSugar), picked)
        assertEquals(listOf(10L), movedToUnknown)
        assertEquals(listOf(20L), added)
        assertEquals(listOf(20L), deleted)
    }

    @Test fun darkTheme() {
        rule.setContent { Screen(dark = true) }

        rule.onNodeWithText("Zahăr 1 kg").assertExists()
        shoot("resolve-dark")
    }

    @Test fun nonAdmin_cannotAddToTheCatalog_andIsToldWhy() {
        rule.setContent { Screen(admin = false) }

        rule.onNodeWithText("Adaugă").assertDoesNotExist()
        rule.onNodeWithText("Doar un administrator poate adăuga produse în catalog.").assertExists()
        rule.onNodeWithContentDescription("Șterge „vopsea”").performClick()
        assertEquals(listOf(20L), deleted)
    }

    @Test fun admin_seesNoRoleNote() {
        rule.setContent { Screen() }

        rule.onNodeWithText("Doar un administrator poate adăuga produse în catalog.").assertDoesNotExist()
    }

    @Test fun phraseWithNoMatch_canStillBeMoved() {
        val noMatch = ReviewItem(11, "xyz", emptyList())
        rule.setContent { Screen(reviewItems = listOf(noMatch), unknownItems = emptyList()) }

        rule.onNodeWithText("Nu am găsit nicio potrivire.").assertExists()
        rule.onNodeWithText("Niciunul, mută la necunoscute").performClick()

        assertEquals(listOf(11L), movedToUnknown)
    }

    @Test fun nothingLeft_saysSo() {
        rule.setContent { Screen(reviewItems = emptyList(), unknownItems = emptyList()) }

        rule.onNodeWithText("Tot ce ai dictat a ajuns unde trebuie.").assertExists()
        shoot("resolve-empty")
    }

    @Test fun loading_showsPlaceholders() {
        rule.setContent { Screen(loading = true) }

        rule.onNodeWithContentDescription("Se încarcă").assertExists()
    }

    @Composable
    private fun Form(onSave: (String, String, String) -> Unit = { _, _, _ -> }, onDismiss: () -> Unit = {}) {
        AppTheme(darkTheme = false) {
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                ProductFormContent(
                    title = "Adaugă în catalog",
                    subtitle = "Ai zis „vopsea”.",
                    initialName = "vopsea",
                    distributors = listOf("Distribuitor A", "Distribuitor B"),
                    onDismiss = onDismiss,
                    onSave = onSave,
                )
            }
        }
    }

    @Test fun form_savesOnlyWithANameAndADistributor() {
        assertFalse(productFormCanSave("", "Distribuitor A"))
        assertFalse(productFormCanSave("  ", "Distribuitor A"))
        assertFalse(productFormCanSave("Vopsea", ""))
        assertTrue(productFormCanSave("Vopsea", "Distribuitor A"))
    }

    @Test fun form_startsWithTheSpokenName_andCannotSaveWithoutADistributor() {
        rule.setContent { Form() }

        rule.onNodeWithText("vopsea").assertExists()
        rule.onNodeWithText("Salvează").assertIsNotEnabled()
        shoot("product-form")
    }

    @Test fun form_acceptsANewDistributor_andTrimsWhatItSaves() {
        var saved: Triple<String, String, String>? = null
        rule.setContent { Form(onSave = { name, distributor, aliases -> saved = Triple(name, distributor, aliases) }) }

        rule.onNodeWithText("Distribuitor *").performClick()
        rule.onNodeWithText("+ Distribuitor nou").performClick()
        rule.onNode(hasSetTextAction() and hasText("Nume distribuitor nou *")).performTextInput("  Distribuitor C  ")
        rule.onNodeWithText("Salvează").assertIsEnabled().performClick()

        assertEquals(Triple("vopsea", "Distribuitor C", ""), saved)
    }

    @Test fun form_cancelDoesNotSave() {
        var saves = 0
        var dismissals = 0
        rule.setContent { Form(onSave = { _, _, _ -> saves++ }, onDismiss = { dismissals++ }) }

        rule.onNodeWithText("Anulează").performClick()

        assertEquals(0, saves)
        assertEquals(1, dismissals)
    }
}
