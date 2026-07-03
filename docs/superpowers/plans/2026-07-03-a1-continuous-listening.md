# A1 — Live Continuous Listening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user hold one recording session and dictate many products with pauses; each pause is matched and added to the active session live, with ambiguous items queued to a persisted review list instead of blocking.

**Architecture:** Keep the continuous-listening restart loop inside `AndroidSpeechProvider`, driven by a pure `ListeningLoopPolicy` (JVM-unit-testable). `HomeViewModel` keeps consuming one `Final` per utterance but no longer stops after each item; medium-confidence results go to a new persisted `needs_review` Room table instead of a blocking suggestions UI. A new review screen re-ranks each queued item so the user resolves them in a batch.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.8, Android `SpeechRecognizer`, Coroutines/Flow. Unit tests via JUnit4 (`src/test`), Room tests via instrumented AndroidJUnit4 (`src/androidTest`, in-memory DB), mirroring `Milestone3IntegrationTest`.

**Spec:** `docs/superpowers/specs/2026-07-03-a1-continuous-listening-design.md`

**Conventions for this repo:**
- All Gradle commands run from `android-native/`: e.g. `cd android-native && ./gradlew ...`.
- Instrumented tests (`connectedDebugAndroidTest`) require a connected emulator or device.
- Commit messages: no `Co-Authored-By` trailer (user preference).

---

## File Structure

**Create:**
- `android-native/app/src/main/java/com/darius/listmanager/data/local/entity/NeedsReviewEntity.kt` — Room entity for queued ambiguous items.
- `android-native/app/src/main/java/com/darius/listmanager/data/local/dao/NeedsReviewDao.kt` — DAO.
- `android-native/app/src/main/java/com/darius/listmanager/data/repository/NeedsReviewRepository.kt` — repository wrapper.
- `android-native/app/src/main/java/com/darius/listmanager/data/speech/ListeningLoopPolicy.kt` — pure restart-decision logic.
- `android-native/app/src/test/java/com/darius/listmanager/data/speech/ListeningLoopPolicyTest.kt` — JVM unit test.
- `android-native/app/src/androidTest/java/com/darius/listmanager/NeedsReviewDaoTest.kt` — instrumented DAO test.
- `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/NeedsReviewViewModel.kt` — review-screen VM.
- `android-native/app/src/main/java/com/darius/listmanager/ui/screens/NeedsReviewScreen.kt` — review UI.

**Modify:**
- `android-native/app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt` — register entity + DAO, bump version 2 → 3.
- `android-native/app/src/main/java/com/darius/listmanager/data/speech/AndroidSpeechProvider.kt` — continuous restart loop.
- `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/HomeViewModel.kt` — continuous routing + counts.
- `android-native/app/src/main/java/com/darius/listmanager/ui/screens/HomeScreen.kt` — review card + added-count + nav param.
- `android-native/app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt` — `review` route + pass callback to Home.

---

## Task 1: NeedsReview persistence (entity, DAO, DB registration)

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/local/entity/NeedsReviewEntity.kt`
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/local/dao/NeedsReviewDao.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt`
- Test: `android-native/app/src/androidTest/java/com/darius/listmanager/NeedsReviewDaoTest.kt`

- [ ] **Step 1: Create the entity**

`android-native/app/src/main/java/com/darius/listmanager/data/local/entity/NeedsReviewEntity.kt`:

```kotlin
package com.darius.listmanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A spoken utterance that matched the catalog with only medium confidence
 * (0.60–0.82) during a live listening session. Persisted so the user can
 * resolve it later without breaking the hands-free flow. Kept separate from
 * `unknown_products`: here a good candidate exists and the user picks it, vs.
 * unknown where a brand-new product must be named.
 */
@Entity(tableName = "needs_review")
data class NeedsReviewEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val spokenText: String,
    val sessionId: Long,
    val createdAt: Long
)
```

- [ ] **Step 2: Create the DAO**

`android-native/app/src/main/java/com/darius/listmanager/data/local/dao/NeedsReviewDao.kt`:

