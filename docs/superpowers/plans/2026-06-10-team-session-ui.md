# Team-Session Android UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the backend's team system in the Android app — create/join teams from the phone and switch the whole app between a Personal workspace and team workspaces, each with its own active shared session.

**Architecture:** A `WorkspaceManager` singleton holds the current workspace (Personal or Team) persisted in SharedPreferences. Room's `SessionEntity` gains a nullable `teamId`; all active-session queries become workspace-scoped. A `WorkspaceSessionResolver` fetches-or-creates the server session for the current workspace when online and mirrors it into Room using the **server id as the local id** (same pattern as products/distributors). New Teams screens handle create/join/invite/members.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room (destructive migration — version bump only), Retrofit, plain SharedPreferences for workspace persistence (matches `SyncService` pattern; spec said DataStore but the codebase pattern is SharedPreferences — no new dependency). Backend: one small FastAPI change (self-removal = leave team) with pytest.

**Spec:** `docs/superpowers/specs/2026-06-10-team-session-ui-design.md`

**Spec deviations (agreed during planning):**
1. "Leave team" needs a small backend change — `DELETE /api/teams/{team_id}/members/{user_id}` currently requires team-admin, so a regular member cannot remove themselves. Task 1 relaxes it for self-removal only (non-breaking).
2. Workspace persistence uses SharedPreferences, not DataStore (existing codebase pattern).

**Conventions for all tasks:**
- Android commands run from `android-native/`: `.\gradlew assembleDebug`, `.\gradlew testDebug`
- Backend commands run from `backend-fastapi/` with venv python: `.\venv\Scripts\python.exe -m pytest -q`
- Commit after every task. No Claude co-author trailer on commits.

---

### Task 1: Backend — members can leave a team (self-removal)

The backend's `remove_member` requires team-admin for everyone. Allow any member to remove **themselves** (leave), keeping the last-admin guard. Non-breaking: admin removal of others is unchanged.

**Files:**
- Modify: `backend-fastapi/app/routers/teams.py:250-287` (`remove_member`)
- Test: `backend-fastapi/tests/test_teams.py` (append a new test class)

- [ ] **Step 1: Write the failing tests**

Append to `backend-fastapi/tests/test_teams.py` (fixtures `client`, `auth_headers` = team admin, `team_member_headers` = non-admin member, `non_member_headers`, `team` already exist in `conftest.py`):

```python
class TestLeaveTeam:
    """Self-removal (leave team) — any member may remove themselves."""

    def test_member_can_leave_team(self, client, team_member_headers, team):
        """A non-admin member can remove themselves from the team."""
        me = client.get("/api/auth/me", headers=team_member_headers).json()

        response = client.delete(
            f"/api/teams/{team.id}/members/{me['id']}",
            headers=team_member_headers,
        )

        assert response.status_code == status.HTTP_200_OK
        # They no longer see the team.
        listing = client.get("/api/teams", headers=team_member_headers)
        assert team.id not in [t["id"] for t in listing.json()]

    def test_last_admin_cannot_leave(self, client, auth_headers, team):
        """The last admin cannot remove themselves (team would be orphaned)."""
        me = client.get("/api/auth/me", headers=auth_headers).json()

        response = client.delete(
            f"/api/teams/{team.id}/members/{me['id']}",
            headers=auth_headers,
        )

        assert response.status_code == status.HTTP_400_BAD_REQUEST

    def test_member_still_cannot_remove_others(
        self, client, team_member_headers, auth_headers, team
    ):
        """A non-admin member removing someone ELSE is still forbidden (403)."""
        admin = client.get("/api/auth/me", headers=auth_headers).json()

        response = client.delete(
            f"/api/teams/{team.id}/members/{admin['id']}",
            headers=team_member_headers,
        )

        assert response.status_code == status.HTTP_403_FORBIDDEN
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest tests/test_teams.py::TestLeaveTeam -q`
Expected: `test_member_can_leave_team` FAILS with 403 != 200. The other two may already pass — that's fine; they pin current behavior.

- [ ] **Step 3: Implement self-removal in `remove_member`**

In `backend-fastapi/app/routers/teams.py`, `remove_member` currently starts with:

```python
    require_team_admin(team_id, current_user, db)
```

Replace that single line with:

```python
    # Self-removal ("leave team") only requires membership; removing anyone
    # else still requires team-admin. The last-admin guard below applies to
    # both paths so a team is never left without an admin.
    if user_id == current_user.id:
        require_team_member(team_id, current_user, db)
    else:
        require_team_admin(team_id, current_user, db)
```

(`require_team_member` is already imported at the top of the file.)

- [ ] **Step 4: Run the full backend suite**

Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: all tests pass, including the 3 new ones.

- [ ] **Step 5: Commit**

```powershell
git add backend-fastapi/app/routers/teams.py backend-fastapi/tests/test_teams.py
git commit -m "feat(api): allow team members to remove themselves (leave team)"
```

---

### Task 2: Android — team DTOs and API endpoints

Pure network-layer additions; nothing calls them yet. Field names must match `app/schemas_teams.py` exactly (snake_case, Gson maps by field name).

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/network/DTOs.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/network/ListManagerApi.kt`

- [ ] **Step 1: Add team DTOs**

Append to `DTOs.kt`:

```kotlin
// ===== Teams =====
data class TeamDTO( val id: Long, val name: String, val created_at: String )
data class TeamMemberDTO( val id: Long, val team_id: Long, val user_id: Long, val role: String )
data class TeamCreateRequest( val name: String )
data class InviteCreateRequest( val role: String = "member" )
data class InviteDTO( val code: String, val expires_at: String, val role: String )
```

- [ ] **Step 2: Add team_id to session DTOs**

In `DTOs.kt`, replace the two existing lines:

```kotlin
data class GlobalSessionDTO( val id: Long, val name: String, val is_active: Boolean, val created_at: String, val completed_at: String?, val version: Int )
```
```kotlin
data class CreateSessionRequest( val name: String )
```

with:

```kotlin
data class GlobalSessionDTO( val id: Long, val name: String, val is_active: Boolean, val created_at: String, val completed_at: String?, val version: Int, val owner_user_id: Long?, val team_id: Long? )
```
```kotlin
data class CreateSessionRequest( val name: String, val team_id: Long? = null )
```

(Backend `GlobalSessionDTO` already returns `owner_user_id` and `team_id`; Gson treats absent fields as null so this is backward-safe.)

- [ ] **Step 3: Add team endpoints and team_id query param**

In `ListManagerApi.kt`, replace:

```kotlin
    @GET("/api/session/active")
    suspend fun getActiveSession(): Response<GlobalSessionDTO>
