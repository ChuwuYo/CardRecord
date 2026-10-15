package com.shuaji.cards.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@TypeConverters(CardEnumConverters::class)
@Database(
    entities = [CardEntity::class, TransactionEntity::class, CardFolderEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    abstract fun transactionDao(): TransactionDao

    abstract fun cardFolderDao(): CardFolderDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

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

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `card_folders` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `color_argb` INTEGER NOT NULL,
                            `icon_key` TEXT NOT NULL,
                            `sort_order` INTEGER NOT NULL,
                            `created_at_millis` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "ALTER TABLE `credit_cards` ADD COLUMN `folder_id` INTEGER",
                    )
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP INDEX IF EXISTS `index_credit_cards_folder_id`")
                    db.execSQL(
                        """
                        CREATE TABLE `card_folders_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `color_argb` INTEGER NOT NULL,
                            `icon_key` TEXT NOT NULL,
                            `sort_order` INTEGER NOT NULL,
                            `created_at_millis` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `card_folders_new` " +
                            "(`id`, `name`, `color_argb`, `icon_key`, `sort_order`, `created_at_millis`) " +
                            "SELECT `id`, `name`, `color_argb`, `icon_key`, `sort_order`, `created_at_millis` " +
                            "FROM `card_folders`",
                    )
                    db.execSQL("DROP TABLE `card_folders`")
                    db.execSQL("ALTER TABLE `card_folders_new` RENAME TO `card_folders`")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `credit_cards` RENAME TO `cards`")
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE `card_folders_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `color_argb` INTEGER NOT NULL,
                            `sort_order` INTEGER NOT NULL,
                            `created_at_millis` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `card_folders_new` " +
                            "(`id`, `name`, `color_argb`, `sort_order`, `created_at_millis`) " +
                            "SELECT `id`, `name`, `color_argb`, `sort_order`, `created_at_millis` " +
                            "FROM `card_folders`",
                    )
                    db.execSQL("DROP TABLE `card_folders`")
                    db.execSQL("ALTER TABLE `card_folders_new` RENAME TO `card_folders`")

                    // 先重命名父表，SQLite 会把旧 transactions 的 FK 同步指向 cards_old。
                    db.execSQL("ALTER TABLE `cards` RENAME TO `cards_old`")
                    db.execSQL(
                        """
                        CREATE TABLE `cards` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `bank` TEXT NOT NULL,
                            `card_number_masked` TEXT NOT NULL,
                            `valid_until_millis` INTEGER,
                            `next_due_date_millis` INTEGER,
                            `required_count` INTEGER NOT NULL,
                            `color_argb` INTEGER NOT NULL,
                            `note` TEXT NOT NULL,
                            `image_uri` TEXT,
                            `image_source_type` TEXT NOT NULL DEFAULT 'USER',
                            `image_provider_key` TEXT,
                            `card_orientation` TEXT NOT NULL DEFAULT 'LANDSCAPE',
                            `folder_id` INTEGER,
                            `created_at_millis` INTEGER NOT NULL,
                            FOREIGN KEY(`folder_id`) REFERENCES `card_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `cards` (" +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, `folder_id`, `created_at_millis`" +
                            ") SELECT " +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, " +
                            "CASE WHEN `folder_id` IS NULL OR EXISTS (" +
                            "SELECT 1 FROM `card_folders` WHERE `card_folders`.`id` = `cards_old`.`folder_id`" +
                            ") THEN `folder_id` ELSE NULL END, `created_at_millis` " +
                            "FROM `cards_old`",
                    )

                    db.execSQL(
                        """
                        CREATE TABLE `transactions_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `card_id` INTEGER NOT NULL,
                            `occurred_at_millis` INTEGER NOT NULL,
                            FOREIGN KEY(`card_id`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `transactions_new` (`id`, `card_id`, `occurred_at_millis`) " +
                            "SELECT `id`, `card_id`, `occurred_at_millis` FROM `transactions`",
                    )
                    db.execSQL("DROP TABLE `transactions`")
                    db.execSQL("DROP TABLE `cards_old`")
                    db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
                    db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
                }
            }

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `cards` RENAME TO `cards_old`")
                    db.execSQL(
                        """
                        CREATE TABLE `cards` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `bank` TEXT NOT NULL,
                            `card_number_masked` TEXT NOT NULL,
                            `valid_until_millis` INTEGER,
                            `next_due_date_millis` INTEGER,
                            `required_count` INTEGER NOT NULL,
                            `color_argb` INTEGER NOT NULL,
                            `note` TEXT NOT NULL,
                            `image_uri` TEXT,
                            `image_source_type` TEXT NOT NULL DEFAULT 'USER',
                            `image_provider_key` TEXT,
                            `card_orientation` TEXT NOT NULL DEFAULT 'LANDSCAPE',
                            `folder_id` INTEGER,
                            `created_at_millis` INTEGER NOT NULL,
                            FOREIGN KEY(`folder_id`) REFERENCES `card_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `cards` (" +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, `folder_id`, `created_at_millis`" +
                            ") SELECT " +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, " +
                            "CASE WHEN `folder_id` IS NULL OR EXISTS (" +
                            "SELECT 1 FROM `card_folders` WHERE `card_folders`.`id` = `cards_old`.`folder_id`" +
                            ") THEN `folder_id` ELSE NULL END, `created_at_millis` " +
                            "FROM `cards_old`",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE `transactions_new` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `card_id` INTEGER NOT NULL,
                            `occurred_at_millis` INTEGER NOT NULL,
                            FOREIGN KEY(`card_id`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        "INSERT INTO `transactions_new` (`id`, `card_id`, `occurred_at_millis`) " +
                            "SELECT `id`, `card_id`, `occurred_at_millis` FROM `transactions`",
                    )
                    db.execSQL("DROP TABLE `transactions`")
                    db.execSQL("DROP TABLE `cards_old`")
                    db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_folder_id` ON `cards` (`folder_id`)")
                    db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `cards` ADD COLUMN `card_type` TEXT NOT NULL " +
                            "DEFAULT '$CARD_TYPE_UNSPECIFIED_KEY'",
                    )
                    db.execSQL("ALTER TABLE `cards` ADD COLUMN `statement_day` INTEGER")
                    db.execSQL("ALTER TABLE `cards` ADD COLUMN `repayment_day` INTEGER")
                }
            }

        private val MIGRATION_8_9 =
            object : Migration(8, 9) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `cards` ADD COLUMN `image_asset_id` TEXT")
                }
            }

        val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "shuaji.db",
                    ).addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