```kotlin
package com.darius.listmanager.data.local.dao

import androidx.room.*
import com.darius.listmanager.data.local.entity.NeedsReviewEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NeedsReviewDao {

    @Query("SELECT * FROM needs_review ORDER BY id DESC")
    fun getAllFlow(): Flow<List<NeedsReviewEntity>>

    @Query("SELECT * FROM needs_review")
    suspend fun getAll(): List<NeedsReviewEntity>

    @Insert
    suspend fun insert(item: NeedsReviewEntity): Long

    @Query("DELETE FROM needs_review WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM needs_review")
    suspend fun deleteAll()
}
```

- [ ] **Step 3: Register the entity and DAO in AppDatabase, bump version**

In `AppDatabase.kt`, add `NeedsReviewEntity::class` to the `entities` array, change `version = 2` to `version = 3`, and add the abstract DAO accessor. The class currently reads:

```kotlin
@Database(
    entities = [
        DistributorEntity::class,
        ProductEntity::class,
        ProductFts::class,
        SessionEntity::class,
        SessionItemEntity::class,
        UnknownProductEntity::class,
        PendingOperationEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun distributorDao(): DistributorDao
    abstract fun productDao(): ProductDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionItemDao(): SessionItemDao
    abstract fun unknownDao(): UnknownDao
    abstract fun pendingOperationDao(): PendingOperationDao
```

Change it to:

```kotlin
@Database(
    entities = [
        DistributorEntity::class,
        ProductEntity::class,
        ProductFts::class,
        SessionEntity::class,
        SessionItemEntity::class,
        UnknownProductEntity::class,
        PendingOperationEntity::class,
        NeedsReviewEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun distributorDao(): DistributorDao
    abstract fun productDao(): ProductDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionItemDao(): SessionItemDao
    abstract fun unknownDao(): UnknownDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun needsReviewDao(): NeedsReviewDao
```

The existing `.fallbackToDestructiveMigration()` on the builder handles the schema change (it recreates the DB and re-seeds via the `onCreate` callback), so no manual `Migration` object is needed. The `import com.darius.listmanager.data.local.entity.*` and `...dao.*` wildcard imports already cover the new classes.

- [ ] **Step 4: Write the failing instrumented DAO test**

`android-native/app/src/androidTest/java/com/darius/listmanager/NeedsReviewDaoTest.kt`:

```kotlin
package com.darius.listmanager

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.dao.NeedsReviewDao
import com.darius.listmanager.data.local.entity.NeedsReviewEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeedsReviewDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: NeedsReviewDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.needsReviewDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun insert_thenQuery_returnsRow() = runBlocking {
        val id = dao.insert(
            NeedsReviewEntity(spokenText = "cartafi", sessionId = 7L, createdAt = 1000L)
        )
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("cartafi", all.first().spokenText)
        assertEquals(7L, all.first().sessionId)
        assertEquals(id, all.first().id)
    }

    @Test
    fun deleteById_removesOnlyThatRow() = runBlocking {
        val id1 = dao.insert(NeedsReviewEntity(spokenText = "a", sessionId = 1L, createdAt = 1L))
        dao.insert(NeedsReviewEntity(spokenText = "b", sessionId = 1L, createdAt = 2L))
        dao.deleteById(id1)
        val all = dao.getAll()
        assertEquals(1, all.size)
        assertEquals("b", all.first().spokenText)
    }

    @Test
    fun getAllFlow_emitsInserted() = runBlocking {
        dao.insert(NeedsReviewEntity(spokenText = "x", sessionId = 1L, createdAt = 1L))
        val emitted = dao.getAllFlow().first()
        assertEquals(1, emitted.size)
    }
}
```

- [ ] **Step 5: Run the test to verify it fails (before Steps 1–3 are compiled in) / passes after**

Run (requires a connected emulator/device):

```
cd android-native && ./gradlew connectedDebugAndroidTest --tests "com.darius.listmanager.NeedsReviewDaoTest"
```

Expected after Steps 1–3: BUILD SUCCESSFUL, 3 tests passed. (If Steps 1–3 were skipped, compilation fails on `needsReviewDao()` — that is the "failing" state.)

- [ ] **Step 6: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/local/entity/NeedsReviewEntity.kt \
        android-native/app/src/main/java/com/darius/listmanager/data/local/dao/NeedsReviewDao.kt \
        android-native/app/src/main/java/com/darius/listmanager/data/local/AppDatabase.kt \
        android-native/app/src/androidTest/java/com/darius/listmanager/NeedsReviewDaoTest.kt
