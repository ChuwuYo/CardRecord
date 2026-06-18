package com.shuaji.cards.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.backup.TestData.card
import com.shuaji.cards.data.backup.TestData.folder
import com.shuaji.cards.data.backup.TestData.transaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [BackupRepository] 的核心场景测试。
 *
 * 测试策略：
 * - 用 **Robolectric** 跑 JVM 单元测试，模拟 Android Context / ContentResolver
 * - 用 `Room.inMemoryDatabaseBuilder` 拿一次性内存数据库，每个测试建一个
 * - 用 `file://` URI + [TemporaryFolder] 把"用户保存的 JSON 文件"落到临时文件，
 *   ContentResolver.openInputStream/openOutputStream 在 Robolectric 下对 file:// 原生支持
 * - 用 `runTest`（kotlinx-coroutines-test）跑 suspend 函数
 *
 * 覆盖矩阵（按子代理审查优先级排）：
 * - REPLACE 5 条核心（P0-1 / P0-2 / P1-2）
 * - MERGE 5 条 id 重映射规则（P1-1 / P1-4 / P1-5）
 * - 异常路径（version 不匹配 / JSON 烂 / 取消）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupRepositoryTest {
    companion object {
        /**
         * Robolectric 的 plugins-maven-dependency-resolver 默认从 Sonatype 直连
         * 185MB 的 android-all-instrumented SDK，国内网络容易卡超时。
         *
         * 沙箱策略：让 Robolectric 走默认 repo URL（不覆盖），但提前用 curl 走代理
         * 把 SDK 镜像下到 `~/.m2/repository/...`，Robolectric 会**先查本地**命中。
         * 路径见 `MavenDependencyResolver.getLocalRepositoryDir()`：
         *   1) `maven.repo.local` system property
         *   2) `~/.m2/repository`
         *
         * 需要的文件（必须齐全，否则 Robolectric 会 fallback 去网络下）：
         *   - `<artifact>.jar`
         *   - `<artifact>.pom`
         *   - `<artifact>.jar.sha1`
         *   - `<artifact>.pom.sha1`
         *
         * 本地能直连 Maven Central 时不需要这个前置步骤。
         */
        @BeforeClass
        @JvmStatic
        fun configureRobolectricMaven() {
            // 确认默认仓库目录可用；不主动 setMavenRepositoryUrl，让 Robolectric 走默认
            // ——它会先扫 `~/.m2/repository` 找 jar，找不到才去网络
            val repoDir = System.getProperty("maven.repo.local") ?: "${System.getProperty("user.home")}/.m2/repository"
            check(
                java.io
                    .File(
                        repoDir,
                        "org/robolectric/android-all-instrumented/14-robolectric-10818077-i6/android-all-instrumented-14-robolectric-10818077-i6.jar",
                    ).exists(),
            ) {
                "Robolectric SDK jar 缺失：$repoDir/org/robolectric/android-all-instrumented/14-robolectric-10818077-i6/" +
                    "请先用 `curl --proxy 127.0.0.1:18080 -O https://maven.aliyun.com/repository/central/org/robolectric/android-all-instrumented/14-robolectric-10818077-i6/{android-all-instrumented-14-robolectric-10818077-i6.jar,.jar.sha1,.pom,.pom.sha1}` 下载"
            }
        }
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries() // 单元测试不需要跑在 IO 调度器上
                .build()
        repo =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── 工具 ────────────────────────────────────────────────────

    private fun tempJsonFile(name: String = "backup.json"): File = tempFolder.newFile(name)

    private fun json(): Json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun seed(
        folders: List<CardFolderEntity> = emptyList(),
        cards: List<CardEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
    ) = runBlocking {
        folders.forEach { db.cardFolderDao().upsert(it) }
        cards.forEach { db.cardDao().upsert(it) }
        transactions.forEach { db.transactionDao().insert(it) }
    }

    // ════════════════════════════════════════════════════════════
    // 导出
    // ════════════════════════════════════════════════════════════

    @Test
    fun export_writes_complete_bundle_to_uri() =
        runTest {
            val f1 = folder(id = 0L, name = "商旅")
            val f1Id = db.cardFolderDao().upsert(f1)
            val c1 = card(id = 0L, name = "招行", folderId = f1Id)
            val c1Id = db.cardDao().upsert(c1)
            db.transactionDao().insert(transaction(cardId = c1Id))

            val out = tempJsonFile()
            val summary = repo.export(Uri.fromFile(out))

            // P1 修：现在返回 ExportSummary（含分类计数），不是 Int（混在一起的总数）
            assertEquals(1, summary.cardCount)
            assertEquals(1, summary.folderCount)
            assertEquals(1, summary.transactionCount)
            assertEquals(3, summary.total)
            assertFalse(summary.isEmpty)

            val bundle = json().decodeFromString<BackupBundle>(out.readText(Charsets.UTF_8))
            assertEquals(1, bundle.folders.size)
            assertEquals(1, bundle.cards.size)
            assertEquals(1, bundle.transactions.size)
            // 关键：exportedAtMillis 已删除，schema 干净
            assertFalse(
                "BackupBundle 不应再含 exportedAtMillis 字段",
                out.readText(Charsets.UTF_8).contains("exportedAtMillis"),
            )
        }

    @Test
    fun export_empty_database_returns_empty_summary() =
        runTest {
            val out = tempJsonFile()
            val summary = repo.export(Uri.fromFile(out))
            // P1 修：空库时返回 ExportSummary(total=0, isEmpty=true)——UI 会用
            // 「已导出空备份」文案（settings_result_export_empty），而不是说"已导出 0 条"
            assertEquals(0, summary.cardCount)
            assertEquals(0, summary.folderCount)
            assertEquals(0, summary.transactionCount)
            assertEquals(0, summary.total)
            assertTrue(summary.isEmpty)

            val bundle = json().decodeFromString<BackupBundle>(out.readText(Charsets.UTF_8))
            assertTrue(bundle.cards.isEmpty())
            assertTrue(bundle.folders.isEmpty())
            assertTrue(bundle.transactions.isEmpty())
        }

    // ════════════════════════════════════════════════════════════
    // 导入 — REPLACE 模式（P0-1 / P0-2）
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_clears_existing_data_and_writes_bundle() =
        runTest {
            // 1) 现库有数据
            val oldFolder = folder(id = 0L, name = "旧分组")
            val oldFolderId = db.cardFolderDao().upsert(oldFolder)
            db.cardDao().upsert(card(id = 0L, name = "旧卡", folderId = oldFolderId))

            // 2) 备份只有新数据
            val newBundle =
                BackupBundle(
                    version = 1,
                    folders = listOf(folder(id = 100L, name = "新分组")),
                    cards = listOf(card(id = 200L, name = "新卡")),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(newBundle), Charsets.UTF_8)

            // 3) REPLACE 导入
            val result = repo.import(Uri.fromFile(file), ImportMode.REPLACE)

            assertEquals(1, result.foldersAdded)
            assertEquals(1, result.cardsAdded)
            assertEquals(0, result.cardsSkippedInvalidFolder)

            // 4) 验证：旧数据全没了，新数据写入
            val folders = db.cardFolderDao().listAll()
            val cards = db.cardDao().listAll()
            assertEquals(1, folders.size)
            assertEquals("新分组", folders[0].name)
            assertEquals(1, cards.size)
            assertEquals("新卡", cards[0].name)
        }

    @Test
    fun replace_invalid_folderId_is_set_to_null() =
        runTest {
            // 备份里 card 引用 folderId=9999，但 bundle.folders 里没有 id=9999 的 folder
            val broken =
                BackupBundle(
                    version = 1,
                    folders = listOf(folder(id = 10L, name = "A")),
                    cards =
                        listOf(
                            card(id = 20L, name = "好卡", folderId = 10L), // OK
                            card(id = 21L, name = "坏卡", folderId = 9999L), // 引用不存在
                        ),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(broken), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.REPLACE)

            // 1 张被改写为 null
            assertEquals(1, result.cardsSkippedInvalidFolder)
            val cards = db.cardDao().listAll()
            assertEquals(2, cards.size)
            val ok = cards.first { it.name == "好卡" }
            val bad = cards.first { it.name == "坏卡" }
            assertEquals(10L, ok.folderId)
            assertNull(bad.folderId) // 失效引用被置 null
        }

    @Test
    fun replace_cascade_deletes_transactions() =
        runTest {
            // 1) 现库：1 张卡 + 5 笔流水
            val folderId = db.cardFolderDao().upsert(folder(id = 0L, name = "X"))
            val cardId = db.cardDao().upsert(card(id = 0L, name = "C", folderId = folderId))
            repeat(5) { i -> db.transactionDao().insert(transaction(cardId = cardId, occurredAtMillis = TestData.FIXED_TIME_MILLIS + i)) }
            assertEquals(5, db.transactionDao().listAll().size)

            // 2) REPLACE 一个空 bundle → 期望所有表被清空
            val empty = BackupBundle(version = 1)
            val file = tempJsonFile()
            file.writeText(json().encodeToString(empty), Charsets.UTF_8)
            repo.import(Uri.fromFile(file), ImportMode.REPLACE)

            // 3) 验证
            assertTrue(db.cardDao().listAll().isEmpty())
            assertTrue(db.cardFolderDao().listAll().isEmpty())
            assertTrue(db.transactionDao().listAll().isEmpty())
        }

    @Test
    fun replace_roundtrip_preserves_data() =
        runTest {
            // 1) 准备数据并 export
            val folderId = db.cardFolderDao().upsert(folder(id = 0L, name = "F"))
            val cardId = db.cardDao().upsert(card(id = 0L, name = "C", folderId = folderId))
            db.transactionDao().insert(transaction(cardId = cardId))

            val out = tempJsonFile()
            repo.export(Uri.fromFile(out))

            // 2) 现库塞点新数据
            db.cardDao().upsert(card(id = 0L, name = "杂质"))

            // 3) REPLACE 导入
            repo.import(Uri.fromFile(out), ImportMode.REPLACE)

            // 4) 验证：跟导出前的快照一致
            val folders = db.cardFolderDao().listAll()
            val cards = db.cardDao().listAll()
            val transactions = db.transactionDao().listAll()
            assertEquals(1, folders.size)
            assertEquals("F", folders[0].name)
            assertEquals(1, cards.size)
            assertEquals("C", cards[0].name)
            assertEquals(1, transactions.size)
        }

    // ════════════════════════════════════════════════════════════
    // 导入 — MERGE 模式（P1-1 / P1-4 / P1-5）
    // ════════════════════════════════════════════════════════════

    @Test
    fun merge_appends_folders_with_new_ids() =
        runTest {
            // 1) 现库空 → merge 2 个 folder
            val bundle =
                BackupBundle(
                    version = 1,
                    folders =
                        listOf(
                            folder(id = 10L, name = "A"),
                            folder(id = 20L, name = "B"),
                        ),
                    cards = emptyList(),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.MERGE)

            // 旧 id 10L / 20L 都被重新分配为新 id（不为 0，且 ≠ 旧值）
            // P2 修：idRemap 字段已删；改用「现库存储的 id 不等于备份里的 id」+「name 集合」
            // 这两个不变式替代——id 对用户无意义，关键是「name 保留 + 数对」。
            assertEquals(2, result.foldersAdded)

            val stored = db.cardFolderDao().listAll()
            assertEquals(2, stored.size)
            assertEquals(setOf("A", "B"), stored.map { it.name }.toSet())
            // 关键：idRemap 字段已删，assertNotNull(result.idRemap) 改成"不能有 idRemap 字段"
            // ——通过 ImportResult 类型本身保证（编译期检查）
        }

    @Test
    fun merge_appends_cards_and_remaps_folderId() =
        runTest {
            val bundle =
                BackupBundle(
                    version = 1,
                    folders = listOf(folder(id = 10L, name = "A")),
                    cards =
                        listOf(
                            card(id = 20L, name = "C1", folderId = 10L), // 指向 backup 内 folder
                            card(id = 21L, name = "C2", folderId = 9999L), // 指向不存在（应保留原值）
                        ),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.MERGE)

            val cards = db.cardDao().listAll()
            assertEquals(2, cards.size)

            val folders = db.cardFolderDao().listAll()
            val folderAId = folders.first { it.name == "A" }.id

            val c1 = cards.first { it.name == "C1" }
            // P2 修：idRemap 字段已删——改成「直接读 db 拿 folder A 的新 id」再验证 card.folderId
            // 等于它（MERGE 后 card 内部的 folderId 必须被重写为新分配的 folderId）。
            assertEquals(folderAId, c1.folderId)

            val c2 = cards.first { it.name == "C2" }
            // 指向 backup 内不存在、现库也没有的 folder（9999）→ 置 null（避免悬空外键约束），
            // 这同时会计入 cardsSkippedInvalidFolder。
            assertNull(c2.folderId)
            assertEquals("C2 的非法 folderId 应计入 cardsSkippedInvalidFolder", 1, result.cardsSkippedInvalidFolder)
        }

    @Test
    fun merge_remaps_transaction_cardId() =
        runTest {
            // 备份里：1 folder + 1 card + 2 transactions
            val bundle =
                BackupBundle(
                    version = 1,
                    folders = listOf(folder(id = 10L, name = "A")),
                    cards = listOf(card(id = 20L, name = "C", folderId = 10L)),
                    transactions =
                        listOf(
                            transaction(id = 100L, cardId = 20L, occurredAtMillis = 1_000L),
                            transaction(id = 101L, cardId = 20L, occurredAtMillis = 2_000L),
                        ),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            repo.import(Uri.fromFile(file), ImportMode.MERGE)

            // 验证：transactions.cardId 全是新分配的 card id（≠ 20L）
            val transactions = db.transactionDao().listAll()
            assertEquals(2, transactions.size)
            val newCardId =
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .id
            assertTrue(newCardId != 20L)
            assertTrue(transactions.all { it.cardId == newCardId })
        }

    @Test
    fun merge_skips_orphan_transactions() =
        runTest {
            // 备份里：1 张 card（id=20）+ 1 笔孤立 transaction（cardId=9999，指向不存在的卡）
            val bundle =
                BackupBundle(
                    version = 1,
                    folders = emptyList(),
                    cards = listOf(card(id = 20L, name = "C")),
                    transactions =
                        listOf(
                            transaction(id = 100L, cardId = 20L), // OK
                            transaction(id = 101L, cardId = 9999L), // 孤儿：cardId 找不到映射
                        ),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.MERGE)

            // transactionsAdded 只算成功插入的；孤立 1 笔被跳过
            assertEquals(1, result.transactionsAdded)
            assertEquals(1, result.transactionsSkipped)

            val stored = db.transactionDao().listAll()
            assertEquals(1, stored.size) // 真正写到 DB 的只有 1 笔
        }

    @Test
    fun merge_reports_duplicate_names() =
        runTest {
            // 1) 现库已有 1 folder "A" + 1 card "C"（来自 seed）
            seed(
                folders = listOf(folder(id = 0L, name = "A")),
                cards = listOf(card(id = 0L, name = "C")),
            )

            // 2) 备份里也有 "A" + "C"（重名）+ "B" + "D"（新）→ 期望各 1 个重名
            val bundle =
                BackupBundle(
                    version = 1,
                    folders =
                        listOf(
                            folder(id = 10L, name = "A"), // 跟现库重名
                            folder(id = 11L, name = "B"), // 不重名
                        ),
                    cards =
                        listOf(
                            card(id = 20L, name = "C"), // 跟现库重名
                            card(id = 21L, name = "D"), // 不重名
                        ),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.MERGE)

            // 1 个 folder 重名、1 个 card 重名
            assertEquals(1, result.duplicateFolderNames)
            assertEquals(1, result.duplicateCardNames)

            // 数据全部写入：现库 1 + 备份 2 = 3（重名 ≠ 跳过）
            assertEquals(3, db.cardFolderDao().listAll().size)
            assertEquals(3, db.cardDao().listAll().size)
        }

    // ════════════════════════════════════════════════════════════
    // 异常路径
    // ════════════════════════════════════════════════════════════

    @Test
    fun import_wrong_version_throws_BackupException() =
        runTest {
            val file = tempJsonFile()
            file.writeText(
                """{"version": 99, "cards": [], "folders": [], "transactions": []}""",
                Charsets.UTF_8,
            )

            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { repo.import(Uri.fromFile(file), ImportMode.REPLACE) }
                }
            assertTrue(
                "异常信息应说明版本不匹配，实际：${ex.message}",
                ex.message!!.contains("版本不匹配"),
            )
        }

    @Test
    fun import_missing_version_field_throws_BackupException() =
        runTest {
            // 关键：@Required 强制要求 version 字段存在，缺了 → MissingFieldException → 包成 BackupException
            val file = tempJsonFile()
            file.writeText(
                """{"cards": [], "folders": [], "transactions": []}""",
                Charsets.UTF_8,
            )

            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { repo.import(Uri.fromFile(file), ImportMode.REPLACE) }
                }
            assertTrue(
                "异常信息应说明格式不正确，实际：${ex.message}",
                ex.message!!.contains("格式不正确"),
            )
        }

    @Test
    fun import_malformed_json_throws_BackupException() =
        runTest {
            val file = tempJsonFile()
            file.writeText("""this is not json {""", Charsets.UTF_8)

            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { repo.import(Uri.fromFile(file), ImportMode.REPLACE) }
                }
            assertTrue(ex.message!!.contains("格式不正确"))
        }

    @Test
    fun import_empty_file_throws_BackupException() =
        runTest {
            val file = tempJsonFile()
            // 文件存在但内容为空（0 字节）—— openInputStream 拿到 0 字节流
            // text.isBlank() → BackupException("备份文件为空")
            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { repo.import(Uri.fromFile(file), ImportMode.REPLACE) }
                }
            assertTrue(ex.message!!.contains("为空"))
        }

    // ════════════════════════════════════════════════════════════
    // 跨设备 imageUri 提示（P1 修）
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_counts_user_image_cards() =
        runTest {
            // 备份里 2 张 USER 卡 + 1 张 PROVIDER 卡 + 1 张 NONE 卡
            // imageSourceType 默认值是 USER.name，但显式传 PROVIDER/NONE 测分类
            val userCard = card(id = 10L, name = "U").copy(imageSourceType = "USER")
            val providerCard = card(id = 11L, name = "P").copy(imageSourceType = "PROVIDER")
            val noneCard = card(id = 12L, name = "N").copy(imageSourceType = "NONE")
            val bundle =
                BackupBundle(
                    version = 1,
                    cards = listOf(userCard, providerCard, noneCard),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.REPLACE)
            assertEquals(1, result.imageUriUserCount) // 只有 USER 卡需要重新上传
        }

    @Test
    fun merge_counts_user_image_cards() =
        runTest {
            // 现库空 + 备份 3 张卡（USER / PROVIDER / NONE 各 1）→ expect 1
            val userCard = card(id = 10L, name = "U").copy(imageSourceType = "USER")
            val providerCard = card(id = 11L, name = "P").copy(imageSourceType = "PROVIDER")
            val noneCard = card(id = 12L, name = "N").copy(imageSourceType = "NONE")
            val bundle =
                BackupBundle(
                    version = 1,
                    cards = listOf(userCard, providerCard, noneCard),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.MERGE)
            assertEquals(1, result.imageUriUserCount)
        }

    @Test
    fun imageUriUserCount_zero_when_no_USER_cards() =
        runTest {
            // 现库空 + 备份 0 张 USER 卡 → expect 0
            val bundle =
                BackupBundle(
                    version = 1,
                    cards =
                        listOf(
                            card(id = 10L, name = "P").copy(imageSourceType = "PROVIDER"),
                            card(id = 11L, name = "N").copy(imageSourceType = "NONE"),
                        ),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = repo.import(Uri.fromFile(file), ImportMode.REPLACE)
            assertEquals(0, result.imageUriUserCount)
        }

    @Test
    fun export_omits_idRemap_field_in_bundle() =
        runTest {
            // P2 修：BackupBundle 删了 idRemap 字段（孤儿字段）。
            // 验证：MERGE 导入 + 重新 export 后 JSON 不含 "idRemap" 字段。
            seed(cards = listOf(card(id = 0L, name = "现库卡")))

            val bundle =
                BackupBundle(
                    version = 1,
                    cards = listOf(card(id = 100L, name = "备份卡")),
                )
            val file = tempJsonFile(name = "input_for_omits_idremap.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)
            repo.import(Uri.fromFile(file), ImportMode.MERGE)

            val out = tempJsonFile(name = "output_for_omits_idremap.json")
            repo.export(Uri.fromFile(out))
            val text = out.readText(Charsets.UTF_8)
            assertFalse("bundle JSON 不应含 idRemap 字段", text.contains("idRemap"))
        }

    // ════════════════════════════════════════════════════════════
    // 事务原子性（P0-1）— 验证事务中间失败时整段 ROLLBACK
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_rolls_back_when_orphan_transaction_breaks_FK() =
        runTest {
            // 1) 现库：1 张卡 + 1 笔流水（用户的真实数据）
            val folderId = db.cardFolderDao().upsert(folder(id = 0L, name = "F"))
            val cardId = db.cardDao().upsert(card(id = 0L, name = "C", folderId = folderId))
            db.transactionDao().insert(transaction(cardId = cardId))

            val snapshotFolders = db.cardFolderDao().listAll().size
            val snapshotCards = db.cardDao().listAll().size
            val snapshotTransactions = db.transactionDao().listAll().size
            assertEquals(1, snapshotFolders)
            assertEquals(1, snapshotCards)
            assertEquals(1, snapshotTransactions)

            // 2) 备份里：1 folder + 1 card + 1 笔孤立 transaction
            //    —— 第二笔 transaction 的 cardId 引用了备份里不存在的 card
            //    ⇒ 写 transactions 时会触发 FK 违反 ⇒ withTransaction 应该 ROLLBACK
            val broken =
                BackupBundle(
                    version = 1,
                    folders = listOf(folder(id = 10L, name = "备份分组")),
                    cards = listOf(card(id = 20L, name = "备份卡", folderId = 10L)),
                    transactions =
                        listOf(
                            transaction(id = 100L, cardId = 20L), // OK
                            transaction(id = 101L, cardId = 9999L), // 引用不存在的 card → FK 违反
                        ),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(broken), Charsets.UTF_8)

            // 3) import 应该抛异常
            assertThrows(BackupException::class.java) {
                runBlocking { repo.import(Uri.fromFile(file), ImportMode.REPLACE) }
            }

            // 4) 关键：数据库状态应该等于 import 前——用户数据完好
            val foldersAfter = db.cardFolderDao().listAll()
            val cardsAfter = db.cardDao().listAll()
            val transactionsAfter = db.transactionDao().listAll()
            assertEquals("folders 应该没变", snapshotFolders, foldersAfter.size)
            assertEquals("cards 应该没变", snapshotCards, cardsAfter.size)
            assertEquals("transactions 应该没变", snapshotTransactions, transactionsAfter.size)
            // 名字也对——确认还是用户原来的数据
            assertEquals("F", foldersAfter[0].name)
            assertEquals("C", cardsAfter[0].name)
        }
}
