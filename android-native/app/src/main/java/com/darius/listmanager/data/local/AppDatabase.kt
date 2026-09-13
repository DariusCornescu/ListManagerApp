package com.darius.listmanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
    version = 9,
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
                    .addMigrations(MIGRATION_7_8)
                    .addMigrations(MIGRATION_8_9)
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

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing rows cannot be attributed safely. Keep them on disk
                // under a quarantine scope rather than assigning them to the
                // next account that signs in.
                db.execSQL(
                    "ALTER TABLE sessions ADD COLUMN accountUserId INTEGER NOT NULL DEFAULT -1"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN clientUuid TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_clientUuid " +
                        "ON sessions(clientUuid)"
                )
            }
        }
    }
}