```

with:

```kotlin
    @GET("/api/session/active")
    suspend fun getActiveSession(@Query("team_id") teamId: Long? = null): Response<GlobalSessionDTO>
```

and append before the closing brace of the interface:

```kotlin
    // ===== Teams =====
    @POST("/api/teams")
    suspend fun createTeam(@Body request: TeamCreateRequest): Response<TeamDTO>

    @GET("/api/teams")
    suspend fun getMyTeams(): Response<List<TeamDTO>>

    @POST("/api/teams/{team_id}/invites")
    suspend fun createInvite(
        @Path("team_id") teamId: Long,
        @Body request: InviteCreateRequest = InviteCreateRequest()
    ): Response<InviteDTO>

    @POST("/api/teams/invites/{code}/accept")
    suspend fun acceptInvite(@Path("code") code: String): Response<TeamMemberDTO>

    @GET("/api/teams/{team_id}/members")
    suspend fun getTeamMembers(@Path("team_id") teamId: Long): Response<List<TeamMemberDTO>>

    @DELETE("/api/teams/{team_id}/members/{user_id}")
    suspend fun removeTeamMember(
        @Path("team_id") teamId: Long,
        @Path("user_id") userId: Long
    ): Response<Unit>
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android-native; .\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```powershell
git add android-native/app/src/main/java/com/darius/listmanager/network/DTOs.kt android-native/app/src/main/java/com/darius/listmanager/network/ListManagerApi.kt
git commit -m "feat(android): team DTOs and API endpoints; team_id on session endpoints"
```

---

### Task 3: Workspace model + WorkspaceManager (with unit tests)

The workspace concept, persisted and observable. Persistence goes behind a 2-method interface so the logic is JVM-unit-testable without Android.

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/workspace/Workspace.kt`
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/workspace/WorkspaceManager.kt`
- Test: `android-native/app/src/test/java/com/darius/listmanager/workspace/WorkspaceManagerTest.kt`

- [ ] **Step 1: Write the failing test**

Create `android-native/app/src/test/java/com/darius/listmanager/workspace/WorkspaceManagerTest.kt`:

```kotlin
package com.darius.listmanager.workspace

import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.data.workspace.WorkspaceStore
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeStore : WorkspaceStore {
    var saved: String? = null
    override fun read(): String? = saved
    override fun write(value: String?) { saved = value }
}

class WorkspaceManagerTest {

    @Test
    fun `defaults to Personal when nothing persisted`() {
        val manager = WorkspaceManager(FakeStore())
        assertEquals(Workspace.Personal, manager.currentWorkspace.value)
    }

    @Test
    fun `switchTo team persists and updates flow`() {
        val store = FakeStore()
        val manager = WorkspaceManager(store)

        manager.switchTo(Workspace.Team(id = 7, name = "Depot"))

        assertEquals(Workspace.Team(7, "Depot"), manager.currentWorkspace.value)
        assertEquals("7:Depot", store.saved)
    }

    @Test
    fun `restores persisted team workspace on construction`() {
        val store = FakeStore().apply { saved = "7:Depot" }
        val manager = WorkspaceManager(store)
        assertEquals(Workspace.Team(7, "Depot"), manager.currentWorkspace.value)
    }

    @Test
    fun `restores Personal when persisted value is null`() {
        val store = FakeStore().apply { saved = null }
        assertEquals(Workspace.Personal, WorkspaceManager(store).currentWorkspace.value)
    }

    @Test
    fun `team name containing colon round-trips`() {
        val store = FakeStore()
        val manager = WorkspaceManager(store)
        manager.switchTo(Workspace.Team(3, "A:B Team"))
        assertEquals(Workspace.Team(3, "A:B Team"), WorkspaceManager(store).currentWorkspace.value)
    }

    @Test
    fun `corrupt persisted value falls back to Personal`() {
        val store = FakeStore().apply { saved = "not-a-number:X" }
        assertEquals(Workspace.Personal, WorkspaceManager(store).currentWorkspace.value)
    }

    @Test
    fun `fallbackToPersonal switches and persists`() {
        val store = FakeStore()
        val manager = WorkspaceManager(store)
        manager.switchTo(Workspace.Team(7, "Depot"))

        manager.fallbackToPersonal()

        assertEquals(Workspace.Personal, manager.currentWorkspace.value)
        assertEquals(null, store.saved)
    }

    @Test
    fun `teamIdOrNull returns id for team and null for personal`() {
        assertEquals(null, Workspace.Personal.teamIdOrNull)
        assertEquals(7L, Workspace.Team(7, "Depot").teamIdOrNull)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-native; .\gradlew testDebug --tests "com.darius.listmanager.workspace.WorkspaceManagerTest"`
Expected: FAIL — unresolved references (`Workspace`, `WorkspaceManager`, `WorkspaceStore`).

- [ ] **Step 3: Implement Workspace + WorkspaceManager**

Create `android-native/app/src/main/java/com/darius/listmanager/data/workspace/Workspace.kt`:

```kotlin
package com.darius.listmanager.data.workspace

/**
 * The context the whole app operates in. [Personal] sessions belong to the
 * logged-in user; [Team] sessions are shared with all members of that team.
 */
sealed class Workspace {
    object Personal : Workspace()
    data class Team(val id: Long, val name: String) : Workspace()

    /** `team_id` to send to the backend; null means personal. */
    val teamIdOrNull: Long?
        get() = (this as? Team)?.id

    val displayName: String
        get() = when (this) {
            is Personal -> "Personal"
            is Team -> name
        }
}
```

Create `android-native/app/src/main/java/com/darius/listmanager/data/workspace/WorkspaceManager.kt`:

