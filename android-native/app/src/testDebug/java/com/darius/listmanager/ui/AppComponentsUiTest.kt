package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.darius.listmanager.ui.components.AppDangerButton
import com.darius.listmanager.ui.components.AppDangerSoftButton
import com.darius.listmanager.ui.components.AppEmptyState
import com.darius.listmanager.ui.components.AppIconButton
import com.darius.listmanager.ui.components.AppPrimaryButton
import com.darius.listmanager.ui.components.AppSecondaryButton
import com.darius.listmanager.ui.components.AppTextButton
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.BannerTone
import com.darius.listmanager.ui.components.ConfirmSheetContent
import com.darius.listmanager.ui.components.SkeletonRows
import com.darius.listmanager.ui.components.StatusBanner
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.theme.Color_DarkErrorContainer
import com.darius.listmanager.ui.theme.Color_DarkOnErrorContainer
import com.darius.listmanager.ui.theme.Color_LightErrorContainer
import com.darius.listmanager.ui.theme.Color_LightOnErrorContainer
import com.darius.listmanager.ui.theme.DarkOnPrimary
import com.darius.listmanager.ui.theme.DarkPrimary
import com.darius.listmanager.ui.theme.LightOnPrimary
import com.darius.listmanager.ui.theme.LightPrimary
import com.darius.listmanager.ui.theme.isMotionReduced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
class AppComponentsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent { AppTheme(darkTheme = dark, content = content) }
    }

    /** A visual record for pull requests, when the platform can render one. Never fails a test. */
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
    private fun Gallery() {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column {
                AppTopBar("Catalog", subtitle = "3 produse", onBack = {})
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    AppPrimaryButton("Acțiunea principală", {})
                    AppPrimaryButton("Indisponibil", {}, enabled = false)
                    AppPrimaryButton("Salvează", {}, loading = true, loadingText = "Se salvează…")
                    AppSecondaryButton("Acțiune secundară", {})
                    AppDangerSoftButton("Deconectare", {})
                    AppDangerButton("Confirmă ștergerea", {})
                    StatusBanner("Ești offline. Vezi datele salvate pe telefon.")
                    StatusBanner("Cont creat. Te poți autentifica.", tone = BannerTone.Success)
                    StatusBanner(
                        "Nu se poate conecta la server.",
                        tone = BannerTone.Error,
                        actionLabel = "Încearcă din nou",
                    )
                    SkeletonRows(count = 2)
                }
            }
        }
    }

    @Test fun gallery_lightTheme() {
        show(dark = false) { Gallery() }

        rule.onNodeWithText("Acțiunea principală").assertExists()
        shoot("components-light")
    }

    @Test fun gallery_darkTheme() {
        show(dark = true) { Gallery() }

        rule.onNodeWithText("Acțiunea principală").assertExists()
        shoot("components-dark")
    }

    @Test fun primaryButton_clicksWhenEnabled() {
        var clicks = 0
        show { AppPrimaryButton("Salvează", { clicks++ }) }

        rule.onNodeWithText("Salvează").assertIsEnabled().performClick()

        assertEquals(1, clicks)
    }

    @Test fun primaryButton_loading_showsProgressAndCannotBePressedTwice() {
        var clicks = 0
        show { AppPrimaryButton("Salvează", { clicks++ }, loading = true, loadingText = "Se salvează…") }

        rule.onNodeWithText("Salvează").assertDoesNotExist()
        rule.onNodeWithText("Se salvează…").assertIsNotEnabled().performClick()

        assertEquals(0, clicks)
    }

    @Test fun primaryButton_disabled_ignoresPresses() {
        var clicks = 0
        show { AppPrimaryButton("Salvează", { clicks++ }, enabled = false) }

        rule.onNodeWithText("Salvează").assertIsNotEnabled().performClick()

        assertEquals(0, clicks)
    }

    @Test fun everyButtonVariantWorks() {
        var clicks = 0
        show(dark = true) {
            Column {
                AppPrimaryButton("Unu", { clicks++ })
                AppSecondaryButton("Doi", { clicks++ })
                AppDangerSoftButton("Trei", { clicks++ })
                AppDangerButton("Patru", { clicks++ })
                AppTextButton("Cinci", { clicks++ })
                AppIconButton(Icons.Rounded.Check, "Șase", { clicks++ })
            }
        }

        listOf("Unu", "Doi", "Trei", "Patru", "Cinci").forEach { rule.onNodeWithText(it).performClick() }
        rule.onNodeWithContentDescription("Șase").performClick()

        assertEquals(6, clicks)
    }

    @Test fun confirmSheet_confirmAndCancelAreSeparateActions() {
        var confirmed = 0
        var dismissed = 0
        show {
            ConfirmSheetContent(
                title = "Golești lista?",
                message = "Toate produsele vor fi șterse.",
                confirmLabel = "Golește lista",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
            )
        }

        rule.onNodeWithText("Toate produsele vor fi șterse.").assertExists()
        rule.onNodeWithText("Anulează").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)

        rule.onNodeWithText("Golește lista").performClick()
        assertEquals(1, confirmed)
    }

    @Test fun topBar_backArrowIsLabelledAndWorks() {
        var backs = 0
        show(dark = true) { AppTopBar("Catalog", subtitle = "3 produse", onBack = { backs++ }) }

        rule.onNodeWithText("Catalog").assertExists()
        rule.onNodeWithText("3 produse").assertExists()
        rule.onNodeWithContentDescription("Înapoi").performClick()

        assertEquals(1, backs)
    }

    @Test fun topBar_withoutBack_hasNoBackArrow() {
        show { AppTopBar("ListManager") }

        rule.onNodeWithContentDescription("Înapoi").assertDoesNotExist()
    }

    @Test fun emptyState_showsItsAction() {
        show {
            AppEmptyState(
                Icons.Rounded.Check,
                "Nimic de rezolvat",
                message = "Tot ce ai dictat a ajuns unde trebuie.",
                positive = true,
                action = { Text("Înregistrează") },
            )
        }

        rule.onNodeWithText("Nimic de rezolvat").assertExists()
        rule.onNodeWithText("Tot ce ai dictat a ajuns unde trebuie.").assertExists()
        rule.onNodeWithText("Înregistrează").assertExists()
    }

    @Test fun banner_actionOnlyAppearsWhenGiven() {
        var retries = 0
        show {
            StatusBanner(
                "Nu se poate conecta la server.",
                tone = BannerTone.Error,
                actionLabel = "Încearcă din nou",
                onAction = { retries++ },
            )
        }

        rule.onNodeWithText("Încearcă din nou").performClick()

        assertEquals(1, retries)
    }

    @Test fun skeleton_tellsScreenReadersItIsLoading() {
        show { SkeletonRows(count = 2) }

        rule.onNodeWithContentDescription("Se încarcă").assertExists()
    }

    @Test fun reducedMotion_onlyWhenAnimationsAreOff() {
        assertTrue(isMotionReduced(0f))
        assertFalse(isMotionReduced(1f))
        assertFalse(isMotionReduced(0.5f))
    }

    @Test fun errorTextAndGoldButtons_passContrastInBothThemes() {
        val pairs = listOf(
            Color_DarkOnErrorContainer to Color_DarkErrorContainer,
            Color_LightOnErrorContainer to Color_LightErrorContainer,
            DarkOnPrimary to DarkPrimary,
            LightOnPrimary to LightPrimary,
        )
        for ((text, background) in pairs) {
            assertTrue("$text on $background", contrast(text, background) >= 4.5f)
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
