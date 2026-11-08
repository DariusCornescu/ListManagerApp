package com.darius.listmanager.network

import androidx.room.Room
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.PendingOperationRepository
import com.darius.listmanager.data.repository.ProductRepository
import com.darius.listmanager.data.repository.SessionRepository
import com.darius.listmanager.data.repository.SessionTransaction
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import com.darius.listmanager.data.workspace.Workspace
import com.darius.listmanager.data.workspace.WorkspaceSessionResolver
import com.darius.listmanager.sync.CatalogRefresher
import com.darius.listmanager.sync.InMemoryCatalogEtagStore
import com.darius.listmanager.util.SpokenQuantity
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * The Android client against a real FastAPI backend: the app's own Retrofit interface, DTOs,
 * token-refresh authenticator, Room database, catalog refresh, voice resolver and session
 * repository, with only the HTTP wiring rebuilt here so it can point at [LIVE_BACKEND_URL].
 *
 * Without `LIVE_BACKEND_URL` every test is skipped, so ordinary unit runs stay offline. CI also
 * sets `REQUIRE_LIVE_BACKEND=true`, which turns a missing URL into a failure instead of a skip.
 *
 * Locally: start the backend (`uvicorn app.main:app`) with `RATE_LIMIT_ENABLED=false`, then
 * `LIVE_BACKEND_URL=http://127.0.0.1:8000/ ./gradlew testDebugUnitTest --tests "*BackendContractE2ETest"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackendContractE2ETest {

    private lateinit var baseUrl: String
    private lateinit var database: AppDatabase

    @Before
    fun requireBackend() {
        val url = System.getenv("LIVE_BACKEND_URL")?.trim().orEmpty()
        if (url.isEmpty() && System.getenv("REQUIRE_LIVE_BACKEND") == "true") {
            fail("REQUIRE_LIVE_BACKEND is set but LIVE_BACKEND_URL is empty")
        }
        assumeTrue("LIVE_BACKEND_URL not set; skipping the live backend contract", url.isNotEmpty())
        baseUrl = if (url.endsWith("/")) url else "$url/"
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() {
        if (::database.isInitialized) database.close()
    }

    // ---------------------------------------------------------------------------------------
    // Wiring: what RetrofitClient builds, pointed at the live backend.
    // ---------------------------------------------------------------------------------------

    private class MemoryTokenStore : AuthTokenStore {
        @Volatile var access: String? = null
        @Volatile var refresh: String? = null
        override fun accessToken() = access
        override fun refreshToken() = refresh
        override fun saveTokens(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }
    }

    private interface RefreshApi {
        @POST("/api/auth/refresh")
        fun refresh(@Body request: RefreshTokenRequest): Call<LoginResponse>
    }

    private fun retrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private fun bareClient() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val refreshApi by lazy { retrofit(bareClient()).create(RefreshApi::class.java) }

    /** The same mapping RetrofitClient.requestRefresh makes. */
    private fun requestRefresh(refreshToken: String): RefreshResult = try {
        val response = refreshApi.refresh(RefreshTokenRequest(refreshToken)).execute()
        val body = response.body()
        when {
            response.isSuccessful && body?.refresh_token != null ->
                RefreshResult.Success(body.access_token, body.refresh_token)
            response.code() == 401 || response.code() == 403 -> RefreshResult.Rejected
            else -> RefreshResult.Unavailable
        }
    } catch (e: IOException) {
        RefreshResult.Unavailable
    }

    private fun api(store: MemoryTokenStore): ListManagerApi {
        val bearer = Interceptor { chain ->
            val token = store.accessToken()
            val request = if (token == null) chain.request()
            else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
            chain.proceed(request)
        }
        val client = bareClient().newBuilder()
            .addInterceptor(bearer)
            .authenticator(TokenRefreshAuthenticator(store, ::requestRefresh))
            .build()
        return retrofit(client).create(ListManagerApi::class.java)
    }

    private class Account(val api: ListManagerApi, val store: MemoryTokenStore, val user: UserDTO)

    /** A fresh user each time, so tests never share a session or depend on seeded accounts. */
    private suspend fun signUpAndIn(): Account {
        val store = MemoryTokenStore()
        val api = api(store)
        val name = "e2e_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val password = "Contract-" + UUID.randomUUID().toString().take(8)

        val registered = api.register(RegisterRequest(name, "$name@example.com", password))
        assertEquals("register: ${registered.errorBody()?.string()}", 200, registered.code())
        assertEquals("USER", registered.body()!!.role)

        val login = api.login(LoginRequest(name, password))
        assertEquals("login: ${login.errorBody()?.string()}", 200, login.code())
        val tokens = login.body()!!
        assertEquals("bearer", tokens.token_type.lowercase())
        assertNotNull("login must return a refresh token", tokens.refresh_token)
        store.saveTokens(tokens.access_token, tokens.refresh_token!!)

        val me = api.getCurrentUser()
        assertEquals(200, me.code())
        assertEquals(name, me.body()!!.username)
        return Account(api, store, me.body()!!)
    }

    private fun resolver(account: Account) = WorkspaceSessionResolver(
        database = database,
        api = account.api,
        currentUserId = { account.user.id },
        // A store of its own: a shared one would answer 304 for a database that holds nothing.
        catalogRefresher = CatalogRefresher(database, account.api, InMemoryCatalogEtagStore()),
    )

    private fun pendingOps(account: Account) =
        PendingOperationRepository(database.pendingOperationDao()) { account.user.id }

    private suspend fun resolveSession(account: Account): Long {
        val result = resolver(account).resolve(Workspace.Personal)
        assertTrue("resolver result: $result", result is WorkspaceSessionResolver.ResolveResult.Resolved)
        return (result as WorkspaceSessionResolver.ResolveResult.Resolved).sessionId
    }

    private suspend fun resolveMilk(): Long {
        val products = ProductRepository(
            dao = database.productDao(),
            pendingOperationRepository = PendingOperationRepository(database.pendingOperationDao()),
            context = RuntimeEnvironment.getApplication(),
            api = api(MemoryTokenStore()),
            database = database,
            isOnline = { false },
        )
        val spoken = "două lapte"
        assertEquals(2, SpokenQuantity.from(spoken))
        val top = when (val result = ResolveSpokenProductUseCase(products).execute(spoken)) {
            is ResolveResult.AutoAdd -> result.product
            is ResolveResult.Suggestions -> result.products.first().product
            is ResolveResult.Unknown -> error("\"$spoken\" matched nothing in the seeded catalog")
        }
        assertEquals("Lapte Zuzu 1L", top.name)
        return top.id
    }

    // ---------------------------------------------------------------------------------------
    // Contract
    // ---------------------------------------------------------------------------------------

    @Test
    fun auth_expiredAccessIsRefreshedAndRotated_andAReplayedRefreshBurnsTheFamily() = runBlocking {
        val account = signUpAndIn()
        val firstRefresh = account.store.refresh!!

        // An access token the server can't verify answers 401; the app's authenticator refreshes
        // once and retries, so the caller only ever sees the 200.
        account.store.access = "not-a-valid-jwt"
        val me = account.api.getCurrentUser()
        assertEquals(200, me.code())
        assertEquals(account.user.id, me.body()!!.id)

        val rotated = account.store.refresh!!
        assertNotEquals("refresh must rotate the refresh token", firstRefresh, rotated)
        assertNotEquals("not-a-valid-jwt", account.store.access)

        // Inside the server's grace window a replayed token still gets a successor (the first
        // reply may have been lost), and that revokes the one the app is holding...
        val replay = requestRefresh(firstRefresh)
        assertTrue("replay within the grace window: $replay", replay is RefreshResult.Success)

        // ...so when the app next needs a refresh, it is refused and the whole family is burned:
        // the 401 reaches the caller, which is what sends the app back to sign-in.
        account.store.access = "not-a-valid-jwt"
        assertEquals(401, account.api.getCurrentUser().code())
        assertEquals(rotated, account.store.refresh)
        assertEquals(
            "the replayer's token dies with the family",
            RefreshResult.Rejected,
            requestRefresh((replay as RefreshResult.Success).refreshToken),
        )
    }

    @Test
    fun catalog_hydratesRoom_andSpokenRomanianResolvesToTheSeededProduct() = runBlocking {
        val account = signUpAndIn()

        // The catalog is public: the raw endpoints answer without a token.
        val anonymous = api(MemoryTokenStore())
        val distributors = anonymous.getDistributors()
        val products = anonymous.getProducts()
        assertEquals(200, distributors.code())
        assertEquals(200, products.code())
        assertTrue("seed has distributors", distributors.body()!!.isNotEmpty())
        assertTrue("seed has Lapte Zuzu 1L", products.body()!!.any { it.name == "Lapte Zuzu 1L" })

        resolveSession(account)
        assertEquals(products.body()!!.size, database.productDao().count())
        assertNotNull(database.distributorDao().getById(products.body()!!.first().distributor_id))

        val milkId = resolveMilk()
        assertEquals(products.body()!!.single { it.name == "Lapte Zuzu 1L" }.id, milkId)
    }

    @Test
    fun session_addUpdateConflictAndComplete_throughTheWriteThroughRepository() = runBlocking {
        val account = signUpAndIn()
        val sessionId = resolveSession(account)
        val milkId = resolveMilk()
        val outbox = pendingOps(account)
        val sessions = SessionRepository(
            sessionDao = database.sessionDao(),
            sessionItemDao = database.sessionItemDao(),
            api = account.api,
            pendingOps = outbox,
            transaction = SessionTransaction.room(database),
            currentUserId = { account.user.id },
        )

        // Add two, as dictated. Write-through leaves nothing waiting in the outbox.
        sessions.addOrIncrementItem(sessionId, milkId, SpokenQuantity.from("două lapte"))
        assertTrue("add left work queued: ${outbox.getUnfinished()}", outbox.getUnfinished().isEmpty())
        val added = account.api.getSessionItems(sessionId).body()!!.single { it.product_id == milkId }
        assertEquals(2, added.quantity)
        assertEquals(added.version, database.sessionItemDao().getItem(sessionId, milkId)!!.version)

        // Change it to three: the repository sends the version it holds and stores the new one.
        sessions.setItemQuantity(sessionId, milkId, 3)
        assertTrue("update left work queued: ${outbox.getUnfinished()}", outbox.getUnfinished().isEmpty())
        val updated = account.api.getSessionItems(sessionId).body()!!.single { it.product_id == milkId }
        assertEquals(3, updated.quantity)
        assertTrue("version must increase: ${added.version} -> ${updated.version}", updated.version > added.version)
        assertEquals(updated.version, database.sessionItemDao().getItem(sessionId, milkId)!!.version)

        // A writer still holding the old version is refused with the server's current one.
        val stale = account.api.updateSessionItem(updated.id, UpdateItemRequest(5, added.version))
        assertEquals(409, stale.code())
        val detail = JsonParser.parseString(stale.errorBody()!!.string()).asJsonObject["detail"].asJsonObject
        assertEquals(updated.version, detail["current_version"].asInt)
        assertEquals(3, account.api.getSessionItems(sessionId).body()!!.single().quantity)

        // Completing groups the list by distributor for the PDFs.
        sessions.completeSessionWriteThrough(sessionId)
        assertTrue("complete left work queued: ${outbox.getUnfinished()}", outbox.getUnfinished().isEmpty())
        val completed = account.api.completeSession(sessionId)
        assertEquals(200, completed.code())
        val body = completed.body()!!
        assertEquals(false, body.session.is_active)
        assertEquals(1, body.distributor_count)
        assertEquals("total_items sums quantities", 3, body.total_items)
        val grouped = body.items_by_distributor.values.single().single()
        assertEquals(milkId, grouped.product_id)
        assertEquals(3, grouped.quantity)
        assertTrue(
            "Lapte Zuzu is seeded under METRO: ${body.items_by_distributor.keys}",
            body.items_by_distributor.keys.single().contains("METRO"),
        )
        assertEquals(404, account.api.getActiveSession().code())
    }
}