```kotlin
package com.darius.listmanager.data.workspace

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minimal persistence seam so WorkspaceManager is unit-testable on the JVM. */
interface WorkspaceStore {
    fun read(): String?
    fun write(value: String?)
}

private class PrefsWorkspaceStore(context: Context) : WorkspaceStore {
    private val prefs = context.applicationContext
        .getSharedPreferences("workspace_prefs", Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)
    override fun write(value: String?) {
        prefs.edit().apply {
            if (value == null) remove(KEY) else putString(KEY, value)
        }.apply()
    }

    private companion object { const val KEY = "current_workspace" }
}

/**
 * Process-wide observable workspace state (same singleton pattern as
 * [com.darius.listmanager.data.repository.AuthState]). Persisted format:
 * null = Personal, "<teamId>:<teamName>" = team (name may contain ':').
 */
class WorkspaceManager(private val store: WorkspaceStore) {

    private val _currentWorkspace = MutableStateFlow(restore())
    val currentWorkspace: StateFlow<Workspace> = _currentWorkspace.asStateFlow()

    fun switchTo(workspace: Workspace) {
        _currentWorkspace.value = workspace
        store.write(
            when (workspace) {
                is Workspace.Personal -> null
                is Workspace.Team -> "${workspace.id}:${workspace.name}"
            }
        )
    }

    /** Used when the server says we lost access to the current team (404/403). */
    fun fallbackToPersonal() = switchTo(Workspace.Personal)

    private fun restore(): Workspace {
        val raw = store.read() ?: return Workspace.Personal
        val sep = raw.indexOf(':')
        if (sep <= 0) return Workspace.Personal
        val id = raw.substring(0, sep).toLongOrNull() ?: return Workspace.Personal
        return Workspace.Team(id, raw.substring(sep + 1))
    }

    companion object {
        @Volatile
        private var INSTANCE: WorkspaceManager? = null

        fun getInstance(context: Context): WorkspaceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WorkspaceManager(PrefsWorkspaceStore(context)).also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android-native; .\gradlew testDebug --tests "com.darius.listmanager.workspace.WorkspaceManagerTest"`
Expected: all 8 tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add android-native/app/src/main/java/com/darius/listmanager/data/workspace android-native/app/src/test/java/com/darius/listmanager/workspace
git commit -m "feat(android): Workspace model + persisted WorkspaceManager"
```

---

### Task 4: Room — workspace-scoped sessions

`SessionEntity` gains `teamId`; all active-session queries become "active session **for this workspace**". DB version 2 → 3; the app already uses `fallbackToDestructiveMigration()` so no migration code (local cache is rebuilt from the server; acceptable per existing pattern — same thing happened for v1→v2).

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/local/entity/SessionEntity.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/local/dao/SessionDao.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt:25` (version)
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/repository/SessionRepository.kt`

- [ ] **Step 1: Add teamId to SessionEntity**

Replace the data class in `SessionEntity.kt` with:

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "Session",
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true,
    /** Owning team, or null for the user's personal workspace. */
    val teamId: Long? = null,
)
```

- [ ] **Step 2: Workspace-scope the SessionDao queries**

Replace the body of `SessionDao` (keep `getAllFlow`, `getById`, `insert`, `update`, `delete` as-is) — the changed/new members:

```kotlin
    @Query("SELECT * FROM sessions WHERE isActive = 1 AND teamId IS :teamId LIMIT 1")
    suspend fun getActiveSession(teamId: Long?): SessionEntity?

    @Query("SELECT * FROM sessions WHERE isActive = 1 AND teamId IS :teamId LIMIT 1")
    fun getActiveSessionFlow(teamId: Long?): Flow<SessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity): Long

    @Query("UPDATE sessions SET isActive = 0 WHERE teamId IS :teamId")
    suspend fun deactivateAll(teamId: Long?)

    @Transaction
    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        val active = getActiveSession(teamId)
        return if (active != null) {
            active
        } else {
            val newId = insert(SessionEntity(name = "Current Session", isActive = true, teamId = teamId))
            getById(newId)!!
        }
    }

    /**
     * Mirror a server session into the local cache as the single active
     * session of its workspace. Uses the SERVER id as the local id (same
     * convention as products/distributors).
     */
    @Transaction
    suspend fun activateServerSession(serverId: Long, name: String, teamId: Long?) {
        deactivateAll(teamId)
        upsert(SessionEntity(id = serverId, name = name, isActive = true, teamId = teamId))
    }
```

Note: `IS :teamId` (not `=`) so `null` matches the personal workspace — SQLite `=` never matches NULL. `@Insert(onConflict = REPLACE)` import is `androidx.room.OnConflictStrategy` (already covered by the existing `androidx.room.*` import).

- [ ] **Step 3: Bump DB version**

In `AppDatabase.kt` change `version = 2` to `version = 3`.

- [ ] **Step 4: Update SessionRepository to take a workspace**

In `SessionRepository.kt`, replace the four session-resolution methods:

```kotlin
    fun getActiveSessionFlow(teamId: Long?): Flow<SessionEntity?> = sessionDao.getActiveSessionFlow(teamId)

    suspend fun getActiveSession(teamId: Long?): SessionEntity? = sessionDao.getActiveSession(teamId)

    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        return sessionDao.getOrCreateActiveSession(teamId)
    }

    suspend fun activateServerSession(serverId: Long, name: String, teamId: Long?) {
        sessionDao.activateServerSession(serverId, name, teamId)
    }
```

(Item-level methods are unchanged — they key off `sessionId`.)

- [ ] **Step 5: Fix the one existing caller and compile**

`SessionViewModel.loadSession()` (line 48) currently calls `sessionRepository.getOrCreateActiveSession()`. Change it to pass the current workspace (full wiring happens in Task 6; for now keep behavior identical):

```kotlin
                val workspaceManager = com.darius.listmanager.data.workspace.WorkspaceManager.getInstance(getApplication())
                val session = sessionRepository.getOrCreateActiveSession(
                    workspaceManager.currentWorkspace.value.teamIdOrNull
                )
```

Run: `cd android-native; .\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. (If other callers of the renamed methods surface as compile errors, pass `teamId = workspaceManager.currentWorkspace.value.teamIdOrNull` the same way.)

- [ ] **Step 6: Run unit tests and commit**

Run: `cd android-native; .\gradlew testDebug`
Expected: PASS.

```powershell
git add android-native/app/src/main/java/com/darius/listmanager
git commit -m "feat(android): workspace-scoped sessions in Room (teamId on SessionEntity, db v3)"
```

---

### Task 5: TeamRepository + WorkspaceSessionResolver

Online-only team operations, and the piece that makes team sessions actually shared: resolving the server's active session for a workspace and mirroring it (with items) into Room.

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/repository/TeamRepository.kt`
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/workspace/WorkspaceSessionResolver.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/local/dao/SessionDao.kt` (Task 4 review findings — Step 0)

- [ ] **Step 0: Fix Task 4 review findings in SessionDao**

(a) `upsert` is `@Insert(onConflict = REPLACE)`. SQLite REPLACE = DELETE+INSERT,
and `session_items` has `ForeignKey(onDelete = CASCADE)` — so re-activating an
existing session id would silently wipe its items. Replace with Room's
`@Upsert` (UPDATE on conflict, no delete, no cascade; Room 2.8.1 supports it):

```kotlin
    @androidx.room.Upsert
    suspend fun upsert(session: SessionEntity): Long
