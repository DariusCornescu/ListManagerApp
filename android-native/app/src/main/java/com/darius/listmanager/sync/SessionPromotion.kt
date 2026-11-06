package com.darius.listmanager.sync

import androidx.room.withTransaction
import com.darius.listmanager.data.local.AppDatabase
import com.darius.listmanager.data.local.entity.SessionEntity
import com.darius.listmanager.data.model.OperationData
import com.darius.listmanager.network.GlobalSessionDTO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Atomically replaces a temporary offline session id with its server id. */
internal class SessionPromotion(private val database: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun apply(
        localSessionId: Long,
        accountUserId: Long,
        dto: GlobalSessionDTO,
    ) = database.withTransaction {
        val sessions = database.sessionDao()
        val items = database.sessionItemDao()
        val pending = database.pendingOperationDao()
        val local = sessions.getById(localSessionId)

        if (local != null && local.id != dto.id) {
            // The UUID is unique. Release it from the temporary row before
            // assigning it to the server row, while keeping the FK parent alive.
            if (local.clientUuid != null) sessions.update(local.copy(clientUuid = null))
            sessions.upsert(
                SessionEntity(
                    id = dto.id,
                    name = dto.name,
                    createdAt = local.createdAt,
                    isActive = local.isActive && dto.is_active,
                    teamId = dto.team_id,
                    accountUserId = if (dto.team_id == null) {
                        accountUserId
                    } else {
                        SessionEntity.TEAM_SHARED_ACCOUNT_ID
                    },
                    clientUuid = dto.client_uuid ?: local.clientUuid,
                )
            )
            items.moveToSession(localSessionId, dto.id)
        } else if (sessions.getById(dto.id) == null) {
            sessions.upsert(
                SessionEntity(
                    id = dto.id,
                    name = dto.name,
                    isActive = dto.is_active,
                    teamId = dto.team_id,
                    accountUserId = if (dto.team_id == null) {
                        accountUserId
                    } else {
                        SessionEntity.TEAM_SHARED_ACCOUNT_ID
                    },
                    clientUuid = dto.client_uuid,
                )
            )
        }

        pending.getUnfinishedForUser(accountUserId).forEach { operation ->
            val data = runCatching {
                json.decodeFromString<OperationData>(operation.operationData)
            }.getOrNull() ?: return@forEach
            val replacement = when (data) {
                is OperationData.AddSessionItem -> if (data.sessionId == localSessionId) {
                    data.copy(sessionId = dto.id)
                } else data
                is OperationData.ClearSession -> if (data.sessionId == localSessionId) {
                    data.copy(sessionId = dto.id)
                } else data
                is OperationData.CompleteSession -> if (data.sessionId == localSessionId) {
                    data.copy(sessionId = dto.id)
                } else data
                else -> data
            }
            val sessionResource = operation.resourceId == localSessionId &&
                operation.resourceType == "SESSION"
            if (replacement != data || sessionResource) {
                pending.update(
                    operation.copy(
                        resourceId = if (sessionResource) dto.id else operation.resourceId,
                        operationData = json.encodeToString(replacement),
                    )
                )
            }
        }

        if (local != null && local.id != dto.id) sessions.deleteById(localSessionId)
    }
}
