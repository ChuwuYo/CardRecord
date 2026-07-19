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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room 迁移 / 外键 schema 一致性测试。
 *
 * 历史上 `cards` 表的外键声明在「实体」与「迁移 SQL」之间不一致：
 * - `MIGRATION_5_6` 的建表 SQL 写了 `ON DELETE SET NULL` 外键；
 * - 但 v7 之前的 `CardEntity` 没声明 `@ForeignKey`/`@Index`。
 *
 * 后果：v5→v6 升级用户启动时 Room schema 校验抛 `IllegalStateException`；
 * 全新安装用户的 `cards` 表没外键，`deleteFolder` 依赖的 SET NULL 不生效。
 * v7（实体补外键 + `MIGRATION_6_7` 统一磁盘 schema）修复后，下面两条用例应通过。
 *
 * 这些回归测试手工建历史库，再用真实 `ALL_MIGRATIONS` 升到最新版并触发 schema 校验；
 * 导出的 schema JSON 另供 Room 自动迁移审阅与 CI 完整性检查。
 * 若外键不一致，`open` 会抛异常、测试失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /**
     * v5 → 最新：升级路径不崩 + 历史数据（含 folder_id 引用）保留。
     *
     * 修复前，Room 打开时会因
     * 「实体期望无外键 vs 迁移后磁盘有外键」校验失败抛异常。
     */
    @Test
    fun migrateFromV5_opensWithoutCrashAndPreservesData() {
        createV5DatabaseWithSampleRow()

        val db =
            Room
                .databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(*AppDatabase.ALL_MIGRATIONS)
                .build()

        try {
            // 强制打开 → 触发 5→6→7→8 迁移 + onValidateSchema（出 bug 会在此抛异常）。
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
            assertEquals("历史卡不能被猜成信用卡", CardType.UNSPECIFIED.key, card.cardType)
            assertNull(card.statementDay)
            assertNull(card.repaymentDay)
            assertEquals(1, runBlocking { db.transactionDao().listAll() }.size)
            assertEquals(8, db.openHelper.readableDatabase.version)
            assertForeignKeysClean(db)
        } finally {
            db.close()
        }
    }

    /**
     * 删除文件夹时，外键 `ON DELETE SET NULL` 自动把卡片 folder_id 置空（卡片归未分类）。
     * 这条用例覆盖「全新安装」路径下 SET NULL 是否真正生效。
     */
    @Test
    fun deletingFolder_setsCardsFolderIdToNull() {
        val db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .build()

        try {
            runBlocking {
                val folderId = db.cardFolderDao().insert(CardFolderEntity(name = "商旅", colorArgb = 0xFF1234))
                val cardId =
                    db.cardDao().upsert(
                        CardEntity(
                            name = "Visa",
                            bank = "某银行",
                            cardNumberMasked = "**** 1234",
                            requiredCount = 6,
                            colorArgb = 0xFF1234,
                            folderId = folderId,
                        ),
                    )

                db.cardFolderDao().delete(
                    CardFolderEntity(id = folderId, name = "商旅", colorArgb = 0xFF1234),
                )

                val card = db.cardDao().getById(cardId)
                assertNotNull("删文件夹不应删卡片", card)
                assertNull("删文件夹后卡片应归未分类（folder_id 置空）", requireNotNull(card).folderId)
            }
        } finally {
            db.close()
        }
    }

    /**
     * 手工建一个 version=5 的库，并写入一个 folder + 一张引用它的卡片。
     *
     * v5 表结构 = `MIGRATION_5_6` 执行**之前**的形态：
     * - `cards`：含后来被删的 current_count / cycle_start_millis / archived，且**无 folder 外键**
     *   （还原历史：v7 之前实体没声明外键，故 v5 建表也没有）。
     * - `transactions`：含后来被删的 amount_cents / merchant / note，带 card 外键 + 索引。
     * - `card_folders`：含后来被删的 icon_key。
     */
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
                    // 一个文件夹 + 一张引用它的卡片（验证迁移后 folder_id 仍被保留）
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
                    // 测试只建 v5；真正的升级交给被测的 ALL_MIGRATIONS。
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
        helper.writableDatabase.use { /* 触发 onCreate，落地 v5 schema + 样例数据 */ }
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
