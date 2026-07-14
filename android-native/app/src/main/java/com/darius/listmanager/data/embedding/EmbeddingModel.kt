package com.darius.listmanager.data.embedding

import android.content.Context
import android.util.Log
import com.darius.listmanager.util.VectorMath
import com.ml.shubham0204.sentence_embeddings.SentenceEmbedding
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Loads the bundled ONNX sentence-embedding model once and produces normalized
 * vectors. All failures degrade gracefully to null so callers fall back to
 * fuzzy-only matching. Singleton so the model is loaded at most once per process.
 */
class EmbeddingModel private constructor(private val appContext: Context) {

    private val embedder = SentenceEmbedding()
    private val initMutex = Mutex()

    @Volatile
    private var ready = false

    suspend fun ensureReady(): Boolean {
        if (ready) return true
        initMutex.withLock {
            if (ready) return true
            try {
                val modelFile = File(appContext.filesDir, MODEL_ASSET)
                if (!modelFile.exists()) {
                    appContext.assets.open(MODEL_ASSET).use { input ->
                        modelFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val tokenizerBytes = appContext.assets.open(TOKENIZER_ASSET).use { it.readBytes() }
                embedder.init(
                    modelFilepath = modelFile.absolutePath,
                    tokenizerBytes = tokenizerBytes,
                    useTokenTypeIds = false,
                    outputTensorName = "sentence_embedding",
                    useFP16 = false,
                    useXNNPack = true,
                    normalizeEmbeddings = false
                )
                ready = true
            } catch (e: Throwable) {
                Log.e(TAG, "Embedding model init failed; falling back to fuzzy-only", e)
                ready = false
            }
        }
        return ready
    }

    suspend fun embed(text: String): FloatArray? {
        if (text.isBlank()) return null
        if (!ensureReady()) return null
        return try {
            VectorMath.l2Normalize(embedder.encode(text))
        } catch (e: Throwable) {
            Log.e(TAG, "encode failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "EmbeddingModel"
        const val MODEL_ASSET = "model.onnx"
        const val TOKENIZER_ASSET = "tokenizer.json"
        const val MODEL_VERSION = "minilm-multilingual-v1"

        @Volatile
        private var INSTANCE: EmbeddingModel? = null

        fun getInstance(context: Context): EmbeddingModel =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: EmbeddingModel(context.applicationContext).also { INSTANCE = it }
            }
    }
}
