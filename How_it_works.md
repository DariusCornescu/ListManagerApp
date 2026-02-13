# Pending Operations

`PendingOperationDao` manages **offline changes** that need to be synced to the server when the app comes back online.

## 1.**User Works Offline**

When there's no internet, changes are saved locally:

- Create distributor → Stored in `pending_operations` table with status `PENDING`
- Update product → Stored in `pending_operations` table with status `PENDING`
- Delete item → Stored in `pending_operations` table with status `PENDING`

## 2.**App Comes Online**

When connection is restored:

- `SyncService` fetches all `PENDING` operations
- Sends them to the server API one by one
- Updates their status based on response

## 3. **Status Lifecycle**

| Status | Meaning |
|--------|---------|
| `PENDING` | Waiting to sync |
| `IN_PROGRESS` | Currently syncing |
| `COMPLETED` | Successfully synced, can delete |
| `FAILED` | Sync failed, will retry later |

## Key Methods

### Query (Read)

```kotlin
getAllPending()              // Get all pending changes
getAllPendingFlow()          // Watch pending changes in real-time
getReadyForSync()            // Get changes scheduled for retry
getAllFailed()               // Get failed operations
getPendingCountFlow()        // Watch count of pending items
```

### Modify (Write)

```kotlin
insert(operation)            // Save new offline change
update(operation)            // Update existing change
markAsInProgress(id)         // Mark as syncing
markAsCompleted(id)          // Mark as successfully synced
markAsFailed(id, error)      // Mark as failed, schedule retry
delete(operation)            // Remove operation
deleteAllCompleted()         // Clean up old synced items
```

## Flow vs Suspend

### `Flow<>` - Real-Time (For UI)

```kotlin
getAllPendingFlow().collect { operations ->
    // Updates automatically whenever data changes
    showPendingBadge(operations.size)
}
```

### `suspend` - One-Time (For Syncing)

```kotlin
val pending = getAllPending()
// Returns data once, then done
```

## Example Sync Workflow

```kotlin
// 1. Get pending operations
val pending = dao.getAllPending()

// 2. Process each one
pending.forEach { operation ->
    dao.markAsInProgress(operation.id)
    
    try {
        // Send to server
        api.syncOperation(operation)
        dao.markAsCompleted(operation.id)  // ✅ Success
    } catch (e: Exception) {
        dao.markAsFailed(operation.id, e.message)  // ❌ Retry later
    }
}
```

## Benefits

✅ **No data loss** - Changes saved locally even offline  
✅ **Automatic retry** - Failed syncs scheduled to retry  
✅ **Real-time UI** - UI shows sync progress via Flow  
✅ **Conflict handling** - Server can detect duplicate operations
