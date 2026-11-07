package com.darius.listmanager.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.darius.listmanager.data.websocket.OnlineUser
import com.darius.listmanager.data.websocket.WebSocketState
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.network.TeamDTO
import com.darius.listmanager.network.TeamMemberDTO
import com.darius.listmanager.ui.components.AppTopBar
import com.darius.listmanager.ui.components.DrawerContent
import com.darius.listmanager.ui.components.CloseDrawerOnBack
import com.darius.listmanager.ui.components.drawerStatus
import com.darius.listmanager.ui.screens.AccountContent
import com.darius.listmanager.ui.screens.AccountFooter
import com.darius.listmanager.ui.screens.EditProfileContent
import com.darius.listmanager.ui.screens.TeamDetailContent
import com.darius.listmanager.ui.screens.TeamDetailFooter
import com.darius.listmanager.ui.screens.TeamInputContent
import com.darius.listmanager.ui.screens.TeamsContent
import com.darius.listmanager.ui.screens.TeamsFooter
import com.darius.listmanager.ui.screens.memberCountLabel
import com.darius.listmanager.ui.screens.roleLabel
import com.darius.listmanager.ui.screens.teamRoleLabel
import com.darius.listmanager.ui.theme.AppTheme
import com.darius.listmanager.ui.viewmodel.ProfileInput
import com.darius.listmanager.ui.viewmodel.workspaceOptions
import org.junit.Assert.assertEquals
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
class AccountAndTeamsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val shop = Workspace.Team(5, "Magazin")
    private val depot = Workspace.Team(6, "Depozit")

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

    // ---------- Account and workspace ----------

    private val picked = mutableListOf<Workspace>()
    private var manageTeams = 0

    @Composable
    private fun Account(current: Workspace = Workspace.Personal, dark: Boolean = false) {
        Page(dark) {
            Column {
                AppTopBar("Contul meu", onBack = {})
                AccountContent(
                    username = "darius",
                    role = "ADMIN",
                    email = "ion@example.com",
                    fullName = "Ion Popescu",
                    company = "Magazinul Exemplu",
                    address = "",
                    currentWorkspace = current,
                    teams = listOf(shop, depot),
                    onPickWorkspace = { picked += it },
                    onManageTeams = { manageTeams++ },
                    modifier = Modifier.weight(1f),
                )
                AccountFooter(canEdit = true, onEdit = {}, onLogout = {})
            }
        }
    }

    @Test fun account_showsProfile_withBlankFieldsAsADash() {
        rule.setContent { Account() }

        rule.onNodeWithText("darius").assertExists()
        rule.onNodeWithText("Administrator").assertExists()
        rule.onNodeWithText("Lista ta").assertExists()
        rule.onNodeWithText("-").assertExists()
        shoot("account-light")
    }

    @Test fun workspace_opensOnTap_andPicksATeam() {
        rule.setContent { Account() }

        rule.onNodeWithText("Magazin").assertDoesNotExist()
        rule.onNodeWithContentDescription("Arată spațiile de lucru").performClick()
        shoot("account-workspaces")
        rule.onNodeWithText("Depozit").performClick()

        assertEquals(listOf<Workspace>(depot), picked)
    }

    @Test fun workspace_marksTheCurrentOne_andLinksToTeams() {
        rule.setContent { Account(current = shop, dark = true) }

        rule.onNodeWithText("Lista comună a echipei").assertExists()
        rule.onNodeWithContentDescription("Arată spațiile de lucru").performClick()
        rule.onNodeWithText("Personal").assertExists()
        shoot("account-workspaces-dark")
        rule.onNodeWithText("Gestionează echipele").performScrollTo().performClick()

        assertEquals(1, manageTeams)
    }

    @Test fun workspaceOptions_keepPersonalFirst_andTheCurrentTeamWhenOffline() {
        assertEquals(listOf<Workspace>(Workspace.Personal, shop, depot), workspaceOptions(Workspace.Personal, listOf(shop, depot)))
        assertEquals(listOf<Workspace>(Workspace.Personal, shop), workspaceOptions(shop, emptyList()))
        assertEquals(listOf<Workspace>(Workspace.Personal, shop, depot), workspaceOptions(shop, listOf(shop, depot)))
    }

    @Test fun roles_readAsWords() {
        assertEquals("Administrator", roleLabel("ADMIN"))
        assertEquals("Utilizator", roleLabel("USER"))
        assertEquals("—", roleLabel(null))
        assertEquals("Administrator", teamRoleLabel("admin"))
        assertEquals("Membru", teamRoleLabel("member"))
    }

    @Test fun editProfile_needsAnEmail_andPassesTheChanges() {
        var saved: Pair<ProfileInput, String>? = null
        rule.setContent {
            Page {
                EditProfileContent(
                    current = ProfileInput("ion@example.com", "Ion", "", ""),
                    isSaving = false,
                    onDismiss = {},
                    onConfirm = { input, password -> saved = input to password },
                )
            }
        }

        shoot("account-edit")
        val email = hasSetTextAction() and hasText("ion@example.com")
        rule.onNode(email).performTextReplacement(" ")
        rule.onNodeWithText("Salvează").assertIsNotEnabled()
        rule.onNode(hasSetTextAction() and hasText(" ")).performTextReplacement("nou@example.com")
        rule.onNodeWithText("Salvează").assertIsEnabled().performClick()

        assertEquals("nou@example.com", saved?.first?.email)
        assertEquals("", saved?.second)
    }

    // ---------- Menu ----------

    @Composable
    private fun Drawer(route: String = "home", dark: Boolean = false, onNavigate: (String) -> Unit = {}) {
        AppTheme(darkTheme = dark) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                    drawerState = rememberDrawerState(DrawerValue.Open),
                    drawerContent = {
                        DrawerContent(
                            onNavigate = onNavigate,
                            workspaceName = "Magazin",
                            currentRoute = route,
                            username = "darius",
                            webSocketState = WebSocketState.Connected,
                            serverReachable = true,
                            onlineUsers = listOf(OnlineUser(1, "darius"), OnlineUser(2, "ana")),
                        )
                    },
                ) {}
            }
        }
    }

    @Test fun menu_isInRomanian_marksThePage_andNavigates() {
        val routes = mutableListOf<String>()
        rule.setContent { Drawer(route = "catalog", onNavigate = { routes += it }) }

        rule.onNodeWithText("Magazin").assertExists()
        rule.onNodeWithText("darius (tu)").assertExists()
        rule.onNodeWithText("Catalog").assertIsSelected()
        shoot("menu-light")
        rule.onNodeWithText("Lista curentă").performClick()
        rule.onNodeWithText("Echipe").performScrollTo().performClick()
        rule.onNodeWithText("Setări").performClick()

        assertEquals(listOf("session", "teams", "settings"), routes)
    }

    @Test fun menu_hasNoWorkspaceSwitcher() {
        rule.setContent { Drawer() }

        rule.onNodeWithText("Spațiu de lucru").assertDoesNotExist()
        rule.onNodeWithText("Workspace").assertDoesNotExist()
    }

    @Test fun menu_darkTheme() {
        rule.setContent { Drawer(dark = true) }

        rule.onNodeWithText("Acasă").assertIsSelected()
        shoot("menu-dark")
    }

    @Test fun menuStatus_saysWhatsHappening() {
        fun status(live: Boolean = false, reachable: Boolean = false, syncing: Boolean = false, pending: Int = 0) =
            drawerStatus(live, reachable, syncing, pending, "darius")

        assertEquals("Conectat live", status(live = true).title)
        assertEquals("Sincronizat · darius", status(live = true).detail)
        assertEquals("Server disponibil", status(reachable = true).title)
        assertEquals("Offline", status().title)
        assertEquals("Datele se salvează pe telefon", status().detail)
        assertEquals("3 în așteptare. Apasă ca să trimiți.", status(pending = 3).detail)
        assertEquals("Se sincronizează…", status(syncing = true, pending = 3).detail)
    }

    // ---------- Teams ----------

    private val teams = listOf(TeamDTO(5, "Magazin", "2026-11-06"), TeamDTO(6, "Depozit", "2026-11-06"))
    private val opened = mutableListOf<Long>()
    private var creates = 0
    private var joins = 0
    private var retries = 0

    @Composable
    private fun Teams(list: List<TeamDTO> = teams, failed: Boolean = false) {
        Page {
            Column {
                AppTopBar("Echipe", onBack = {})
                TeamsContent(list, false, failed, { id, _ -> opened += id }, { retries++ }, Modifier.weight(1f))
                TeamsFooter(enabled = true, onCreate = { creates++ }, onJoin = { joins++ })
            }
        }
    }

    @Test fun teams_openAndOfferBothWaysIn() {
        rule.setContent { Teams() }

        shoot("teams-light")
        rule.onNodeWithText("Depozit").performClick()
        rule.onNodeWithText("Creează echipă").performClick()
        rule.onNodeWithText("Intră cu un cod").performClick()

        assertEquals(listOf(6L), opened)
        assertEquals(1, creates)
        assertEquals(1, joins)
    }

    @Test fun teams_empty_saysWhatToDo() {
        rule.setContent { Teams(list = emptyList()) }

        rule.onNodeWithText("Nicio echipă încă").assertExists()
    }

    @Test fun teams_loadFailure_offersRetry() {
        rule.setContent { Teams(list = emptyList(), failed = true) }

        rule.onNodeWithText("Încearcă din nou").performClick()
        assertEquals(1, retries)
    }

    @Test fun teamInput_needsText_andTrims() {
        var value: String? = null
        rule.setContent {
            Page {
                TeamInputContent("Creează echipă", "Numele echipei", "Creează", { value = it }, {})
            }
        }

        rule.onNodeWithText("Creează").assertIsNotEnabled()
        rule.onNode(hasSetTextAction()).performTextInput("  Magazin  ")
        shoot("team-create")
        rule.onNodeWithText("Creează").assertIsEnabled().performClick()

        assertEquals("Magazin", value)
    }

    // ---------- One team ----------

    private val members = listOf(
        TeamMemberDTO(1, 5, 12, "admin"),
        TeamMemberDTO(2, 5, 18, "member"),
    )
    private val removed = mutableListOf<Long>()
    private var invites = 0

    @Composable
    private fun Team(admin: Boolean, dark: Boolean = false) {
        Page(dark) {
            Column {
                AppTopBar("Magazin", subtitle = memberCountLabel(members.size), onBack = {})
                TeamDetailContent(
                    members, 12, admin, false, false,
                    onInvite = { invites++ },
                    onRemove = { removed += it.user_id },
                    onRetry = {},
                    modifier = Modifier.weight(1f),
                )
                TeamDetailFooter(enabled = true, onLeave = {})
            }
        }
    }

    @Test fun teamAdmin_canInviteAndRemoveOthers_butNotThemselves() {
        rule.setContent { Team(admin = true) }

        rule.onNodeWithText("Utilizator 12 (tu)").assertExists()
        rule.onNodeWithText("Membru").assertExists()
        rule.onNodeWithContentDescription("Elimină Utilizator 12 (tu)").assertDoesNotExist()
        shoot("team-admin")
        rule.onNodeWithText("Invită pe cineva").performClick()
        rule.onNodeWithContentDescription("Elimină Utilizator 18").performClick()

        assertEquals(1, invites)
        assertEquals(listOf(18L), removed)
    }

    @Test fun teamMember_canOnlySeeAndLeave() {
        rule.setContent { Team(admin = false, dark = true) }

        rule.onNodeWithText("Invită pe cineva").assertDoesNotExist()
        rule.onNodeWithContentDescription("Elimină Utilizator 18").assertDoesNotExist()
        rule.onNodeWithText("Părăsește echipa").assertIsEnabled()
        shoot("team-member-dark")
    }

    @Test fun back_closesAnOpenMenu_beforeWhatIsBehindIt() {
        var behind = 0
        lateinit var drawer: DrawerState
        rule.setContent {
            drawer = rememberDrawerState(DrawerValue.Open)
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = { ModalDrawerSheet { Text("Meniu") } },
            ) {
                BackHandler { behind++ }
                CloseDrawerOnBack(drawer)
            }
        }

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue(drawer.isClosed)
        assertEquals(0, behind)

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(1, behind)
    }
}
