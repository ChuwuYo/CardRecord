package com.shuaji.cards.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CardEntity::class, TransactionEntity::class, CardFolderEntity::class],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    abstract fun transactionDao(): TransactionDao

    abstract fun cardFolderDao(): CardFolderDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * v1 → v2：新增卡面来源、卡组织、朝向三列。
         * 旧数据的 image_source_type 默认为 USER（兼容以前的上传图片卡面），
         * card_orientation 默认为 LANDSCAPE（标准横版卡片）。
         *
         * 注：v1.0 时代主表叫 `credit_cards`，MIGRATION_1_2 是从 v1 升上来的
         * 老用户会执行到的路径，必须保留旧表名。v1.3.4 起新表名才叫 `cards`。
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
         *
         * 历史 v2 实体没有为 icon_key / sort_order 声明默认值，也没有 folder_id 索引；
         * 迁移必须还原当时 Room 校验所期望的结构。v1.3.4 前主表名为 `credit_cards`。
         */
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

        /**
         * v3 → v4：修复 v1.3.0 留下的坏 schema。
         *
         * 旧实现为文件夹列增加了实体未声明的默认值，并多建了 folder_id 索引，
         * 导致 Room schema 校验不一致。本迁移逐列重建文件夹表并移除多余索引；
         * `credit_cards` 及其历史卡片不改动。
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. 删多余索引（如果存在）
                    db.execSQL("DROP INDEX IF EXISTS `index_credit_cards_folder_id`")
                    // 2. 用正确 schema 建临时表并逐列复制，不能假设旧表里没有用户数据。
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
                    // credit_cards 表本身完全不动，历史卡片与 folder_id 关联都保留。
                }
            }

        /**
         * v4 → v5：主表重命名。
         *
         * v1.3.4 改类名/包名/项目名时同步把 Room 主表 `credit_cards` 重命名为 `cards`。
         * 数据完全保留，只是 SQLite 内部对表名做 RENAME。
         * Foreign key 引用会自动跟随新表名（SQLite RENAME 会更新 sqlite_master 里的引用）。
         *
         * **历史 bug 修复**：v1.3.4 ~ v1.4.0 的代码里这版迁移的 version 一直误写为 4
         * （没有真正升到 5），所以 v1.3.4+ 用户设备上 schema 仍是 v4、表名仍是
         * `credit_cards`。本迁移在 v1.4.0 升到 version=6 后才**真正**被执行：
         * 老用户从设备 v4 → v5 → v6，路径 v4→v5 跑这里 RENAME。
         */
        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `credit_cards` RENAME TO `cards`")
                }
            }

        /**
         * v5 → v6：移除没有读取路径的列。
         *
         * 三件事：
         * 1. transactions 表瘦化：删 `amount_cents` / `merchant` / `note`
         *    只剩 `id` / `card_id` / `occurred_at_millis`
         * 2. cards 表删 `current_count` / `cycle_start_millis`
         *    —— currentCount 由 Repository 按统计窗口从 transactions 派生，cycleStartMillis 是死字段
         * 3. card_folders 表删 `icon_key` —— 历史从未被 UI 消费的写而不读字段
         *
         * 为兼容受支持的 Android SQLite 版本并保持约束准确，三张表都用建新表、复制、重命名。
         * Room 在事务中执行迁移，事务内切换 `PRAGMA foreign_keys` 不生效；因此先重命名父表，
         * 再把子表接到新父表，最后删除旧父表，避免级联删除历史流水。
         */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. v5 的 cards 尚未声明 folder 外键，先安全瘦化文件夹表。
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

                    // 2. 先重命名父表，SQLite 会把旧 transactions 的 FK 同步指向 cards_old。
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

                    // 3. 重建子表并直接引用新 cards；复制完成后才删除旧子表和旧父表。
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

        /**
         * v6 → v7：统一 `cards.folder_id` 的 SET NULL 外键与索引。
         * 全新 v6 库没有该外键，v5→v6 路径却已建出外键；本迁移重建 cards 与 transactions，
         * 让两条历史路径收敛，同时保留全部卡片和流水并把悬空 folder_id 归一化为 NULL。
         */
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

        /**
         * v7 → v8：新增卡片资金属性及信用卡账期字段。
         *
         * 历史卡没有足够信息判断借记或信用，必须保留为 UNSPECIFIED；两个日号保持 NULL，
         * 等用户编辑时主动选择，不能用迁移默认值替用户做业务推断。
         */
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

        /**
         * 全部迁移，按版本顺序。`get()` 与迁移测试共用同一份，避免「测试漏注册某条迁移」。
         */
        val ALL_MIGRATIONS: Array<Migration> =
            arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
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
