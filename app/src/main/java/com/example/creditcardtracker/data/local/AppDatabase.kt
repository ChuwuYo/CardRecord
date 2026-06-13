package com.example.creditcardtracker.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CreditCardEntity::class, TransactionEntity::class, CardFolderEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun creditCardDao(): CreditCardDao

    abstract fun transactionDao(): TransactionDao

    abstract fun cardFolderDao(): CardFolderDao

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

        /**
         * v2 → v3：新增「文件夹」概念。
         * 1. 新建 card_folders 表
         * 2. credit_cards 增加 folder_id 列（可空，默认 NULL 即未分类）
         * 3. 建索引加速按文件夹过滤
         */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS card_folders (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            name TEXT NOT NULL,
                            color_argb INTEGER NOT NULL,
                            icon_key TEXT NOT NULL DEFAULT 'folder',
                            sort_order INTEGER NOT NULL DEFAULT 0,
                            created_at_millis INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "ALTER TABLE credit_cards ADD COLUMN folder_id INTEGER",
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS index_credit_cards_folder_id ON credit_cards(folder_id)",
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
                    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(true) // 兜底：迁移失败时清库
                    .build()
                    .also { instance = it }
            }
    }
}