git commit -m "feat(db): add needs_review table for ambiguous voice items"
```

---

## Task 2: NeedsReviewRepository

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/repository/NeedsReviewRepository.kt`

- [ ] **Step 1: Create the repository**

Mirrors `UnknownRepository`. `android-native/app/src/main/java/com/darius/listmanager/data/repository/NeedsReviewRepository.kt`:

```kotlin
package com.darius.listmanager.data.repository

import com.darius.listmanager.data.local.dao.NeedsReviewDao
import com.darius.listmanager.data.local.entity.NeedsReviewEntity
import kotlinx.coroutines.flow.Flow

class NeedsReviewRepository(private val dao: NeedsReviewDao) {

    fun getAllFlow(): Flow<List<NeedsReviewEntity>> = dao.getAllFlow()

    suspend fun getAll(): List<NeedsReviewEntity> = dao.getAll()

    suspend fun insert(spokenText: String, sessionId: Long, createdAt: Long): Long {
        return dao.insert(
            NeedsReviewEntity(
                spokenText = spokenText,
                sessionId = sessionId,
                createdAt = createdAt
            )
        )
    }

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun deleteAll() = dao.deleteAll()
}
```

`createdAt` is passed in by the caller (via `System.currentTimeMillis()`) rather than defaulted here, keeping the repository free of clock calls and matching how `SessionEntity` timestamps are set by callers.

- [ ] **Step 2: Verify it compiles**

```
cd android-native && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/repository/NeedsReviewRepository.kt
git commit -m "feat(data): add NeedsReviewRepository"
```

---

