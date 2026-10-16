package com.shuaji.cards.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrateFromV5_opensWithoutCrashAndPreservesData() {
        createV5DatabaseWithSampleRow()

        val db =
            Room
                .databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .build()

        try {
            val cards = runBlocking { db.cardDao().listAll() }
            assertEquals("历史卡片应被迁移保留", 1, cards.size)
            assertEquals("迁移不应丢失 folder_id 引用", 1L, cards.single().folderId)
            assertEquals("重建 cards 父表不能级联删除历史流水", 1, runBlocking { db.transactionDao().listAll() }.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateFromV3_preservesExistingFoldersAndAssignments() {
        createV3DatabaseWithSampleFolder()

        val db = openMigratedDatabase()
        try {
            val folders = runBlocking { db.cardFolderDao().listAll() }
            val cards = runBlocking { db.cardDao().listAll() }

            assertEquals("v3 已有文件夹不能被迁移删除", listOf("商旅"), folders.map { it.name })
            assertEquals(folders.single().id, cards.single().folderId)
            assertForeignKeysClean(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateFromV5_nullsDanglingFolderIdAndLeavesForeignKeysClean() {
        createV5DatabaseWithSampleRow(folderId = 999L, insertFolder = false)

        val db = openMigratedDatabase()
        try {
            assertNull(
                runBlocking {
                    db
                        .cardDao()
                        .listAll()
                        .single()
                        .folderId
                },
            )
            assertEquals(1, runBlocking { db.transactionDao().listAll() }.size)
            assertForeignKeysClean(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateFromV6_nullsDanglingFolderIdAndLeavesForeignKeysClean() {
        createV6DatabaseWithDanglingFolder()

        val db = openMigratedDatabase()
        try {
            assertNull(
                runBlocking {
                    db
                        .cardDao()
                        .listAll()
                        .single()
                        .folderId
                },
            )
            assertEquals("v6 重建 cards 不能级联删除历史流水", 1, runBlocking { db.transactionDao().listAll() }.size)
            assertForeignKeysClean(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateFromV7_preservesExistingCardAsUnspecifiedWithoutCreditDays() {
        createV7DatabaseWithSampleCard()

        val db = openMigratedDatabase()
        try {
            val card = runBlocking { db.cardDao().listAll().single() }
            assertEquals("历史卡不能被猜成信用卡", CardType.UNSPECIFIED, card.cardType)
            assertNull(card.statementDay)
            assertNull(card.repaymentDay)
            assertEquals(1, runBlocking { db.transactionDao().listAll() }.size)
            assertEquals(9, db.openHelper.readableDatabase.version)
            assertForeignKeysClean(db)
        } finally {
            db.close()
        }
    }

    @Test
    fun migrateFromV8_preservesLegacyImageUri_andAddsEmptyOwnedAssetReference() {
        createV8DatabaseWithLegacyImage()

        val db = openMigratedDatabase()
        try {
            val card = runBlocking { db.cardDao().listAll().single() }

            assertEquals("content://gallery/legacy", card.imageUri)
            assertNull("文件复制由应用层幂等迁移负责，Room 迁移不能伪造资产", card.imageAssetId)
            assertEquals(9, db.openHelper.readableDatabase.version)
            assertForeignKeysClean(db)
        } finally {
            db.close()
        }
    }

    private fun createV5DatabaseWithSampleRow(
        folderId: Long = 1L,
        insertFolder: Boolean = true,
    ) {
        context.deleteDatabase(dbName)
        val callback =
            object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
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
                            `current_count` INTEGER NOT NULL DEFAULT 0,
                            `cycle_start_millis` INTEGER,
                            `archived` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE `transactions` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `card_id` INTEGER NOT NULL,
                            `occurred_at_millis` INTEGER NOT NULL,
                            `amount_cents` INTEGER NOT NULL DEFAULT 0,
                            `merchant` TEXT NOT NULL DEFAULT '',
                            `note` TEXT NOT NULL DEFAULT '',
                            FOREIGN KEY(`card_id`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
                    db.execSQL(
                        """
                        CREATE TABLE `card_folders` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `name` TEXT NOT NULL,
                            `color_argb` INTEGER NOT NULL,
                            `icon_key` TEXT NOT NULL,
                            `sort_order` INTEGER NOT NULL,
                            `created_at_millis` INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    if (insertFolder) {
                        db.execSQL(
                            "INSERT INTO `card_folders` " +
                                "(`id`, `name`, `color_argb`, `icon_key`, `sort_order`, `created_at_millis`) " +
                                "VALUES (1, '商旅', 255, 'folder', 0, 0)",
                        )
                    }
                    db.execSQL(
                        "INSERT INTO `cards` (" +
                            "`id`, `name`, `bank`, `card_number_masked`, `required_count`, `color_argb`, " +
                            "`note`, `image_source_type`, `card_orientation`, `folder_id`, " +
                            "`created_at_millis`, `current_count`, `archived`" +
                            ") VALUES (1, 'Visa', '某银行', '**** 1234', 6, 255, '', 'USER', 'LANDSCAPE', " +
                            "$folderId, 0, 0, 0)",
                    )
                    db.execSQL(
                        "INSERT INTO transactions (id, card_id, occurred_at_millis, amount_cents, merchant, note) " +
                            "VALUES (1, 1, 123, 0, '', '')",
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                }
            }

        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(dbName)
                    .callback(callback)
                    .build(),
            )
        helper.writableDatabase.use { }
        helper.close()
    }

    private fun createV3DatabaseWithSampleFolder() {
        createHistoricalDatabase(version = 3) { db ->
            createLegacyCardTables(db, cardTable = "credit_cards")
            db.execSQL(
                """
                CREATE TABLE `card_folders` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `color_argb` INTEGER NOT NULL,
                    `icon_key` TEXT NOT NULL DEFAULT 'folder',
                    `sort_order` INTEGER NOT NULL DEFAULT 0,
                    `created_at_millis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_credit_cards_folder_id` ON `credit_cards` (`folder_id`)")
            db.execSQL(
                "INSERT INTO card_folders (id, name, color_argb, icon_key, sort_order, created_at_millis) " +
                    "VALUES (1, '商旅', 255, 'folder', 0, 0)",
            )
            insertLegacyCard(db, "credit_cards", folderId = 1L)
        }
    }

    private fun createV6DatabaseWithDanglingFolder() {
        createHistoricalDatabase(version = 6) { db ->
            db.execSQL(
                """
                CREATE TABLE `card_folders` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `color_argb` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `created_at_millis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `cards` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL, `bank` TEXT NOT NULL,
                    `card_number_masked` TEXT NOT NULL, `valid_until_millis` INTEGER,
                    `next_due_date_millis` INTEGER, `required_count` INTEGER NOT NULL,
                    `color_argb` INTEGER NOT NULL, `note` TEXT NOT NULL, `image_uri` TEXT,
                    `image_source_type` TEXT NOT NULL DEFAULT 'USER', `image_provider_key` TEXT,
                    `card_orientation` TEXT NOT NULL DEFAULT 'LANDSCAPE', `folder_id` INTEGER,
                    `created_at_millis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `transactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `card_id` INTEGER NOT NULL, `occurred_at_millis` INTEGER NOT NULL,
                    FOREIGN KEY(`card_id`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
            db.execSQL(
                "INSERT INTO cards (id, name, bank, card_number_masked, required_count, color_argb, note, " +
                    "image_source_type, card_orientation, folder_id, created_at_millis) " +
                    "VALUES (1, 'Visa', '某银行', '**** 1234', 6, 255, '', 'USER', 'LANDSCAPE', 999, 0)",
            )
            db.execSQL("INSERT INTO transactions (id, card_id, occurred_at_millis) VALUES (1, 1, 123)")
        }
    }

    private fun createV7DatabaseWithSampleCard() {
        createHistoricalDatabase(version = 7) { db ->
            db.execSQL(
                """
                CREATE TABLE `card_folders` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `color_argb` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `created_at_millis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `cards` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL, `bank` TEXT NOT NULL,
                    `card_number_masked` TEXT NOT NULL, `valid_until_millis` INTEGER,
                    `next_due_date_millis` INTEGER, `required_count` INTEGER NOT NULL,
                    `color_argb` INTEGER NOT NULL, `note` TEXT NOT NULL, `image_uri` TEXT,
                    `image_source_type` TEXT NOT NULL DEFAULT 'USER', `image_provider_key` TEXT,
                    `card_orientation` TEXT NOT NULL DEFAULT 'LANDSCAPE', `folder_id` INTEGER,
                    `created_at_millis` INTEGER NOT NULL,
                    FOREIGN KEY(`folder_id`) REFERENCES `card_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_cards_folder_id` ON `cards` (`folder_id`)")
            db.execSQL(
                """
                CREATE TABLE `transactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `card_id` INTEGER NOT NULL, `occurred_at_millis` INTEGER NOT NULL,
                    FOREIGN KEY(`card_id`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
            db.execSQL(
                "INSERT INTO cards (id, name, bank, card_number_masked, required_count, color_argb, note, " +
                    "image_source_type, card_orientation, created_at_millis) " +
                    "VALUES (1, '历史卡', '某银行', '**** 1234', 6, 255, '', 'USER', 'LANDSCAPE', 0)",
            )
            db.execSQL("INSERT INTO transactions (id, card_id, occurred_at_millis) VALUES (1, 1, 123)")
        }
    }

    private fun createV8DatabaseWithLegacyImage() {
        createHistoricalDatabase(version = 8) { db ->
            db.execSQL(
                """
                CREATE TABLE `card_folders` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `color_argb` INTEGER NOT NULL,
                    `sort_order` INTEGER NOT NULL,
                    `created_at_millis` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE `cards` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL, `bank` TEXT NOT NULL,
                    `card_number_masked` TEXT NOT NULL,
                    `card_type` TEXT NOT NULL DEFAULT 'UNSPECIFIED',
                    `statement_day` INTEGER, `repayment_day` INTEGER,
                    `valid_until_millis` INTEGER, `next_due_date_millis` INTEGER,
                    `required_count` INTEGER NOT NULL, `color_argb` INTEGER NOT NULL,
                    `note` TEXT NOT NULL, `image_uri` TEXT,
                    `image_source_type` TEXT NOT NULL DEFAULT 'USER', `image_provider_key` TEXT,
                    `card_orientation` TEXT NOT NULL DEFAULT 'LANDSCAPE', `folder_id` INTEGER,
                    `created_at_millis` INTEGER NOT NULL,
                    FOREIGN KEY(`folder_id`) REFERENCES `card_folders`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_cards_folder_id` ON `cards` (`folder_id`)")
            db.execSQL(
                """
                CREATE TABLE `transactions` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `card_id` INTEGER NOT NULL, `occurred_at_millis` INTEGER NOT NULL,
                    FOREIGN KEY(`card_id`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
            db.execSQL(
                "INSERT INTO cards (id, name, bank, card_number_masked, card_type, required_count, " +
                    "color_argb, note, image_uri, image_source_type, card_orientation, created_at_millis) " +
                    "VALUES (1, '历史图片卡', '', '', 'UNSPECIFIED', 6, 255, '', " +
                    "'content://gallery/legacy', 'USER', 'LANDSCAPE', 0)",
            )
        }
    }

    private fun createLegacyCardTables(
        db: SupportSQLiteDatabase,
        cardTable: String,
    ) {
        db.execSQL(
            """
            CREATE TABLE `$cardTable` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL, `bank` TEXT NOT NULL, `card_number_masked` TEXT NOT NULL,
                `valid_until_millis` INTEGER, `next_due_date_millis` INTEGER,
                `required_count` INTEGER NOT NULL, `color_argb` INTEGER NOT NULL,
                `note` TEXT NOT NULL, `image_uri` TEXT,
                `image_source_type` TEXT NOT NULL DEFAULT 'USER', `image_provider_key` TEXT,
                `card_orientation` TEXT NOT NULL DEFAULT 'LANDSCAPE', `folder_id` INTEGER,
                `created_at_millis` INTEGER NOT NULL, `current_count` INTEGER NOT NULL DEFAULT 0,
                `cycle_start_millis` INTEGER, `archived` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE `transactions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `card_id` INTEGER NOT NULL,
                `occurred_at_millis` INTEGER NOT NULL, `amount_cents` INTEGER NOT NULL DEFAULT 0,
                `merchant` TEXT NOT NULL DEFAULT '', `note` TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(`card_id`) REFERENCES `$cardTable`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")
    }

    private fun insertLegacyCard(
        db: SupportSQLiteDatabase,
        table: String,
        folderId: Long,
    ) {
        db.execSQL(
            "INSERT INTO `$table` (id, name, bank, card_number_masked, required_count, color_argb, note, " +
                "image_source_type, card_orientation, folder_id, created_at_millis, current_count, archived) " +
                "VALUES (1, 'Visa', '某银行', '**** 1234', 6, 255, '', 'USER', 'LANDSCAPE', " +
                "$folderId, 0, 0, 0)",
        )
    }

    private fun createHistoricalDatabase(
        version: Int,
        create: (SupportSQLiteDatabase) -> Unit,
    ) {
        context.deleteDatabase(dbName)
        val callback =
            object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) = create(db)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            }
        val helper =
            FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(dbName)
                    .callback(callback)
                    .build(),
            )
        helper.writableDatabase.use { }
        helper.close()
    }

    private fun openMigratedDatabase(): AppDatabase =
        Room
            .databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

    private fun assertForeignKeysClean(db: AppDatabase) {
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals("迁移后不应留下外键脏数据", 0, cursor.count)
        }
    }
}