```

(b) Local offline-created sessions use autogenerated positive ids that can
collide with server session ids (both start at 1). Make
`getOrCreateActiveSession` allocate NEGATIVE ids for local fallback sessions so
they can never collide with a mirrored server session:

```kotlin
    @Query("SELECT MIN(id) FROM sessions")
    suspend fun getMinSessionId(): Long?

    @Transaction
    suspend fun getOrCreateActiveSession(teamId: Long?): SessionEntity {
        val active = getActiveSession(teamId)
        if (active != null) return active
        // Local fallback sessions get negative ids; server-mirrored sessions
        // (activateServerSession) own the positive id space.
        val newId = minOf(getMinSessionId() ?: 0L, 0L) - 1L
        insert(SessionEntity(id = newId, name = "Current Session", isActive = true, teamId = teamId))
        return getById(newId)!!
    }
```

Run `cd android-native; .\gradlew testDebug assembleDebug` — green before continuing.

- [ ] **Step 1: Implement TeamRepository**

Create `TeamRepository.kt`:

```kotlin
package com.darius.listmanager.data.repository

import com.darius.listmanager.network.*
import retrofit2.Response
import java.io.IOException

/**
 * Team management is ONLINE-ONLY by design (rare admin actions — no offline
 * queue). Every call maps to [TeamResult]; connectivity failures surface as
 * [TeamResult.Offline] so the UI can show "requires connection".
 */
sealed class TeamResult<out T> {
    data class Success<T>(val data: T) : TeamResult<T>()
    data class Failure(val message: String) : TeamResult<Nothing>()
    object Offline : TeamResult<Nothing>()
}

class TeamRepository(private val api: ListManagerApi = RetrofitClient.api) {

    suspend fun createTeam(name: String): TeamResult<TeamDTO> =
        call { api.createTeam(TeamCreateRequest(name)) }

    suspend fun getMyTeams(): TeamResult<List<TeamDTO>> =
        call { api.getMyTeams() }

    suspend fun createInvite(teamId: Long): TeamResult<InviteDTO> =
        call { api.createInvite(teamId) }

    suspend fun acceptInvite(code: String): TeamResult<TeamMemberDTO> =
        call(badRequestMessage = "Invite is invalid, expired, or already used") {
            api.acceptInvite(code.trim())
        }

    suspend fun getMembers(teamId: Long): TeamResult<List<TeamMemberDTO>> =
        call { api.getTeamMembers(teamId) }

    /** Works for both "remove member" (admin) and "leave team" (self). */
    suspend fun removeMember(teamId: Long, userId: Long): TeamResult<Unit> =
        call { api.removeTeamMember(teamId, userId) }

    private suspend fun <T> call(
        badRequestMessage: String? = null,
        block: suspend () -> Response<T>,
    ): TeamResult<T> {
        return try {
            val response = block()
            when {
                response.isSuccessful ->
                    @Suppress("UNCHECKED_CAST")
                    TeamResult.Success(response.body() ?: Unit as T)
                response.code() == 400 || response.code() == 404 ->
                    TeamResult.Failure(badRequestMessage ?: "Request rejected (${response.code()})")
                response.code() == 403 ->
                    TeamResult.Failure("You don't have permission for this action")
                response.code() == 409 ->
                    TeamResult.Failure("You are already a member of this team")
                else ->
                    TeamResult.Failure("Server error (${response.code()})")
            }
        } catch (e: IOException) {
            TeamResult.Offline
        }
    }
}
```

- [ ] **Step 2: Implement WorkspaceSessionResolver**

Create `WorkspaceSessionResolver.kt`:

```kotlin
package com.darius.listmanager.data.workspace

import android.util.Log
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.SessionItemEntity
import com.darius.listmanager.network.CreateSessionRequest
import com.darius.listmanager.network.ListManagerApi
import com.darius.listmanager.network.RetrofitClient
import java.io.IOException

/**
 * Resolves the SERVER active session for a workspace and mirrors it into Room
 * so every device in the team operates on the same session id.
 *
 * Online:  GET /api/session/active?team_id → (404 → POST /api/session/create)
 *          → activate locally under the server id → pull items.
 * Offline: no-op; the UI keeps using the cached local session
 *          (getOrCreateActiveSession) and ops queue as usual.
 */