## Task 3: Continuous-listening policy (pure) + unit test

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/data/speech/ListeningLoopPolicy.kt`
- Test: `android-native/app/src/test/java/com/darius/listmanager/data/speech/ListeningLoopPolicyTest.kt`

- [ ] **Step 1: Write the failing unit test**

`android-native/app/src/test/java/com/darius/listmanager/data/speech/ListeningLoopPolicyTest.kt`:

```kotlin
package com.darius.listmanager.data.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningLoopPolicyTest {

    @Test
    fun results_whileListening_restarts() {
        assertEquals(LoopAction.RESTART, ListeningLoopPolicy.decide(RecognizerEvent.RESULTS, true))
    }

    @Test
    fun noMatch_whileListening_restarts() {
        assertEquals(LoopAction.RESTART, ListeningLoopPolicy.decide(RecognizerEvent.NO_MATCH, true))
    }

    @Test
    fun speechTimeout_whileListening_restarts() {
        assertEquals(LoopAction.RESTART, ListeningLoopPolicy.decide(RecognizerEvent.SPEECH_TIMEOUT, true))
    }

    @Test
    fun recognizerBusy_whileListening_retriesSoon() {
        assertEquals(LoopAction.RETRY_SOON, ListeningLoopPolicy.decide(RecognizerEvent.RECOGNIZER_BUSY, true))
    }

    @Test
    fun fatalError_whileListening_stops() {
        assertEquals(LoopAction.STOP, ListeningLoopPolicy.decide(RecognizerEvent.FATAL_ERROR, true))
    }

    @Test
    fun results_whenNotListening_stops() {
        assertEquals(LoopAction.STOP, ListeningLoopPolicy.decide(RecognizerEvent.RESULTS, false))
    }

    @Test
    fun timeout_whenNotListening_stops() {
        assertEquals(LoopAction.STOP, ListeningLoopPolicy.decide(RecognizerEvent.SPEECH_TIMEOUT, false))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
cd android-native && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.data.speech.ListeningLoopPolicyTest"
```

Expected: FAIL — unresolved references `LoopAction`, `RecognizerEvent`, `ListeningLoopPolicy`.

- [ ] **Step 3: Write the minimal implementation**

`android-native/app/src/main/java/com/darius/listmanager/data/speech/ListeningLoopPolicy.kt`:

```kotlin
package com.darius.listmanager.data.speech

/** A recognizer lifecycle event the continuous loop reacts to. */
enum class RecognizerEvent {
    RESULTS,          // onResults fired (one utterance finished)
    NO_MATCH,         // ERROR_NO_MATCH
    SPEECH_TIMEOUT,   // ERROR_SPEECH_TIMEOUT (silence)
    RECOGNIZER_BUSY,  // ERROR_RECOGNIZER_BUSY
    FATAL_ERROR       // permissions / audio / client / server errors
}

/** What the provider should do next. */
enum class LoopAction {
    RESTART,     // begin a new recognition immediately
    RETRY_SOON,  // begin a new recognition after a short delay
    STOP         // stop the loop and go Idle/Error
}

/**
 * Pure decision logic for the continuous ("one big listening") restart loop.
 * Extracted from [AndroidSpeechProvider] so it can be unit-tested without the
 * Android framework.
 */
object ListeningLoopPolicy {
    fun decide(event: RecognizerEvent, wantListening: Boolean): LoopAction {
        if (!wantListening) return LoopAction.STOP
        return when (event) {
            RecognizerEvent.RESULTS -> LoopAction.RESTART
            RecognizerEvent.NO_MATCH -> LoopAction.RESTART
            RecognizerEvent.SPEECH_TIMEOUT -> LoopAction.RESTART
            RecognizerEvent.RECOGNIZER_BUSY -> LoopAction.RETRY_SOON
            RecognizerEvent.FATAL_ERROR -> LoopAction.STOP
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```
cd android-native && ./gradlew testDebugUnitTest --tests "com.darius.listmanager.data.speech.ListeningLoopPolicyTest"
```

Expected: BUILD SUCCESSFUL, 7 tests passed.

- [ ] **Step 5: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/speech/ListeningLoopPolicy.kt \
        android-native/app/src/test/java/com/darius/listmanager/data/speech/ListeningLoopPolicyTest.kt
git commit -m "feat(speech): add pure ListeningLoopPolicy with unit tests"
```

---

## Task 4: Continuous restart loop in AndroidSpeechProvider

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/data/speech/AndroidSpeechProvider.kt`

**Behavior:** `startListening()` sets `wantListening = true` and begins. After each `onResults` (emit `Final`) and after recoverable errors, consult `ListeningLoopPolicy` and either restart, retry-after-delay, or stop. `stopListening()` sets `wantListening = false` and stops for real. Restarts are posted to the main-thread `Handler` to avoid re-entrant calls inside recognizer callbacks. `SpeechState` is unchanged (we reuse `Listening` as the between-utterances state), so no exhaustive `when` blocks elsewhere need updating.

- [ ] **Step 1: Replace the provider implementation**

Replace the entire contents of `AndroidSpeechProvider.kt` with:

```kotlin
package com.darius.listmanager.data.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.darius.listmanager.data.repository.SpeechRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidSpeechProvider(private val context: Context) : SpeechRepository {

    private val _speechState = MutableStateFlow<SpeechState>(SpeechState.Idle)
    override val speechState: StateFlow<SpeechState> = _speechState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the user wants a continuous session (between Record and Stop). */
    @Volatile
    private var wantListening = false

    private val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO") // Romanian
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    override fun startListening() {
        Log.d("Speech", "Starting continuous listening...")

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            wantListening = false
            _speechState.value = SpeechState.Error("Speech recognition not available")
            return
        }

        wantListening = true
        beginRecognition()
    }

    override fun stopListening() {
        Log.d("Speech", "Stopping continuous listening")
        wantListening = false
        mainHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("Speech", "Error stopping listener", e)
        }
        _speechState.value = SpeechState.Idle
    }

    /** (Re)create the recognizer and start one utterance. Does NOT change [wantListening]. */
    private fun beginRecognition() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        initializeSpeechRecognizer()
        try {
            speechRecognizer?.startListening(recognizerIntent)
            _speechState.value = SpeechState.Listening
        } catch (e: Exception) {
            Log.e("Speech", "Failed to start listening", e)
            wantListening = false
            _speechState.value = SpeechState.Error("Failed to start: ${e.message}")
        }
    }

    /** Apply the loop policy for [event]; restart, retry after a delay, or stop. */
    private fun applyPolicy(event: RecognizerEvent, errorMessage: String? = null) {
        when (ListeningLoopPolicy.decide(event, wantListening)) {
            LoopAction.RESTART -> mainHandler.post { if (wantListening) beginRecognition() }
            LoopAction.RETRY_SOON -> mainHandler.postDelayed({ if (wantListening) beginRecognition() }, 300)
            LoopAction.STOP -> {
                if (errorMessage != null && wantListening) {
                    // Fatal error while the user still wanted to listen: surface it.
                    _speechState.value = SpeechState.Error(errorMessage)
                } else {
                    _speechState.value = SpeechState.Idle
                }
                wantListening = false
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    val event = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> RecognizerEvent.NO_MATCH
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognizerEvent.SPEECH_TIMEOUT
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognizerEvent.RECOGNIZER_BUSY
                        else -> RecognizerEvent.FATAL_ERROR
                    }
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        else -> "Recognition error: $error"
                    }
                    Log.d("Speech", "onError code=$error -> $event")
                    applyPolicy(event, errorMessage = message)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    Log.d("Speech", "Final result: $text")
                    if (text.isNotBlank()) {
                        _speechState.value = SpeechState.Final(text)
                    }
                    applyPolicy(RecognizerEvent.RESULTS)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotBlank()) {
                        _speechState.value = SpeechState.Partial(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    override fun release() {
        Log.d("Speech", "Releasing speech recognizer")
        wantListening = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
cd android-native && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/data/speech/AndroidSpeechProvider.kt
git commit -m "feat(speech): continuous restart loop in AndroidSpeechProvider"
```

---

## Task 5: HomeViewModel continuous routing + counts

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/HomeViewModel.kt`

**Behavior:** Stop calling `resetSpeechState()` after each item so the loop keeps running. Route `Suggestions` to the persisted `needs_review` table instead of the blocking suggestions UI. Add `sessionAddedCount` (reset on `startListening`, incremented on each auto-add) and `reviewCount` (collected from the needs-review flow) to the UI state.

- [ ] **Step 1: Add reviewCount + sessionAddedCount to HomeUiState**

Replace the `HomeUiState` data class:

```kotlin
data class HomeUiState( val speechState: SpeechState = SpeechState.Idle, val suggestions: List<RankedProduct> = emptyList(), val message: String? = null, val isProcessing: Boolean = false, val unknownProductCount: Int = 0 )
```

with:

```kotlin
data class HomeUiState(
    val speechState: SpeechState = SpeechState.Idle,
    val suggestions: List<RankedProduct> = emptyList(),
    val message: String? = null,
    val isProcessing: Boolean = false,
    val unknownProductCount: Int = 0,
    val reviewCount: Int = 0,
    val sessionAddedCount: Int = 0
)
```

- [ ] **Step 2: Add the NeedsReviewRepository field and collect its count**

After the existing `unknownRepository` field (line ~29):

```kotlin
    private val unknownRepository = UnknownRepository(database.unknownDao())
```

add:

```kotlin
    private val needsReviewRepository = NeedsReviewRepository(database.needsReviewDao())
```

No new import is needed: `HomeViewModel` already has the wildcard `import com.darius.listmanager.data.repository.*`, which covers `NeedsReviewRepository`.

Then in the `init { }` block, after the unknown-products collector, add a second collector:

```kotlin
        // Collect needs-review count
        viewModelScope.launch {
            needsReviewRepository.getAllFlow().collect { items ->
                _uiState.value = _uiState.value.copy(reviewCount = items.size)
            }
        }
```

- [ ] **Step 3: Reset the added-count when a session starts**

Replace `startListening()`:

```kotlin
    fun startListening() {
        speechRepository.startListening()
        _uiState.value = _uiState.value.copy( suggestions = emptyList(), message = null )
    }
```

with:

```kotlin
    fun startListening() {
        speechRepository.startListening()
        _uiState.value = _uiState.value.copy(
            suggestions = emptyList(),
            message = null,
            sessionAddedCount = 0
        )
    }
```

- [ ] **Step 4: Rewrite processSpokenText branches for continuous flow**

Replace the whole `when (val result = ...)` block inside `processSpokenText` with:

```kotlin
                when (val result = resolveSpokenProductUseCase.execute(spokenText)) {
                    is ResolveResult.AutoAdd -> {
                        Log.d(TAG, "AutoAdd: ${result.product.name} (score: ${result.score})")
                        val session = sessionRepository.getOrCreateActiveSession()
                        addProductUseCase.execute(session.id, result.product.id, 1)
                        _uiState.value = _uiState.value.copy(
                            message = "Adăugat: ${result.product.name}",
                            suggestions = emptyList(),
                            isProcessing = false,
                            sessionAddedCount = _uiState.value.sessionAddedCount + 1
                        )
                        // Keep listening — do NOT reset speech state.
                    }
                    is ResolveResult.Suggestions -> {
                        Log.d(TAG, "Ambiguous -> needs review: '$spokenText'")
                        val session = sessionRepository.getOrCreateActiveSession()
                        needsReviewRepository.insert(
                            spokenText = spokenText,
                            sessionId = session.id,
                            createdAt = System.currentTimeMillis()
                        )
                        _uiState.value = _uiState.value.copy(
                            message = "De verificat: '$spokenText'",
                            suggestions = emptyList(),
                            isProcessing = false
                        )
                        // Keep listening.
                    }
                    is ResolveResult.Unknown -> {
                        Log.d(TAG, "Unknown: ${result.spokenText}")
                        unknownRepository.insert(result.spokenText)
                        _uiState.value = _uiState.value.copy(
                            message = "Nerecunoscut: '${result.spokenText}'. Salvat.",
                            suggestions = emptyList(),
                            isProcessing = false
                        )
                        // Keep listening.
                    }
                }
```

This removes the two `resetSpeechState()` calls that previously stopped the recognizer after AutoAdd and Unknown. `resetSpeechState()` and `addSuggestedProduct()` remain defined (still used by the Stop/clear buttons and the — now unpopulated — suggestions card), so no other code breaks.

- [ ] **Step 5: Verify it compiles**

```
cd android-native && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/HomeViewModel.kt
git commit -m "feat(home): continuous voice routing to needs-review + session counts"
```

---

## Task 6: NeedsReviewViewModel

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/NeedsReviewViewModel.kt`

**Behavior:** Collect the needs-review flow. For each queued item, re-run `ResolveSpokenProductUseCase` to produce fresh candidate products (this is why we only stored `spokenText`). Expose a list of `ReviewItem(id, spokenText, candidates)`. Tapping a candidate adds it to the active session and deletes the review row; "not here" moves the item to `unknown_products` and deletes the review row.

- [ ] **Step 1: Create the ViewModel**

`android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/NeedsReviewViewModel.kt`:

```kotlin
package com.darius.listmanager.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.repository.*
import com.darius.listmanager.data.usecase.AddProductUseCase
import com.darius.listmanager.data.usecase.ResolveResult
import com.darius.listmanager.data.usecase.ResolveSpokenProductUseCase
import com.darius.listmanager.network.RetrofitClient
import com.darius.listmanager.util.RankedProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReviewItem(
    val id: Long,
    val spokenText: String,
    val candidates: List<RankedProduct>
)

data class NeedsReviewUiState(
    val items: List<ReviewItem> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null
)

class NeedsReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val pendingOperationRepo = PendingOperationRepository(database.pendingOperationDao())
    private val productRepository = ProductRepository(
        database.productDao(), pendingOperationRepo, application.applicationContext, RetrofitClient.api
    )
    private val sessionRepository = SessionRepository(database.sessionDao(), database.sessionItemDao())
    private val unknownRepository = UnknownRepository(database.unknownDao())
    private val needsReviewRepository = NeedsReviewRepository(database.needsReviewDao())
    private val resolveSpokenProductUseCase = ResolveSpokenProductUseCase(productRepository)
    private val addProductUseCase = AddProductUseCase(sessionRepository)

    private val _uiState = MutableStateFlow(NeedsReviewUiState())
    val uiState: StateFlow<NeedsReviewUiState> = _uiState.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            needsReviewRepository.getAllFlow().collect { rows ->
                val items = rows.map { row ->
                    val candidates = when (val r = resolveSpokenProductUseCase.execute(row.spokenText)) {
                        is ResolveResult.Suggestions -> r.products
                        is ResolveResult.AutoAdd -> listOf(RankedProduct(r.product, r.score))
                        is ResolveResult.Unknown -> emptyList()
                    }
                    ReviewItem(id = row.id, spokenText = row.spokenText, candidates = candidates)
                }
                _uiState.value = _uiState.value.copy(items = items, isLoading = false)
            }
        }
    }

    fun addCandidate(reviewId: Long, productId: Long, productName: String) {
        viewModelScope.launch {
            try {
                val session = sessionRepository.getOrCreateActiveSession()
                addProductUseCase.execute(session.id, productId, 1)
                needsReviewRepository.deleteById(reviewId)
                _uiState.value = _uiState.value.copy(message = "Adăugat: $productName")
            } catch (e: Exception) {
                Log.e("NeedsReviewVM", "addCandidate failed", e)
                _uiState.value = _uiState.value.copy(message = "Eroare: ${e.message}")
            }
        }
    }

    fun sendToUnknown(reviewId: Long, spokenText: String) {
        viewModelScope.launch {
            unknownRepository.insert(spokenText)
            needsReviewRepository.deleteById(reviewId)
            _uiState.value = _uiState.value.copy(message = "Mutat la necunoscute: '$spokenText'")
        }
    }

    fun dismiss(reviewId: Long) {
        viewModelScope.launch { needsReviewRepository.deleteById(reviewId) }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}
```

Note: `RankedProduct` already defaults its `breakdown` map, so `RankedProduct(r.product, r.score)` is valid.

- [ ] **Step 2: Verify it compiles**

```
cd android-native && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/ui/viewmodel/NeedsReviewViewModel.kt
git commit -m "feat(review): add NeedsReviewViewModel that re-ranks queued items"
```

---

## Task 7: NeedsReviewScreen

**Files:**
- Create: `android-native/app/src/main/java/com/darius/listmanager/ui/screens/NeedsReviewScreen.kt`

- [ ] **Step 1: Create the screen**

Mirrors `UnknownProductsScreen` structure. `android-native/app/src/main/java/com/darius/listmanager/ui/screens/NeedsReviewScreen.kt`:

```kotlin
package com.darius.listmanager.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.darius.listmanager.ui.viewmodel.NeedsReviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedsReviewScreen(
    onBack: () -> Unit,
    viewModel: NeedsReviewViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("De verificat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            uiState.items.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Nimic de verificat",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "Ai zis: \"${item.spokenText}\"",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                HorizontalDivider()
                                if (item.candidates.isEmpty()) {
                                    Text(
                                        "Niciun candidat.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    item.candidates.forEach { candidate ->
                                        Surface(
                                            onClick = {
                                                viewModel.addCandidate(
                                                    item.id,
                                                    candidate.product.id,
                                                    candidate.product.name
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceContainerLow
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    candidate.product.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    Icons.Rounded.Add,
                                                    contentDescription = "Adaugă",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.sendToUnknown(item.id, item.spokenText)
                                        }
                                    ) { Text("Niciunul — la necunoscute") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```
cd android-native && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/ui/screens/NeedsReviewScreen.kt
git commit -m "feat(review): add NeedsReviewScreen"
```

---

## Task 8: Wire navigation and Home UI (review card + added count)

**Files:**
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt`
- Modify: `android-native/app/src/main/java/com/darius/listmanager/ui/screens/HomeScreen.kt`

- [ ] **Step 1: Add the `review` route and pass a callback to Home in NavGraph**

In `NavGraph.kt`, add the import:

```kotlin
import com.darius.listmanager.ui.screens.NeedsReviewScreen
```

Inside the `composable("home") { HomeScreen( ... ) }` call, add a new navigation callback argument (after `onNavigateToUnknown`):

```kotlin
                onNavigateToReview = { navController.navigate("review") },
```

And add a new composable route alongside the `unknown` one:

```kotlin
        composable("review") {
            NeedsReviewScreen(onBack = { navController.popBackStack() })
        }
```

- [ ] **Step 2: Add the `onNavigateToReview` parameter to HomeScreen**

In `HomeScreen.kt`, change the signature from:

```kotlin
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSession: () -> Unit,
    onNavigateToUnknown: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAccount: () -> Unit,
    isLoggedIn: Boolean = false,
    username: String? = null,
    viewModel: HomeViewModel = viewModel()
) {
```

to:

```kotlin
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSession: () -> Unit,
    onNavigateToUnknown: () -> Unit,
    onNavigateToReview: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAccount: () -> Unit,
    isLoggedIn: Boolean = false,
    username: String? = null,
    viewModel: HomeViewModel = viewModel()
) {
```

- [ ] **Step 3: Show the live added-count while listening**

In `HomeScreen.kt`, directly after the status `Text(...)` block that ends at the `SpeechState.Error -> "Eroare: ${state.message}"` `when` (the block whose closing is `color = MaterialTheme.colorScheme.onSurfaceVariant )`), insert:

```kotlin
                if (uiState.speechState is SpeechState.Listening || uiState.sessionAddedCount > 0) {
                    Text(
                        "Adăugate în sesiune: ${uiState.sessionAddedCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
```

- [ ] **Step 4: Add a "needs review" card that navigates to the review screen**

In `HomeScreen.kt`, immediately before the existing "Produse necunoscute" `Card(...)` (the one with `onClick = onNavigateToUnknown`), insert a review card:

```kotlin
                if (uiState.reviewCount > 0) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ),
                        onClick = onNavigateToReview
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "De verificat",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    ) {
                                        Text(
                                            "${uiState.reviewCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Apasă pentru a confirma ${uiState.reviewCount} produs(e) ambigue",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                            Icon(
                                Icons.Rounded.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
```

- [ ] **Step 5: Verify it compiles**

```
cd android-native && ./gradlew compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. If the compiler reports `onNavigateToReview` missing at a `HomeScreen(` call site, ensure Step 1 added the argument in `NavGraph.kt`.

- [ ] **Step 6: Commit**

```bash
git add android-native/app/src/main/java/com/darius/listmanager/ui/navigation/NavGraph.kt \
        android-native/app/src/main/java/com/darius/listmanager/ui/screens/HomeScreen.kt
git commit -m "feat(home): review nav card + live added count; wire review route"
```

---

## Task 9: Full build + manual verification

**Files:** none (verification only).

- [ ] **Step 1: Assemble the debug APK**

```
cd android-native && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the unit + instrumented test suites**

```
cd android-native && ./gradlew testDebugUnitTest
cd android-native && ./gradlew connectedDebugAndroidTest
```

Expected: all green, including `ListeningLoopPolicyTest`, `NeedsReviewDaoTest`, and the existing `Milestone3IntegrationTest`. (Instrumented run needs a device/emulator.)

- [ ] **Step 3: Manual smoke test on a device/emulator**

Install and verify the live flow (this is the part not covered by automated tests):

1. Tap the mic → status shows "Ascultare...".
2. Say a clearly-catalogued product (e.g. "lapte"), pause. It is added; "Adăugate în sesiune" increments; the mic keeps listening (no manual re-tap).
3. Say a misspelled/ambiguous product (e.g. "cartafi"), pause. Nothing blocks; the "De verificat" card appears/increments on Home.
4. Say nonsense, pause. It lands in "Produse necunoscute"; listening continues.
5. Tap **Stop** → recognizer stops, status returns to idle.
6. Open **De verificat** → each item shows candidate products; tapping one adds it to the session and removes it from the list; "Niciunul" moves it to unknown.
7. Confirm the session screen contains the auto-added + confirmed items with correct quantities (duplicates increment).

- [ ] **Step 4: Commit any fixes found during manual testing**

```bash
git add -A
git commit -m "fix(a1): address issues found during manual verification"
```

---

## Notes / explicitly deferred (from spec)

- **Quantity parsing** ("două lapte" → qty 2) is out of scope; auto-add uses qty 1.
- **B2 embeddings** is the next track.
- **Backend hosting** for multi-phone sync is a separate track.
- The optional silence safety auto-stop from the spec is **not** implemented here (pending the open question raised at spec review); the recognizer keeps listening until Stop. If desired, add it as a follow-up by tracking consecutive `SPEECH_TIMEOUT` restarts in `AndroidSpeechProvider` and calling `stopListening()` after a threshold.
- The Home suggestions card is now unpopulated (medium-confidence items go to review). It is left in place to minimize churn; removing it is a safe follow-up cleanup.
