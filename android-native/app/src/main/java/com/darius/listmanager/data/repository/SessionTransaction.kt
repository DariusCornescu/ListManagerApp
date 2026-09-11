package com.darius.listmanager.data.repository

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import kotlinx.coroutines.sync.Mutex

/** A local edit and its outbox entry must commit or roll back together. */
fun interface SessionTransaction {
    suspend fun run(block: suspend () -> Unit)

    companion object {
        fun room(database: AppDatabase) = SessionTransaction { block ->
            database.withTransaction { block() }
        }
    }
}

/** All repository instances and the background sender share this process lock. */
object SessionWriteCoordinator {
    val mutex = Mutex()
}