class WorkspaceSessionResolver(
    private val database: AppDatabase,
    private val api: ListManagerApi = RetrofitClient.api,
) {
    sealed class ResolveResult {
        data class Resolved(val sessionId: Long) : ResolveResult()
        object Offline : ResolveResult()
        /** Server says we can't see this workspace anymore (removed from team). */
        object AccessLost : ResolveResult()
    }

    suspend fun resolve(workspace: Workspace): ResolveResult {
        val teamId = workspace.teamIdOrNull
        return try {
            var response = api.getActiveSession(teamId)

            if (response.code() == 404) {
                // No active session in this workspace yet — create one.
                response = run {
                    val created = api.createSession(
                        CreateSessionRequest(name = "Current Session", team_id = teamId)
                    )
                    created
                }
            }

            when {
                response.isSuccessful -> {
                    val dto = response.body()!!
                    database.sessionDao().activateServerSession(
                        serverId = dto.id, name = dto.name, teamId = teamId
                    )
                    pullItems(dto.id)
                    ResolveResult.Resolved(dto.id)
                }
                response.code() == 403 || response.code() == 404 -> ResolveResult.AccessLost
                else -> {
                    Log.e(TAG, "resolve failed: HTTP ${response.code()}")
                    ResolveResult.Offline // treat as transient; keep cached state
                }
            }
        } catch (e: IOException) {
            ResolveResult.Offline
        }
    }

    /** Replace local items with the server's (server is source of truth when online). */
    private suspend fun pullItems(sessionId: Long) {
        try {
            val response = api.getSessionItems(sessionId)
            if (response.isSuccessful) {
                val dao = database.sessionItemDao()
                dao.deleteAllInSession(sessionId)
                response.body().orEmpty().forEach { item ->
                    dao.insert(
                        SessionItemEntity(
                            id = item.id,
                            sessionId = sessionId,
                            productId = item.product_id,
                            quantity = item.quantity,
                        )
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "item pull failed; keeping cached items")
        }
    }

    private companion object { const val TAG = "WorkspaceSessionResolver" }
}
```

- [ ] **Step 3: Compile**

Run: `cd android-native; .\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```powershell
git add android-native/app/src/main/java/com/darius/listmanager/data/repository/TeamRepository.kt android-native/app/src/main/java/com/darius/listmanager/data/workspace/WorkspaceSessionResolver.kt
git commit -m "feat(android): TeamRepository and workspace session resolver"
```

---

### Task 6: SessionViewModel reacts to workspace switches + header label

The session screen must reload when the workspace changes and show which workspace it's in.

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/SessionViewModel.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/screens/SessionScreen.kt` (TopAppBar title area)

- [ ] **Step 1: Make SessionViewModel workspace-aware**

In `SessionViewModel.kt`:

1. Add imports:
```kotlin
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.data.workspace.WorkspaceSessionResolver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
```

2. Add to `SessionUiState`:
```kotlin
    val workspaceName: String = "Personal",
```

3. Replace the fields + `init` + `loadSession()` with:

```kotlin
    private val workspaceManager = WorkspaceManager.getInstance(application)
    private val resolver = WorkspaceSessionResolver(database)

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var currentSessionId: Long? = null

    init {
        observeWorkspace()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeWorkspace() {
        viewModelScope.launch {
            workspaceManager.currentWorkspace.collectLatest { workspace ->
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    workspaceName = workspace.displayName,
                )
                try {
                    // Best effort: align with the server session for this
                    // workspace. Offline falls back to the local cache.
                    when (resolver.resolve(workspace)) {
                        is WorkspaceSessionResolver.ResolveResult.AccessLost -> {
                            workspaceManager.fallbackToPersonal()
                            return@collectLatest // flow re-emits with Personal
                        }
                        else -> { /* Resolved or Offline — continue below */ }
                    }
                    val session = sessionRepository.getOrCreateActiveSession(workspace.teamIdOrNull)
                    currentSessionId = session.id
                    sessionRepository.getSessionItemsFlow(session.id).collect { items ->
                        _uiState.value = _uiState.value.copy(items = items, isLoading = false)
                    }
                } catch (e: Exception) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                }
            }
        }
    }
```

(`collectLatest` cancels the inner items collection when the workspace changes — exactly the reload semantics we want. All other methods — `updateQuantity`, `clearSession`, `generatePdfs` — already key off `currentSessionId` and need no changes.)

- [ ] **Step 2: Show the workspace label in SessionScreen**

In `SessionScreen.kt`, locate the `TopAppBar` `title = { ... }` and change the title composable to a two-line title showing the workspace (adapt to the actual title composable found in the file — keep existing style):

```kotlin
                title = {
                    Column {
                        Text("Current Session")
                        Text(
                            uiState.workspaceName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
```

(`uiState` is already collected in this screen; add `import androidx.compose.foundation.layout.Column` if missing.)

- [ ] **Step 3: Compile and run unit tests**

Run: `cd android-native; .\gradlew assembleDebug testDebug`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 4: Commit**

```powershell
git add android-native/app/src/main/java/com/darius/listmanager
git commit -m "feat(android): session screen reloads per workspace with workspace label"
```

---

### Task 6.5: Device→server write-through for session items (scope addition, user-approved)

Discovered during Task 6 review: nothing uploads item mutations (the pending-op
replay machinery exists but is never fed; the WS only sends pings). Without
this, team sessions are one-way. Approved addition: item add / set-quantity /
delete / clear become write-through — local Room write first (instant UI),
then the server API when the session is server-mirrored (positive id); on
connectivity failure, enqueue the existing pending-op types for SyncService
replay. Sessions with NEGATIVE ids (local fallback) skip the network entirely
(stranding is recorded follow-up #3).

**Files:**
- Modify: `data/local/entity/SessionItemEntity.kt` — add `val version: Int = 1`
  (server optimistic-lock version; DB version 3 → 4, destructive fallback as before)
- Modify: `data/local/AppDatabase.kt` — version 4
- Modify: `data/repository/SessionRepository.kt` — constructor gains
  `api: ListManagerApi` and `pendingOps: PendingOperationRepository`; the four
  item methods become write-through (capture pre-state BEFORE the local write;
  rethrow CancellationException; IOException → enqueue; other non-2xx → log,
  rely on WS/pull self-heal; on successful add/update upsert the local row from
  the response DTO — id/quantity/version — re-keying local rows to server ids
  via the (sessionId, productId) unique index)
- Modify: `data/workspace/WorkspaceSessionResolver.kt` — pullItems also stores
  `version = item.version`
- Modify: construction sites of SessionRepository (SessionViewModel,
  HomeViewModel, others found by grep) to pass the new dependencies
- Test: `app/src/test/java/com/darius/listmanager/repository/SessionRepositoryWriteThroughTest.kt`
  — JVM tests with hand-rolled fakes (ListManagerApi fake throwing
  NotImplementedError except item methods; fake DAOs; retrofit2.Response
  .success/.error are JVM-constructible). Cover at minimum: online add upserts
  server row; offline add enqueues ADD_SESSION_ITEM; negative session id skips
  network and queue; offline delete enqueues; clear calls API for positive ids.

Semantics per method (sessionId > 0 means server-mirrored):
- `addOrIncrementItem`: local addOrIncrement → POST add (server increments and
  returns the absolute DTO) → upsert local from DTO. Offline → enqueue
  `AddSessionItem(sessionId, productId, quantity)` (delta semantics — replay-safe).
- `setItemQuantity`: capture `existing = getItem(sessionId, productId)` first;
  local setQuantity. Then: qty<=0 && existing!=null → DELETE by `existing.id`
  (404 = success); qty>0 && existing!=null → PUT update with
  `UpdateItemRequest(qty, existing.version)`, 409 → log + self-heal; offline →
  enqueue `UpdateSessionItem(existing.id, qty, existing.version)`;
  existing==null && qty>0 → treat as add.
- `deleteItem(itemId)`: local delete; itemId>0 → DELETE (404 ok); offline →
  enqueue `DeleteSessionItem(itemId)`.
- `clearSession(sessionId)`: local clear; sessionId>0 → DELETE items endpoint;
  offline → enqueue `ClearSession(sessionId)`.

Commit: `feat(android): write-through sync for session items (+offline queue)`

### Task 7: TeamsViewModel + TeamsScreen (list / create / join)

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/TeamsViewModel.kt`
- Create: `android-native/app/src/main/java/com/darius/listmanager/ui/screens/TeamsScreen.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/components/DrawerContent.kt`

- [ ] **Step 1: Implement TeamsViewModel**

Create `TeamsViewModel.kt`:

```kotlin
package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.TeamRepository
import com.darius.listmanager.data.repository.TeamResult
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.network.TeamDTO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeamsUiState(
    val teams: List<TeamDTO> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Set after a successful join/create so the UI can offer switching. */
    val justJoinedTeam: TeamDTO? = null,
)

class TeamsViewModel(application: Application) : AndroidViewModel(application) {

    private val teamRepository = TeamRepository()
    private val workspaceManager = WorkspaceManager.getInstance(application)

    private val _uiState = MutableStateFlow(TeamsUiState())
    val uiState: StateFlow<TeamsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = teamRepository.getMyTeams()) {
                is TeamResult.Success ->
                    _uiState.value = _uiState.value.copy(teams = result.data, isLoading = false)
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = "Team management requires a connection"
                    )
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(isLoading = false, error = result.message)
            }
        }
    }

    fun createTeam(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            when (val result = teamRepository.createTeam(name.trim())) {
                is TeamResult.Success -> {
                    _uiState.value = _uiState.value.copy(justJoinedTeam = result.data)
                    refresh()
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun joinTeam(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            when (val result = teamRepository.acceptInvite(code)) {
                is TeamResult.Success -> {
                    // Membership gives team_id; resolve the full team from the refreshed list.
                    when (val teams = teamRepository.getMyTeams()) {
                        is TeamResult.Success -> {
                            val joined = teams.data.find { it.id == result.data.team_id }
                            _uiState.value = _uiState.value.copy(
                                teams = teams.data, isLoading = false, justJoinedTeam = joined
                            )
                        }
                        else -> refresh()
                    }
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun switchToTeam(team: TeamDTO) {
        workspaceManager.switchTo(Workspace.Team(team.id, team.name))
        consumeJustJoined()
    }

    fun consumeJustJoined() {
        _uiState.value = _uiState.value.copy(justJoinedTeam = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
```

- [ ] **Step 2: Implement TeamsScreen**

Create `TeamsScreen.kt`. Follow the visual conventions of `CatalogScreen.kt` (Scaffold + TopAppBar with back arrow, LazyColumn of cards):

```kotlin
package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.network.TeamDTO
import com.darius.listmanager.ui.viewmodel.TeamsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamsScreen(
    onBack: () -> Unit,
    onOpenTeam: (Long, String) -> Unit,
    viewModel: TeamsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var showJoinDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // After create/join: offer to switch the app into that team's workspace.
    uiState.justJoinedTeam?.let { team ->
        AlertDialog(
            onDismissRequest = { viewModel.consumeJustJoined() },
            title = { Text("Switch to ${team.name}?") },
            text = { Text("Work in this team's shared session now?") },
            confirmButton = {
                TextButton(onClick = { viewModel.switchToTeam(team) }) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.consumeJustJoined() }) { Text("Not now") }
            }
        )
    }

    if (showCreateDialog) {
        TeamNameDialog(
            title = "Create team",
            confirmLabel = "Create",
            onConfirm = { viewModel.createTeam(it); showCreateDialog = false },
            onDismiss = { showCreateDialog = false }
        )
    }

    if (showJoinDialog) {
        TeamNameDialog(
            title = "Join with invite code",
            confirmLabel = "Join",
            placeholder = "Paste invite code",
            onConfirm = { viewModel.joinTeam(it); showJoinDialog = false },
            onDismiss = { showJoinDialog = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Teams") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Create team") }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedButton(
                onClick = { showJoinDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Rounded.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Join with invite code")
            }

            when {
                uiState.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.teams.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No teams yet. Create one or join with a code.")
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp)) {
                    items(uiState.teams, key = { it.id }) { team ->
                        TeamRow(team = team, onClick = { onOpenTeam(team.id, team.name) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamRow(team: TeamDTO, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Groups, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(team.name, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun TeamNameDialog(
    title: String,
    confirmLabel: String,
    placeholder: String = "Team name",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
```

- [ ] **Step 3: Register the route and drawer entry**

In `NavGraph.kt`, add import `com.darius.listmanager.ui.screens.TeamsScreen` and a composable after the `"catalog"` block:

```kotlin
        composable("teams") {
            TeamsScreen(
                onBack = { navController.popBackStack() },
                onOpenTeam = { teamId, teamName ->
                    navController.navigate("team_detail/$teamId?name=$teamName")
                }
            )
        }
```

In `DrawerContent.kt`, add after the Catalog item (line 32):

```kotlin
        DrawerItem(Icons.Rounded.Groups, "Teams") { onNavigate("teams") }
```

(`team_detail` route is added in Task 8 — to keep this task compiling, also add the Task 8 route stub now or do Steps 3 of both tasks together; simplest is to add the `TeamDetailScreen` route only in Task 8 and navigate there then. For THIS task, make `onOpenTeam` a no-op: `onOpenTeam = { _, _ -> }` and wire it for real in Task 8.)

- [ ] **Step 4: Compile, install, eyeball**

Run: `cd android-native; .\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL. Optional: `adb install -r app\build\outputs\apk\debug\app-debug.apk` and check the Teams screen lists/creates/joins (backend must be running with `adb reverse tcp:8000 tcp:8000`).

- [ ] **Step 5: Commit**

```powershell
git add android-native/app/src/main/java/com/darius/listmanager
git commit -m "feat(android): Teams screen - list, create, join via invite code"
```

---

### Task 8: TeamDetailScreen (members, invite, leave/remove)

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/TeamDetailViewModel.kt`
- Create: `android-native/app/src/main/java/com/darius/listmanager/ui/screens/TeamDetailScreen.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt`

- [ ] **Step 1: Implement TeamDetailViewModel**

Create `TeamDetailViewModel.kt`:

```kotlin
package com.darius.listmanager.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.repository.AuthState
import com.darius.listmanager.data.repository.TeamRepository
import com.darius.listmanager.data.repository.TeamResult
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceManager
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.network.TeamMemberDTO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TeamDetailUiState(
    val members: List<TeamMemberDTO> = emptyList(),
    val myUserId: Long? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** Set when an invite code was generated; UI opens the share sheet. */
    val inviteCode: String? = null,
    /** Set true after leaving so the UI can navigate back. */
    val leftTeam: Boolean = false,
) {
    val myRole: String?
        get() = members.find { it.user_id == myUserId }?.role
    val amAdmin: Boolean get() = myRole == "admin"
}

class TeamDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val teamRepository = TeamRepository()
    private val workspaceManager = WorkspaceManager.getInstance(application)

    private val _uiState = MutableStateFlow(TeamDetailUiState())
    val uiState: StateFlow<TeamDetailUiState> = _uiState.asStateFlow()

    private var teamId: Long = -1

    fun load(teamId: Long) {
        this.teamId = teamId
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            // user id needed to distinguish "me" in the member list
            val me = try {
                RetrofitClient.api.getCurrentUser().body()
            } catch (e: Exception) { null }
            when (val result = teamRepository.getMembers(teamId)) {
                is TeamResult.Success -> _uiState.value = _uiState.value.copy(
                    members = result.data, myUserId = me?.id, isLoading = false
                )
                is TeamResult.Offline -> _uiState.value = _uiState.value.copy(
                    isLoading = false, error = "Team management requires a connection"
                )
                is TeamResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoading = false, error = result.message
                )
            }
        }
    }

    fun generateInvite() {
        viewModelScope.launch {
            when (val result = teamRepository.createInvite(teamId)) {
                is TeamResult.Success ->
                    _uiState.value = _uiState.value.copy(inviteCode = result.data.code)
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun consumeInviteCode() {
        _uiState.value = _uiState.value.copy(inviteCode = null)
    }

    fun removeMember(userId: Long) {
        viewModelScope.launch {
            when (val result = teamRepository.removeMember(teamId, userId)) {
                is TeamResult.Success -> {
                    if (userId == _uiState.value.myUserId) {
                        // We left: if the app is in this team's workspace, fall back.
                        val current = workspaceManager.currentWorkspace.value
                        if ((current as? Workspace.Team)?.id == teamId) {
                            workspaceManager.fallbackToPersonal()
                        }
                        _uiState.value = _uiState.value.copy(leftTeam = true)
                    } else {
                        load(teamId)
                    }
                }
                is TeamResult.Offline ->
                    _uiState.value = _uiState.value.copy(error = "Team management requires a connection")
                is TeamResult.Failure ->
                    _uiState.value = _uiState.value.copy(error = result.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
```

- [ ] **Step 2: Implement TeamDetailScreen**

Create `TeamDetailScreen.kt`:

```kotlin
package com.darius.listmanager.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.TeamDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamDetailScreen(
    teamId: Long,
    teamName: String,
    onBack: () -> Unit,
    viewModel: TeamDetailViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(teamId) { viewModel.load(teamId) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.leftTeam) {
        if (uiState.leftTeam) onBack()
    }

    // Open the Android share sheet when an invite code arrives.
    LaunchedEffect(uiState.inviteCode) {
        uiState.inviteCode?.let { code ->
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Join my ListManager team \"$teamName\" with this invite code:\n\n$code"
                )
                type = "text/plain"
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share invite code"))
            viewModel.consumeInviteCode()
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave $teamName?") },
            text = { Text("You will lose access to this team's shared session.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    uiState.myUserId?.let { viewModel.removeMember(it) }
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(teamName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            if (uiState.amAdmin) {
                Button(onClick = { viewModel.generateInvite() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Invite — generate & share code")
                }
                Spacer(Modifier.height(8.dp))
            }

            OutlinedButton(
                onClick = { confirmLeave = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Leave team")
            }

            Spacer(Modifier.height(16.dp))
            Text("Members", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            if (uiState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    items(uiState.members, key = { it.id }) { member ->
                        ListItem(
                            leadingContent = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            headlineContent = {
                                Text(
                                    if (member.user_id == uiState.myUserId) "User ${member.user_id} (you)"
                                    else "User ${member.user_id}"
                                )
                            },
                            supportingContent = { Text(member.role) },
                            trailingContent = {
                                if (uiState.amAdmin && member.user_id != uiState.myUserId) {
                                    IconButton(onClick = { viewModel.removeMember(member.user_id) }) {
                                        Icon(
                                            Icons.Rounded.PersonRemove,
                                            contentDescription = "Remove member",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
```

Note: `TeamMemberDTO` carries only `user_id`/`role` (no username) — showing "User <id>" is accepted for v1; if usernames are wanted later, the backend DTO needs a `username` field (out of scope).

- [ ] **Step 3: Wire the route**

In `NavGraph.kt` add imports `com.darius.listmanager.ui.screens.TeamDetailScreen` and add after the `"teams"` composable:

```kotlin
        composable(
            route = "team_detail/{teamId}?name={teamName}",
            arguments = listOf(
                navArgument("teamId") { type = NavType.LongType },
                navArgument("teamName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            TeamDetailScreen(
                teamId = backStackEntry.arguments?.getLong("teamId") ?: 0L,
                teamName = backStackEntry.arguments?.getString("teamName") ?: "",
                onBack = { navController.popBackStack() }
            )
        }
```

And in the `"teams"` composable replace the Task 7 no-op with the real navigation:

```kotlin
                onOpenTeam = { teamId, teamName ->
                    navController.navigate("team_detail/$teamId?name=$teamName")
                }
```

- [ ] **Step 4: Compile**

Run: `cd android-native; .\gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```powershell
git add android-native/app/src/main/java/com/darius/listmanager
git commit -m "feat(android): team detail screen - members, invite share, leave/remove"
```

---

### Task 9: Drawer workspace switcher + final verification

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/components/DrawerContent.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ListManagerApp.kt:80-92` (pass workspace state into drawer)
- Modify: `docs/PROGRESS.md`

- [ ] **Step 1: Add the workspace switcher to DrawerContent**

Replace `DrawerContent.kt`'s composable with a version that takes workspace state (keep `DrawerItem` helper as-is):

```kotlin
@Composable
fun DrawerContent(
    onNavigate: (String) -> Unit,
    workspaceName: String = "Personal",
    teams: List<TeamDTO> = emptyList(),
    onSwitchWorkspace: (Workspace) -> Unit = {},
) {
    var switcherExpanded by remember { mutableStateOf(false) }

    ModalDrawerSheet {
        Spacer(Modifier.height(24.dp))

        Text(
            "List Manager",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // ===== Workspace switcher =====
        Box(Modifier.padding(horizontal = 12.dp)) {
            NavigationDrawerItem(
                icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                label = {
                    Column {
                        Text("Workspace", style = MaterialTheme.typography.labelSmall)
                        Text(workspaceName, fontWeight = FontWeight.SemiBold)
                    }
                },
                selected = false,
                onClick = { switcherExpanded = true }
            )
            DropdownMenu(
                expanded = switcherExpanded,
                onDismissRequest = { switcherExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Personal") },
                    onClick = {
                        switcherExpanded = false
                        onSwitchWorkspace(Workspace.Personal)
                    }
                )
                teams.forEach { team ->
                    DropdownMenuItem(
                        text = { Text(team.name) },
                        onClick = {
                            switcherExpanded = false
                            onSwitchWorkspace(Workspace.Team(team.id, team.name))
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Manage teams…") },
                    onClick = {
                        switcherExpanded = false
                        onNavigate("teams")
                    }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        DrawerItem(Icons.Rounded.Home, "Home") { onNavigate("home") }
        DrawerItem(Icons.Rounded.ShoppingCart, "Current Session") { onNavigate("session") }
        DrawerItem(Icons.Rounded.Warning, "Unknown Products") { onNavigate("unknown") }
        DrawerItem(Icons.Rounded.Inventory, "Catalog") { onNavigate("catalog") }
        DrawerItem(Icons.Rounded.Groups, "Teams") { onNavigate("teams") }
        DrawerItem(Icons.Rounded.Description, "Generated PDFs") { onNavigate("pdfs") }

        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        DrawerItem(Icons.Rounded.Settings, "Settings") { onNavigate("settings") }
        DrawerItem(Icons.Rounded.Info, "About") { onNavigate("about") }
    }
}
```

New imports needed: `com.darius.listmanager.network.TeamDTO`, `com.darius.listmanager.data.workspace.Workspace`.

- [ ] **Step 2: Feed workspace state from AppContent**

In `ListManagerApp.kt` `AppContent()`, after the `webSocketService` block add:

```kotlin
    // ===== WORKSPACE STATE =====
    val workspaceManager = remember {
        com.darius.listmanager.data.workspace.WorkspaceManager.getInstance(context)
    }
    val currentWorkspace by workspaceManager.currentWorkspace.collectAsState()
    val teamRepository = remember { com.darius.listmanager.data.repository.TeamRepository() }
    var drawerTeams by remember {
        mutableStateOf<List<com.darius.listmanager.network.TeamDTO>>(emptyList())
    }
    // Refresh the team list whenever the drawer is opened while logged in.
    LaunchedEffect(drawerState.isOpen, isLoggedIn) {
        if (drawerState.isOpen && isLoggedIn) {
            val result = teamRepository.getMyTeams()
            if (result is com.darius.listmanager.data.repository.TeamResult.Success) {
                drawerTeams = result.data
            }
        }
    }
```

and change the `DrawerContent(...)` call to:

```kotlin
            DrawerContent(
                workspaceName = currentWorkspace.displayName,
                teams = drawerTeams,
                onSwitchWorkspace = { workspace ->
                    workspaceManager.switchTo(workspace)
                    scope.launch {
                        drawerState.close()
                        navController.navigate("session") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                onNavigate = { route ->
                    scope.launch {
                        drawerState.close()
                        navController.navigate(route) {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                }
            )
```

(Switching navigates to the session screen so the user immediately sees the workspace's session; `SessionViewModel.observeWorkspace()` does the reload.)

- [ ] **Step 2b: Reset workspace on logout (review finding from Task 3)**

The persisted workspace must not survive a user switch on a shared device. In
`AccountScreen`'s logout path (where `AuthState.clear()` / token clearing is
triggered — follow the existing logout flow from `AccountScreen.kt` /
`AuthViewModel.logout()`), add:

```kotlin
WorkspaceManager.getInstance(context).fallbackToPersonal()
```

so the next login always starts in the Personal workspace.

- [ ] **Step 3: Full build + all tests**

Run: `cd android-native; .\gradlew assembleDebug testDebug`
Expected: BUILD SUCCESSFUL, all unit tests pass.
Run: `cd backend-fastapi; .\venv\Scripts\python.exe -m pytest -q`
Expected: all pass.

- [ ] **Step 4: Manual two-device verification**

With backend running and `adb reverse tcp:8000 tcp:8000`:
1. Install on phone: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
2. Phone: Teams → Create team → confirm switch dialog → session screen shows team name.
3. Phone: team detail → Invite → share code (copy it).
4. Emulator (or second account on phone): Teams → Join with code → switch.
5. Add an item on one device → appears on the other (WebSocket).
6. Switch phone back to Personal → session items differ from the team's.
7. Airplane mode: add item in team workspace → reconnect → syncs.

- [ ] **Step 5: Update PROGRESS.md and commit**

Append to `docs/PROGRESS.md`: what shipped (team UI, workspace switcher, backend self-removal change), the new `teams`/`team_detail` routes, the DB version bump (destructive — local cache wiped once on upgrade, INCLUDING the pending-op queue), and open questions (member usernames in TeamMemberDTO, role management UI).

Also record these two known follow-ups from Task 5 code review (pre-existing,
out of scope for this feature):
1. `session_items` ids: local offline-created items use autogenerated positive
   ids while the resolver/WS mirror server item ids with `@Insert(REPLACE)` on
   the same table — a collision silently replaces an unsynced local row. Same
   class of bug fixed for sessions (negative local ids); items need the same
   treatment or a separate serverId column.
2. Pending ops queued against a NEGATIVE local fallback session id never drain:
   after the resolver activates the server session, replay hits
   `/api/session/{-1}/items` → 404 → retries forever. Follow-up: re-target
   queued session-item ops to the resolved server session id during resolve, or
   drop ops whose session no longer exists.
3. Item stranding (Task 6 review): items added to a negative-id fallback
   session (e.g. voice-adds from Home before the workspace's server session was
   resolved) are orphaned when `activateServerSession` deactivates the fallback
   without migrating its items. Needs item migration + upload once the
   device→server item sync path exists.
4. Resolve-blocking UX (Task 6 review): `resolver.resolve()` runs before items
   display; on a black-hole network the user can watch a spinner for up to the
   30s OkHttp timeouts. Consider `withTimeout(5s)` around resolve or
   cached-items-first + background refresh.
5. DISCOVERED GAP, decided out-of-band: the app has NO device→server upload
   path for session items at all (nothing calls `api.addSessionItem` outside
   the SyncService replay of ops that nothing enqueues; the WebSocket only
   sends pings). Team sessions are one-way (server→device) until a
   write-through/queue path is added for item add/update/delete/clear.

```powershell
git add android-native/app/src/main/java/com/darius/listmanager docs/PROGRESS.md
git commit -m "feat(android): drawer workspace switcher wired to workspace-scoped sessions"
```
