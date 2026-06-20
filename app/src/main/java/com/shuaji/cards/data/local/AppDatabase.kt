package com.shuaji.cards.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CardEntity::class, TransactionEntity::class, CardFolderEntity::class],
    version = 7,
    exportSchema = false,
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
         * 关键：表结构必须**逐字符匹配** Room kapt 生成的 schema。
         * Room 会在 onValidateSchema 里通过 TableInfo.equals 对比：
         * - icon_key / sort_order 在 @ColumnInfo 里没有 defaultValue
         *   ⇒ 生成的 SQL 是 `icon_key TEXT NOT NULL`（无 DEFAULT）
         *   ⇒ 迁移脚本也不能加 DEFAULT 'folder' / DEFAULT 0
         * - credit_cards 没在 @Entity(indices=...) 声明索引
         *   ⇒ Room 不期望 index_credit_cards_folder_id，迁移不要建这个索引
         * 否则 TableInfo.equals 返回 false、validateSchema 失败，
         * 在某些设备上 fallbackToDestructiveMigration 不彻底会 IllegalStateException。
         *
         * 注：v1.3.4 前主表叫 `credit_cards`，本迁移针对的是从老版本升上来的用户，
         *     必须保留旧表名。
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
         * v1.3.0 的 MIGRATION_2_3 写错了：在 `card_folders` 上加了
         * `DEFAULT 'folder'` / `DEFAULT 0`、在 `credit_cards.folder_id` 上
         * 多建了一个 Room 不期望的索引。Room kapt 生成的 schema 跟它对不上，
         * v1.3.0 启动时 fallbackToDestructiveMigration 触发，把全表清空重建。
         *
         * 这一次：
         * - DROP 坏 schema 的 `card_folders` 表（v1.3.0 留下的，里面没数据）
         * - 用正确 schema 重建
         * - DROP Room 不期望的多余索引
         * - **`credit_cards` 表本身完全不动**，里面历史卡片保留
         *
         * 注：本迁移针对老版本（主表叫 `credit_cards`）的升级路径，保留旧表名。
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 1. 删多余索引（如果存在）
                    db.execSQL("DROP INDEX IF EXISTS `index_credit_cards_folder_id`")
                    // 2. 删坏 schema 的 card_folders 表
                    db.execSQL("DROP TABLE IF EXISTS `card_folders`")
                    // 3. 用正确 schema 重建 card_folders
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
                    // 注意：完全不碰 credit_cards 表，历史卡数据保留
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
         * v5 → v6：**data 层大瘦身**。
         *
         * 三件事：
         * 1. transactions 表瘦化：删 `amount_cents` / `merchant` / `note`
         *    只剩 `id` / `card_id` / `occurred_at_millis`
         * 2. cards 表删 `current_count` / `cycle_start_millis`
         *    —— currentCount 从 transactions COUNT 算，cycleStartMillis 是死字段
         * 3. card_folders 表删 `icon_key` —— 历史从未被 UI 消费的写而不读字段
         *
         * 关键约束（**逐字符匹配 Room kapt 生成的 schema**）：
         * - SQLite **不支持** `ALTER TABLE ... DROP COLUMN`（除非 SQLite ≥ 3.35 且启用）
         *   ⇒ 必须**建新表 → 数据复制 → 删旧表 → 重命名**四步走。
         * - 新表的列定义、NOT NULL、DEFAULT、PRAGMA foreign_keys=OFF 都要精确匹配 Room 期望。
         * - 复制数据时只 SELECT 仍然存在的列，不 SELECT 已删列。
         * - 重命名后再 `PRAGMA foreign_keys=ON` 让外键自检重新生效。
         *
         * PRAGMA foreign_keys=OFF/ON 包住：避免中间步骤触发外键约束。
         * 删 cards 旧表时 transactions 里的 card_id 暂时是悬空引用，
         * 但我们马上 RENAME 新 cards 表回来，引用恢复一致。
         */
        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // 0. 关外键自检 —— 中间表替换不允许被 FK 拦
                    db.execSQL("PRAGMA foreign_keys=OFF")

                    // 1. transactions 表瘦化
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `transactions_new` (
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
                    db.execSQL("ALTER TABLE `transactions_new` RENAME TO `transactions`")
                    db.execSQL("CREATE INDEX `index_transactions_card_id` ON `transactions` (`card_id`)")

                    // 2. cards 表删 current_count / cycle_start_millis / archived
                    //    - archived 字段从未在 UI 上提供过"归档"入口，是死字段
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `cards_new` (
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
                        "INSERT INTO `cards_new` (" +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, `folder_id`, `created_at_millis`" +
                            ") SELECT " +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, `folder_id`, `created_at_millis` " +
                            "FROM `cards`",
                    )
                    db.execSQL("DROP TABLE `cards`")
                    db.execSQL("ALTER TABLE `cards_new` RENAME TO `cards`")

                    // 3. card_folders 表删 icon_key
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `card_folders_new` (
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

                    // 4. 重新打开外键自检
                    db.execSQL("PRAGMA foreign_keys=ON")
                }
            }

        /**
         * v6 → v7：**统一 `cards` 表的外键 schema**，修复历史不一致。
         *
         * 背景（两条互相矛盾的历史路径）：
         * - **全新安装到 v6**：`cards` 表由 Room 按 `CardEntity` 生成。v6 之前的 `CardEntity`
         *   **没声明** `@ForeignKey`/`@Index`，所以这些设备上的 `cards` 表**没有** folder 外键
         *   ⇒ `deleteFolder` 依赖的 `ON DELETE SET NULL` 形同虚设，删文件夹后卡的 `folder_id`
         *   变悬空、不归「未分类」。
         * - **v5 → v6 升级**：`MIGRATION_5_6` 的建表 SQL **写了** `ON DELETE SET NULL` 外键
         *   ⇒ 这些设备的 `cards` 表**有**外键，但与「实体期望无外键」对不上，
         *   Room 启动 `onValidateSchema` 抛 `IllegalStateException`、且不会走 destructive 兜底
         *   （校验失败回滚 ⇒ 这些用户的 `user_version` 实际仍停在 5）。
         *
         * 本迁移把两条路径都收敛到「带外键 + `index_cards_folder_id` 索引」的统一形态，
         * 与新版 `CardEntity` 的 `@ForeignKey(SET_NULL)` + `@Index("folder_id")` 声明逐字符匹配：
         * - 全新 v6 设备（无外键）：重建后获得外键 + 索引。
         * - 曾崩溃、实际停在 v5 的设备：先跑 5→6 建出外键，再跑 6→7 重建并补索引。
         *
         * 走「建新表 → 复制 → 删旧表 → 重命名 → 建索引」四步（SQLite 不支持 ALTER 改外键），
         * 列定义与 `MIGRATION_5_6` 的 `cards` 完全一致，仅补齐外键与索引。
         */
        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("PRAGMA foreign_keys=OFF")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `cards_new` (
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
                        "INSERT INTO `cards_new` (" +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, `folder_id`, `created_at_millis`" +
                            ") SELECT " +
                            "`id`, `name`, `bank`, `card_number_masked`, `valid_until_millis`, " +
                            "`next_due_date_millis`, `required_count`, `color_argb`, `note`, " +
                            "`image_uri`, `image_source_type`, `image_provider_key`, " +
                            "`card_orientation`, `folder_id`, `created_at_millis` " +
                            "FROM `cards`",
                    )
                    db.execSQL("DROP TABLE `cards`")
                    db.execSQL("ALTER TABLE `cards_new` RENAME TO `cards`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_cards_folder_id` ON `cards` (`folder_id`)")
                    db.execSQL("PRAGMA foreign_keys=ON")
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
            )

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "shuaji.db",
                    ).addMigrations(*ALL_MIGRATIONS)
                    // 兜底仍然保留，但写对迁移后这个分支永远走不到
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}
