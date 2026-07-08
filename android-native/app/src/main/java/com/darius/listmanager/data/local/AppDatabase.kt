package com.darius.listmanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.darius.listmanager.data.local.dao.*
import com.darius.listmanager.data.local.entity.*
import com.darius.listmanager.util.DatabaseSeeder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        DistributorEntity::class,
        ProductEntity::class,
        ProductFts::class,
        SessionEntity::class,
        SessionItemEntity::class,
        UnknownProductEntity::class,
        PendingOperationEntity::class,
        NeedsReviewEntity::class,
        ProductEmbeddingEntity::class,
        InventoryItemEntity::class
    ],
    version = 7,
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
    abstract fun productEmbeddingDao(): ProductEmbeddingDao
    abstract fun inventoryItemDao(): InventoryItemDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "list_manager_db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Seed database on first creation
                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    DatabaseSeeder.seed(database)
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}