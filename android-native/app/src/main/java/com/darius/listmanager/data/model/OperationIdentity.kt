package com.darius.listmanager.data.model

import java.util.UUID

/** Normalize before persistence, never generate a key inside a network retry. */
fun OperationData.withOperationIdentity(): OperationData = when (this) {
    is OperationData.AddSessionItem ->
        if (idempotencyKey != null) this else copy(idempotencyKey = UUID.randomUUID().toString())
    is OperationData.UpdateSessionItem ->
        if (idempotencyKey != null) this else copy(idempotencyKey = UUID.randomUUID().toString())
    else -> this
}
