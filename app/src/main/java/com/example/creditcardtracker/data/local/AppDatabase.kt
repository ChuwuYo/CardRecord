package com.example.creditcardtracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CreditCardEntity::class, TransactionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun creditCardDao(): CreditCardDao

    abstract fun transactionDao(): TransactionDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：新增卡面来源、卡组织、朝向三列。
         * 旧数据的 image_source_type 默认为 USER（兼容以前的上传图片卡面），
         * card_orientation 默认为 LANDSCAPE（标准横版信用卡）。
         */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE credit_cards ADD COLUMN image_source_type TEXT NOT NULL DEFAULT 'USER'",
                    )
                    db.execSQL(
                        "ALTER TABLE credit_cards ADD COLUMN image_provider_key TEXT",
                    )
                    db.execSQL(
                        "ALTER TABLE credit_cards ADD COLUMN card_orientation TEXT NOT NULL DEFAULT 'LANDSCAPE'",
                    )
                }
            }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "credit_card_tracker.db",
                    ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(true) // 兜底：迁移失败时清库
                    .build()
                    .also { instance = it }
            }
    }
}
