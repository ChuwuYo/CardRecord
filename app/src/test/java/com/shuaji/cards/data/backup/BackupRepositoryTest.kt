package com.shuaji.cards.data.backup

import android.content.Context
import android.net.Uri
import android.os.OperationCanceledException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shuaji.cards.data.CardNetworkProvider
import com.shuaji.cards.data.CardRepository
import com.shuaji.cards.data.DateToken
import com.shuaji.cards.data.backup.TestData.backupBundle
import com.shuaji.cards.data.backup.TestData.card
import com.shuaji.cards.data.backup.TestData.folder
import com.shuaji.cards.data.backup.TestData.transaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [BackupRepository] 的核心场景测试。
 *
 * 测试策略：
 * - 用 **Robolectric** 跑 JVM 单元测试，模拟 Android Context / ContentResolver
 * - 用 `Room.inMemoryDatabaseBuilder` 拿一次性内存数据库，每个测试建一个
 * - 用 `file://` URI + [TemporaryFolder] 把"用户保存的 JSON 文件"落到临时文件，
 *   ContentResolver.openAssetFileDescriptor 在 Robolectric 下对 file:// 原生支持
 * - 用 `runTest`（kotlinx-coroutines-test）跑 suspend 函数
 *
 * 覆盖矩阵：
 * - REPLACE 的清空、校验和字段保留
 * - MERGE 的主键、外键重映射和字段保留
 * - 异常路径（version 不匹配 / JSON 烂 / 取消）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class BackupRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: BackupRepository
    private lateinit var cardRepository: CardRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries() // 单元测试不需要跑在 IO 调度器上
                .build()
        cardRepository =
            CardRepository(
                database = db,
                cardDao = db.cardDao(),
                transactionDao = db.transactionDao(),
                folderDao = db.cardFolderDao(),
                clock = Clock.fixed(Instant.parse("2027-07-15T00:00:00Z"), ZoneOffset.UTC),
                zoneIdProvider = { ZoneOffset.UTC },
                boundaryTicks = flowOf(Unit),
            )
        repo =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── 工具 ────────────────────────────────────────────────────

    private fun tempJsonFile(name: String = "backup.json"): File = tempFolder.newFile(name)

    /** 生产导入必须绑定用户刚确认过的同一份内容；测试也统一走这条不变量。 */
    private suspend fun importInspected(
        file: File,
        mode: ImportMode,
        repository: BackupRepository = repo,
    ): ImportResult {
        val uri = Uri.fromFile(file)
        val inspected = repository.inspect(uri)
        return repository.import(uri, mode, inspected.contentSha256)
    }

    private fun json(): Json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private fun repositoryWithLimit(maxBackupBytes: Long): BackupRepository =
        BackupRepository(
            context = context,
            database = db,
            cardDao = db.cardDao(),
            folderDao = db.cardFolderDao(),
            transactionDao = db.transactionDao(),
            normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
            maxBackupBytes = maxBackupBytes,
        )

    private fun seed(
        folders: List<CardFolderEntity> = emptyList(),
        cards: List<CardEntity> = emptyList(),
        transactions: List<TransactionEntity> = emptyList(),
    ) = runBlocking {
        folders.forEach { db.cardFolderDao().insert(it) }
        cards.forEach { db.cardDao().upsert(it) }
        transactions.forEach { db.transactionDao().insert(it) }
    }

    private suspend fun snapshotDatabase() =
        DatabaseSnapshot(
            folders = db.cardFolderDao().listAll().sortedBy(CardFolderEntity::id),
            cards = db.cardDao().listAll().sortedBy(CardEntity::id),
            transactions = db.transactionDao().listAll().sortedBy(TransactionEntity::id),
        )

    private data class DatabaseSnapshot(
        val folders: List<CardFolderEntity>,
        val cards: List<CardEntity>,
        val transactions: List<TransactionEntity>,
    )

    /** REPLACE 可重分配内部主键；用户数据与文件内引用关系必须保持。 */
    private fun assertLogicalSnapshotEquals(
        expected: DatabaseSnapshot,
        actual: DatabaseSnapshot,
    ) {
        assertEquals(
            expected.folders.map { it.copy(id = 0L) }.sortedBy { it.name },
            actual.folders.map { it.copy(id = 0L) }.sortedBy { it.name },
        )
        val expectedFolderName = expected.folders.associate { it.id to it.name }
        val actualFolderName = actual.folders.associate { it.id to it.name }
        assertEquals(
            expected.cards
                .map { it.copy(id = 0L, folderId = null) to it.folderId?.let(expectedFolderName::get) }
                .sortedBy { it.first.name },
            actual.cards
                .map { it.copy(id = 0L, folderId = null) to it.folderId?.let(actualFolderName::get) }
                .sortedBy { it.first.name },
        )
        val expectedCardName = expected.cards.associate { it.id to it.name }
        val actualCardName = actual.cards.associate { it.id to it.name }
        assertEquals(
            expected.transactions
                .map { expectedCardName.getValue(it.cardId) to it.occurredAtMillis }
                .sortedWith(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }),
            actual.transactions
                .map { actualCardName.getValue(it.cardId) to it.occurredAtMillis }
                .sortedWith(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second }),
        )
    }

    // ════════════════════════════════════════════════════════════
    // 导出
    // ════════════════════════════════════════════════════════════

    @Test
    fun export_writes_complete_bundle_to_uri() =
        runTest {
            val f1 = folder(id = 0L, name = "商旅")
            val f1Id = db.cardFolderDao().insert(f1)
            val c1 =
                card(
                    id = 0L,
                    name = "招行",
                    colorArgb = 0xFF2E7D32.toInt(),
                    imageSourceType = "PROVIDER",
                    imageProviderKey = "visa",
                    folderId = f1Id,
                )
            val c1Id = db.cardDao().upsert(c1)
            val sourceTransaction = transaction(cardId = c1Id)
            val transactionId = db.transactionDao().insert(sourceTransaction)

            val out = tempJsonFile()
            val summary = repo.export(Uri.fromFile(out))

            assertEquals(1, summary.cardCount)
            assertEquals(1, summary.folderCount)
            assertEquals(1, summary.transactionCount)
            assertEquals(3, summary.total)
            assertFalse(summary.isEmpty)

            val bundle = json().decodeFromString<BackupBundle>(out.readText(Charsets.UTF_8))
            val exportedJson = json().parseToJsonElement(out.readText(Charsets.UTF_8)).jsonObject
            assertEquals(1, bundle.folders.size)
            assertEquals(1, bundle.cards.size)
            assertEquals(1, bundle.transactions.size)
            assertEquals(PUBLISHED_TOP_LEVEL_FIELDS, exportedJson.keys)
            assertEquals(
                PUBLISHED_CARD_FIELDS,
                exportedJson
                    .getValue("cards")
                    .jsonArray
                    .single()
                    .jsonObject.keys,
            )
            assertEquals(
                PUBLISHED_FOLDER_FIELDS,
                exportedJson
                    .getValue("folders")
                    .jsonArray
                    .single()
                    .jsonObject.keys,
            )
            assertEquals(
                PUBLISHED_TRANSACTION_FIELDS,
                exportedJson
                    .getValue("transactions")
                    .jsonArray
                    .single()
                    .jsonObject.keys,
            )
            assertEquals(0xFF2E7D32.toInt(), bundle.cards.single().colorArgb)
            assertEquals("PROVIDER", bundle.cards.single().imageSourceType)
            assertEquals("visa", bundle.cards.single().imageProviderKey)
            assertEquals(f1.copy(id = f1Id), bundle.folders.single().toEntity())
            assertEquals(c1.copy(id = c1Id), bundle.cards.single().toEntity(bundle.version))
            assertEquals(sourceTransaction.copy(id = transactionId), bundle.transactions.single().toEntity())
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

    @Test
    fun export_truncatesLongerExistingFile() =
        runTest {
            val out = tempJsonFile("overwrite.json")
            out.writeText("x".repeat(16_384), Charsets.UTF_8)

            repo.export(Uri.fromFile(out))

            val decoded = json().decodeFromString<BackupBundle>(out.readText(Charsets.UTF_8))
            assertTrue(decoded.cards.isEmpty())
            assertTrue(out.readText(Charsets.UTF_8).trimEnd().endsWith("}"))
        }

    @Test
    fun export_preflightsImportLimitBeforeTruncatingTarget() =
        runTest {
            db.cardDao().upsert(card(name = "中文与 emoji 💳"))
            val reference = tempJsonFile("oversize-reference.json")
            repo.export(Uri.fromFile(reference))
            val out = tempJsonFile("preserve-on-oversize.json")
            out.writeText("原文件不能被破坏", Charsets.UTF_8)
            var outputOpened = false
            val limitedRepo =
                BackupRepository(
                    context = context,
                    database = db,
                    cardDao = db.cardDao(),
                    folderDao = db.cardFolderDao(),
                    transactionDao = db.transactionDao(),
                    normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                    maxBackupBytes = reference.length() - 1L,
                    openOutputStream = { _, _, _ ->
                        outputOpened = true
                        error("超限预检不得打开目标")
                    },
                )

            val exception =
                assertThrows(BackupException::class.java) {
                    runBlocking { limitedRepo.export(Uri.fromFile(out)) }
                }

            assertTrue(exception.message.orEmpty().contains("上限"))
            assertFalse(outputOpened)
            assertEquals("原文件不能被破坏", out.readText(Charsets.UTF_8))
        }

    @Test
    fun export_atExactImportLimit_roundTripsThroughInspect() =
        runTest {
            db.cardDao().upsert(card(name = "边界卡 💳"))
            val reference = tempJsonFile("reference-size.json")
            repo.export(Uri.fromFile(reference))
            val exactLimit = reference.length()
            val exactRepo = repositoryWithLimit(maxBackupBytes = exactLimit)
            val out = tempJsonFile("exact-limit.json")

            val summary = exactRepo.export(Uri.fromFile(out))
            val inspected = exactRepo.inspect(Uri.fromFile(out))

            assertEquals(exactLimit, out.length())
            assertEquals(summary.cardCount, inspected.cardCount)
            assertEquals(summary.folderCount, inspected.folderCount)
            assertEquals(summary.transactionCount, inspected.transactionCount)
        }

    @Test
    fun inspect_decodesValidatedBundle_andReportsTrustedCounts() =
        runTest {
            val bundle =
                backupBundle(
                    folders = listOf(folder(id = 10L, name = "旅行")),
                    cards =
                        listOf(
                            card(
                                id = 20L,
                                name = "自定义卡",
                                folderId = 10L,
                                imageUri = "content://card/image",
                                imageSourceType = ImageSourceType.USER.key,
                            ),
                        ),
                    transactions = listOf(transaction(id = 30L, cardId = 20L)),
                )
            val file = tempJsonFile("inspect.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val info = repo.inspect(Uri.fromFile(file))

            assertEquals(1, info.folderCount)
            assertEquals(1, info.cardCount)
            assertEquals(1, info.transactionCount)
            assertEquals(1, info.imageUriUserCount)
            assertEquals(64, info.contentSha256.length)
        }

    @Test
    fun import_rejectsFileChangedAfterInspection_withoutWriting() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "现库卡"))
            val file = tempJsonFile("changed-after-preview.json")
            file.writeText(
                json().encodeToString(backupBundle(cards = listOf(card(id = 10L, name = "预览卡")))),
                Charsets.UTF_8,
            )
            val preview = repo.inspect(Uri.fromFile(file))
            file.writeText(
                json().encodeToString(backupBundle(cards = listOf(card(id = 20L, name = "替换卡")))),
                Charsets.UTF_8,
            )

            val exception =
                assertThrows(BackupException::class.java) {
                    runBlocking {
                        repo.import(
                            Uri.fromFile(file),
                            ImportMode.REPLACE,
                            expectedContentSha256 = preview.contentSha256,
                        )
                    }
                }

            assertTrue(exception.message.orEmpty().contains("变化"))
            assertEquals("现库卡", db.cardDao().getById(originalId)?.name)
        }

    @Test
    fun import_acceptsUnchangedFileBoundToInspectionDigest() =
        runTest {
            val file = tempJsonFile("unchanged-after-preview.json")
            file.writeText(
                json().encodeToString(backupBundle(cards = listOf(card(id = 10L, name = "同一文件")))),
                Charsets.UTF_8,
            )
            val preview = repo.inspect(Uri.fromFile(file))

            val result =
                repo.import(
                    Uri.fromFile(file),
                    ImportMode.REPLACE,
                    expectedContentSha256 = preview.contentSha256,
                )

            assertEquals(1, result.cardsAdded)
            assertEquals(
                "同一文件",
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .name,
            )
        }

    // ════════════════════════════════════════════════════════════
    // 导入 — REPLACE 模式
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_clears_existing_data_and_writes_bundle() =
        runTest {
            // 1) 现库有数据
            val oldFolder = folder(id = 0L, name = "旧分组")
            val oldFolderId = db.cardFolderDao().insert(oldFolder)
            db.cardDao().upsert(card(id = 0L, name = "旧卡", folderId = oldFolderId))

            // 2) 备份只有新数据
            val newBundle =
                backupBundle(
                    version = 1,
                    folders = listOf(folder(id = 100L, name = "新分组")),
                    cards = listOf(card(id = 200L, name = "新卡")),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(newBundle), Charsets.UTF_8)

            // 3) REPLACE 导入
            val result = importInspected(file, ImportMode.REPLACE)

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
                backupBundle(
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

            val result = importInspected(file, ImportMode.REPLACE)

            // 1 张被改写为 null
            assertEquals(1, result.cardsSkippedInvalidFolder)
            val cards = db.cardDao().listAll()
            assertEquals(2, cards.size)
            val ok = cards.first { it.name == "好卡" }
            val bad = cards.first { it.name == "坏卡" }
            assertEquals(
                db
                    .cardFolderDao()
                    .listAll()
                    .single()
                    .id,
                ok.folderId,
            )
            assertNull(bad.folderId) // 失效引用被置 null
        }

    @Test
    fun replace_duplicateCardIds_isRejectedBeforeWriting() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "原卡"))
            val bundle =
                backupBundle(
                    cards =
                        listOf(
                            card(id = 20L, name = "重复一"),
                            card(id = 20L, name = "重复二"),
                        ),
                )
            val file = tempJsonFile("duplicate-card-id.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            assertThrows(BackupException::class.java) {
                runBlocking { importInspected(file, ImportMode.REPLACE) }
            }
            assertEquals("校验失败不能改动现库", "原卡", db.cardDao().getById(originalId)?.name)
        }

    @Test
    fun replace_nonPositiveRequiredCount_isRejectedBeforeWriting() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "原卡"))
            val file = tempJsonFile("invalid-required-count.json")
            file.writeText(
                json().encodeToString(
                    backupBundle(cards = listOf(card(id = 20L, name = "无效目标", requiredCount = 0))),
                ),
                Charsets.UTF_8,
            )

            assertThrows(BackupException::class.java) {
                runBlocking { importInspected(file, ImportMode.REPLACE) }
            }
            assertEquals("无效目标不能改动现库", "原卡", db.cardDao().getById(originalId)?.name)
        }

    @Test
    fun replace_remapsMaximumIdsAndLeavesAutoincrementUsable() =
        runTest {
            val bundle =
                backupBundle(
                    folders = listOf(folder(id = Long.MAX_VALUE, name = "极值分组")),
                    cards = listOf(card(id = Long.MAX_VALUE, name = "极值卡", folderId = Long.MAX_VALUE)),
                    transactions =
                        listOf(
                            transaction(
                                id = Long.MAX_VALUE,
                                cardId = Long.MAX_VALUE,
                            ),
                        ),
                )
            val file = tempJsonFile("maximum-ids.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            importInspected(file, ImportMode.REPLACE)

            val importedFolder = db.cardFolderDao().listAll().single()
            val importedCard = db.cardDao().listAll().single()
            val importedTransaction = db.transactionDao().listAll().single()
            assertTrue(importedFolder.id != Long.MAX_VALUE)
            assertTrue(importedCard.id != Long.MAX_VALUE)
            assertTrue(importedTransaction.id != Long.MAX_VALUE)
            assertEquals(importedFolder.id, importedCard.folderId)
            assertEquals(importedCard.id, importedTransaction.cardId)

            val nextFolderId = db.cardFolderDao().insert(folder(name = "后续分组"))
            val nextCardId = db.cardDao().upsert(card(name = "后续卡", folderId = nextFolderId))
            val nextTransactionId = db.transactionDao().insert(transaction(cardId = nextCardId))
            assertTrue(nextFolderId > importedFolder.id)
            assertTrue(nextCardId > importedCard.id)
            assertTrue(nextTransactionId > importedTransaction.id)
        }

    @Test
    fun replace_cascade_deletes_transactions() =
        runTest {
            // 1) 现库：1 张卡 + 5 笔流水
            val folderId = db.cardFolderDao().insert(folder(id = 0L, name = "X"))
            val cardId = db.cardDao().upsert(card(id = 0L, name = "C", folderId = folderId))
            repeat(5) { i -> db.transactionDao().insert(transaction(cardId = cardId, occurredAtMillis = TestData.FIXED_TIME_MILLIS + i)) }
            assertEquals(5, db.transactionDao().listAll().size)

            // 2) REPLACE 一个空 bundle → 期望所有表被清空
            val empty = backupBundle(version = 1)
            val file = tempJsonFile()
            file.writeText(json().encodeToString(empty), Charsets.UTF_8)
            importInspected(file, ImportMode.REPLACE)

            // 3) 验证
            assertTrue(db.cardDao().listAll().isEmpty())
            assertTrue(db.cardFolderDao().listAll().isEmpty())
            assertTrue(db.transactionDao().listAll().isEmpty())
        }

    @Test
    fun replace_roundtrip_preserves_data() =
        runTest {
            // 1) 准备数据并 export
            val folderId = db.cardFolderDao().insert(folder(id = 0L, name = "F"))
            val originalCard =
                card(
                    id = 0L,
                    name = "C",
                    colorArgb = 0xFF6A1B9A.toInt(),
                    imageSourceType = "PROVIDER",
                    imageProviderKey = "mastercard",
                    folderId = folderId,
                )
            val cardId = db.cardDao().upsert(originalCard)
            db.transactionDao().insert(transaction(cardId = cardId))

            val out = tempJsonFile()
            repo.export(Uri.fromFile(out))

            // 2) 现库塞点新数据
            db.cardDao().upsert(card(id = 0L, name = "杂质"))

            // 3) REPLACE 导入
            importInspected(out, ImportMode.REPLACE)

            // 4) 验证：跟导出前的快照一致
            val folders = db.cardFolderDao().listAll()
            val cards = db.cardDao().listAll()
            val transactions = db.transactionDao().listAll()
            assertEquals(1, folders.size)
            assertEquals("F", folders[0].name)
            assertEquals(1, cards.size)
            assertEquals("C", cards[0].name)
            assertEquals(originalCard.colorArgb, cards[0].colorArgb)
            assertEquals(originalCard.imageSourceType, cards[0].imageSourceType)
            assertEquals(originalCard.imageProviderKey, cards[0].imageProviderKey)
            assertEquals(1, transactions.size)
        }

    @Test
    fun schemaTwo_replaceRoundtripPreservesEveryEntityField() =
        runTest {
            val folderId =
                db.cardFolderDao().insert(
                    folder(
                        name = "完整分组",
                        colorArgb = 0xFF112233.toInt(),
                        sortOrder = 7,
                        createdAtMillis = 111L,
                    ),
                )
            val cardId =
                db.cardDao().upsert(
                    card(
                        name = "完整卡片",
                        bank = "完整银行",
                        cardNumberMasked = "**** 9876",
                        cardType = CardType.CREDIT.key,
                        statementDay = 12,
                        repaymentDay = 27,
                        validUntilMillis = 222L,
                        nextDueDateMillis = DateToken.fromLocalDate(LocalDate.of(2029, 6, 1)),
                        requiredCount = 9,
                        colorArgb = 0xFF445566.toInt(),
                        note = "完整备注",
                        imageUri = "content://card/image",
                        imageSourceType = "USER",
                        imageProviderKey = "provider",
                        cardOrientation = "PORTRAIT",
                        folderId = folderId,
                        createdAtMillis = 333L,
                    ),
                )
            val plainNetworkCard =
                card(
                    id = 0L,
                    name = "纯色银联",
                    imageSourceType = ImageSourceType.NONE.key,
                    imageProviderKey = CardNetworkProvider.UNIONPAY.key,
                )
            val userNetworkCard =
                card(
                    id = 0L,
                    name = "图片万事达",
                    imageSourceType = ImageSourceType.USER.key,
                    imageProviderKey = CardNetworkProvider.MASTERCARD.key,
                )
            db.cardDao().upsert(plainNetworkCard)
            db.cardDao().upsert(userNetworkCard)
            db.transactionDao().insert(transaction(cardId = cardId, occurredAtMillis = 444L))
            val before = snapshotDatabase()
            val file = tempJsonFile()
            repo.export(Uri.fromFile(file))

            seed(cards = listOf(card(name = "杂质")))
            importInspected(file, ImportMode.REPLACE)

            assertEquals(BackupBundle.SCHEMA_VERSION, json().decodeFromString<BackupBundle>(file.readText()).version)
            assertEquals(2, BackupBundle.SCHEMA_VERSION)
            assertEquals(8, db.openHelper.readableDatabase.version)
            assertLogicalSnapshotEquals(before, snapshotDatabase())

            importInspected(file, ImportMode.MERGE)

            val mergedCards = db.cardDao().listAll()
            assertEquals(
                2,
                mergedCards.count {
                    it.name == "完整卡片" &&
                        it.cardType == CardType.CREDIT.key &&
                        it.statementDay == 12 &&
                        it.repaymentDay == 27
                },
            )
            assertEquals(
                2,
                mergedCards.count {
                    it.name == plainNetworkCard.name &&
                        it.imageSourceType == ImageSourceType.NONE.key &&
                        it.imageProviderKey == CardNetworkProvider.UNIONPAY.key
                },
            )
            assertEquals(
                2,
                mergedCards.count {
                    it.name == userNetworkCard.name &&
                        it.imageSourceType == ImageSourceType.USER.key &&
                        it.imageProviderKey == CardNetworkProvider.MASTERCARD.key
                },
            )
            assertEquals(
                2,
                mergedCards
                    .filter { it.name == plainNetworkCard.name }
                    .map(CardEntity::id)
                    .distinct()
                    .size,
            )
            assertEquals(
                2,
                mergedCards
                    .filter { it.name == userNetworkCard.name }
                    .map(CardEntity::id)
                    .distinct()
                    .size,
            )
        }

    @Test
    fun schemaTwo_nonCreditCardsDiscardCreditOnlyDays() =
        runTest {
            val validDebit =
                backupBundle(
                    cards = listOf(card(id = 20L, name = "借记卡", cardType = CardType.DEBIT.key)),
                )
            val inconsistentDebit =
                validDebit.copy(
                    cards = validDebit.cards.map { it.copy(statementDay = 8, repaymentDay = 21) },
                )
            val file = tempJsonFile("debit-with-credit-days.json")
            file.writeText(json().encodeToString(inconsistentDebit), Charsets.UTF_8)

            importInspected(file, ImportMode.REPLACE)

            val stored = db.cardDao().listAll().single()
            assertEquals(CardType.DEBIT.key, stored.cardType)
            assertNull(stored.statementDay)
            assertNull(stored.repaymentDay)
        }

    @Test
    fun schemaTwo_rejectsMissingOrUnknownTypeAndInvalidDaysBeforeWriting() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "原卡"))
            val valid =
                backupBundle(
                    cards =
                        listOf(
                            card(
                                id = 20L,
                                name = "信用卡",
                                cardType = CardType.CREDIT.key,
                                statementDay = 8,
                                repaymentDay = 21,
                            ),
                        ),
                )
            val invalidCards =
                listOf(
                    valid.cards.single().copy(cardType = null),
                    valid.cards.single().copy(cardType = "FUTURE_TYPE"),
                    valid.cards.single().copy(statementDay = 0),
                    valid.cards.single().copy(statementDay = 32),
                    valid.cards.single().copy(repaymentDay = -1),
                    valid.cards.single().copy(repaymentDay = 32),
                )

            invalidCards.forEachIndexed { index, invalidCard ->
                val file = tempJsonFile("invalid-card-details-$index.json")
                file.writeText(
                    json().encodeToString(valid.copy(cards = listOf(invalidCard))),
                    Charsets.UTF_8,
                )

                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
                assertEquals("无效备份不能改动现库", "原卡", db.cardDao().getById(originalId)?.name)
            }
        }

    @Test
    fun importOverdueBackup_advancesDueAndPreservesEveryTransaction() =
        runTest {
            val originalCard =
                card(
                    id = 20L,
                    name = "多年未打开",
                    bank = "测试银行",
                    cardNumberMasked = "**** 4321",
                    nextDueDateMillis = DateToken.fromLocalDate(LocalDate.of(2024, 6, 1)),
                    requiredCount = 8,
                    colorArgb = 0xFF123456.toInt(),
                    note = "其余字段必须保留",
                    createdAtMillis = 123_456L,
                )
            val originalTransactions =
                listOf(
                    transaction(id = 100L, cardId = 20L, occurredAtMillis = 1_700_000_000_000L),
                    transaction(id = 101L, cardId = 20L, occurredAtMillis = 1_800_000_000_000L),
                )
            val file = tempJsonFile()
            file.writeText(
                json().encodeToString(
                    backupBundle(
                        version = BackupBundle.SCHEMA_VERSION,
                        cards = listOf(originalCard),
                        transactions = originalTransactions,
                    ),
                ),
                Charsets.UTF_8,
            )

            val result = importInspected(file, ImportMode.REPLACE)

            assertEquals(2, result.transactionsAdded)
            val importedTransactions = db.transactionDao().listAll()
            assertEquals(
                originalTransactions.map(TransactionEntity::occurredAtMillis),
                importedTransactions.map(TransactionEntity::occurredAtMillis),
            )
            val imported = db.cardDao().listAll().single()
            assertEquals(LocalDate.of(2028, 6, 1), DateToken.toLocalDate(checkNotNull(imported.nextDueDateMillis)))
            assertEquals(
                originalCard.copy(id = imported.id, nextDueDateMillis = imported.nextDueDateMillis),
                imported,
            )
            assertTrue(importedTransactions.all { it.cardId == imported.id })
        }

    @Test
    fun importNormalizationFailure_rollsBackWholeImport() =
        runTest {
            seed(
                folders = listOf(folder(id = 1L, name = "原分组")),
                cards = listOf(card(id = 2L, name = "原卡", folderId = 1L)),
                transactions = listOf(transaction(id = 3L, cardId = 2L)),
            )
            val before = snapshotDatabase()
            val file = tempJsonFile()
            file.writeText(
                json().encodeToString(
                    backupBundle(
                        version = BackupBundle.SCHEMA_VERSION,
                        cards = listOf(card(id = 20L, name = "备份卡")),
                    ),
                ),
                Charsets.UTF_8,
            )
            val failingRepository =
                BackupRepository(
                    context = context,
                    database = db,
                    cardDao = db.cardDao(),
                    folderDao = db.cardFolderDao(),
                    transactionDao = db.transactionDao(),
                    normalizeInTransaction = { error("归一化失败") },
                )

            assertThrows(BackupException::class.java) {
                runBlocking { importInspected(file, ImportMode.REPLACE, failingRepository) }
            }

            assertEquals(before, snapshotDatabase())
        }

    @Test
    fun reconcileImagePermissionFailure_doesNotReverseCommittedImportResult() =
        runTest {
            val file = tempJsonFile("reconcile-failure.json")
            file.writeText(
                json().encodeToString(backupBundle(cards = listOf(card(id = 20L, name = "已导入")))),
                Charsets.UTF_8,
            )
            val repository =
                BackupRepository(
                    context = context,
                    database = db,
                    cardDao = db.cardDao(),
                    folderDao = db.cardFolderDao(),
                    transactionDao = db.transactionDao(),
                    normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                    reconcileImagePermissions = { error("模拟权限清理失败") },
                )

            val result = importInspected(file, ImportMode.REPLACE, repository)

            assertEquals(1, result.cardsAdded)
            assertEquals(
                "已导入",
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .name,
            )
        }

    // ════════════════════════════════════════════════════════════
    // 导入 — MERGE 模式
    // ════════════════════════════════════════════════════════════

    @Test
    fun merge_appends_folders_with_new_ids() =
        runTest {
            // 1) 现库空 → merge 2 个 folder
            val bundle =
                backupBundle(
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

            val result = importInspected(file, ImportMode.MERGE)

            assertEquals(2, result.foldersAdded)

            val stored = db.cardFolderDao().listAll()
            assertEquals(2, stored.size)
            assertEquals(setOf("A", "B"), stored.map { it.name }.toSet())
        }

    @Test
    fun merge_appends_cards_and_remaps_folderId() =
        runTest {
            val bundle =
                backupBundle(
                    version = 1,
                    folders = listOf(folder(id = 10L, name = "A")),
                    cards =
                        listOf(
                            card(
                                id = 20L,
                                name = "C1",
                                colorArgb = 0xFF123456.toInt(),
                                imageSourceType = "PROVIDER",
                                imageProviderKey = "visa",
                                cardOrientation = "PORTRAIT",
                                folderId = 10L,
                            ),
                            card(id = 21L, name = "C2", folderId = 9999L), // 指向不存在（应保留原值）
                        ),
                    transactions = emptyList(),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.MERGE)

            val cards = db.cardDao().listAll()
            assertEquals(2, cards.size)

            val folders = db.cardFolderDao().listAll()
            val folderAId = folders.first { it.name == "A" }.id

            val c1 = cards.first { it.name == "C1" }
            assertEquals(folderAId, c1.folderId)
            assertEquals(0xFF123456.toInt(), c1.colorArgb)
            assertEquals("PROVIDER", c1.imageSourceType)
            assertEquals("visa", c1.imageProviderKey)
            assertEquals("PORTRAIT", c1.cardOrientation)

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
                backupBundle(
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

            importInspected(file, ImportMode.MERGE)

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
    fun mergeOverdueCard_remapsIdsAndOnlyAdvancesDueWhilePreservingAllTransactions() =
        runTest {
            val originalFolder =
                folder(
                    id = 10L,
                    name = "历史分组",
                    colorArgb = 0xFF102030.toInt(),
                    sortOrder = 4,
                    createdAtMillis = 111L,
                )
            val originalCard =
                card(
                    id = 20L,
                    name = "历史卡",
                    bank = "历史银行",
                    cardNumberMasked = "**** 2468",
                    validUntilMillis = 222L,
                    nextDueDateMillis = DateToken.fromLocalDate(LocalDate.of(2024, 6, 1)),
                    requiredCount = 6,
                    colorArgb = 0xFF405060.toInt(),
                    note = "历史备注",
                    imageUri = "content://history/image",
                    imageSourceType = "USER",
                    imageProviderKey = "history-provider",
                    cardOrientation = "PORTRAIT",
                    folderId = 10L,
                    createdAtMillis = 333L,
                )
            val originalTransactions =
                listOf(
                    transaction(id = 100L, cardId = 20L, occurredAtMillis = 444L),
                    transaction(id = 101L, cardId = 20L, occurredAtMillis = 555L),
                )
            val file = tempJsonFile()
            file.writeText(
                json().encodeToString(
                    backupBundle(
                        version = BackupBundle.SCHEMA_VERSION,
                        folders = listOf(originalFolder),
                        cards = listOf(originalCard),
                        transactions = originalTransactions,
                    ),
                ),
            )

            val result = importInspected(file, ImportMode.MERGE)

            assertEquals(2, result.transactionsAdded)
            val importedFolder = db.cardFolderDao().listAll().single()
            assertEquals(originalFolder.copy(id = importedFolder.id), importedFolder)
            val importedCard = db.cardDao().listAll().single()
            assertEquals(LocalDate.of(2028, 6, 1), DateToken.toLocalDate(checkNotNull(importedCard.nextDueDateMillis)))
            assertEquals(
                originalCard.copy(
                    id = importedCard.id,
                    folderId = importedFolder.id,
                    nextDueDateMillis = importedCard.nextDueDateMillis,
                ),
                importedCard,
            )
            val importedTransactions = db.transactionDao().listAll()
            assertEquals(
                originalTransactions.map(TransactionEntity::occurredAtMillis).sorted(),
                importedTransactions.map(TransactionEntity::occurredAtMillis).sorted(),
            )
            assertTrue(importedTransactions.all { it.cardId == importedCard.id })
        }

    @Test
    fun merge_skips_orphan_transactions() =
        runTest {
            // 备份里：1 张 card（id=20）+ 1 笔孤立 transaction（cardId=9999，指向不存在的卡）
            val bundle =
                backupBundle(
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

            val result = importInspected(file, ImportMode.MERGE)

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
                backupBundle(
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

            val result = importInspected(file, ImportMode.MERGE)

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
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
            assertTrue(
                "异常信息应说明版本不匹配，实际：${ex.message}",
                ex.message.orEmpty().contains("版本不匹配"),
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
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
            assertTrue(
                "异常信息应说明格式不正确，实际：${ex.message}",
                ex.message.orEmpty().contains("格式不正确"),
            )
        }

    @Test
    fun import_missingAnyTopLevelTable_isRejectedWithoutChangingDatabase() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "原卡"))
            val incompleteFiles =
                listOf(
                    """{"version":1,"folders":[],"transactions":[]}""",
                    """{"version":1,"cards":[],"transactions":[]}""",
                    """{"version":1,"cards":[],"folders":[]}""",
                )

            incompleteFiles.forEachIndexed { index, content ->
                val file = tempJsonFile("missing-table-$index.json")
                file.writeText(content, Charsets.UTF_8)

                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
                assertEquals("原卡", db.cardDao().getById(originalId)?.name)
            }
        }

    @Test
    fun import_malformed_json_throws_BackupException() =
        runTest {
            val file = tempJsonFile()
            file.writeText("""this is not json {""", Charsets.UTF_8)

            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
            assertTrue(ex.message.orEmpty().contains("格式不正确"))
        }

    @Test
    fun import_empty_file_throws_BackupException() =
        runTest {
            val file = tempJsonFile()
            // 文件存在但内容为空（0 字节）—— openInputStream 拿到 0 字节流
            // text.isBlank() → BackupException("备份文件为空")
            val ex =
                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }
            assertTrue(ex.message.orEmpty().contains("为空"))
        }

    @Test
    fun import_fileOverConfiguredLimit_isRejectedBeforeWriting() =
        runTest {
            val originalId = db.cardDao().upsert(card(name = "原卡"))
            val file = tempJsonFile("oversized.json")
            file.writeText(
                """{"version":1,"cards":[],"folders":[],"transactions":[]}""" + " ".repeat(256),
                Charsets.UTF_8,
            )
            val limitedRepo = repositoryWithLimit(maxBackupBytes = 96L)

            val exception =
                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE, limitedRepo) }
                }

            assertTrue(exception.message.orEmpty().contains("上限"))
            assertEquals("超限文件不能改动现库", "原卡", db.cardDao().getById(originalId)?.name)
        }

    @Test
    fun activeOperation_rejectsConcurrentWork_andCancellationClosesStreamAsCancellation() {
        val blockingInput = CloseBlockingInputStream()
        val blockingRepository =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                openInputStream = { _, _ -> blockingInput },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val active = scope.async { blockingRepository.inspect(Uri.EMPTY) }
            assertTrue("首个操作应已进入阻塞读取", blockingInput.readStarted.await(5, TimeUnit.SECONDS))

            assertThrows(BackupException::class.java) {
                runBlocking { blockingRepository.inspect(Uri.EMPTY) }
            }
            assertFalse("busy 不能抢占正在执行的操作", active.isCompleted)

            assertEquals(BackupCancelResult.CANCELLED, blockingRepository.cancelActive())

            assertTrue("取消必须主动关闭阻塞流", blockingInput.closed.await(5, TimeUnit.SECONDS))
            assertThrows(CancellationException::class.java) {
                runBlocking { active.await() }
            }
        } finally {
            scope.cancel()
            blockingInput.close()
        }
    }

    @Test
    fun cancelActive_cancelsProviderOpenBeforeStreamExists() {
        val openStarted = CountDownLatch(1)
        val providerCancelled = CountDownLatch(1)
        val blockingRepository =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                openInputStream = { _, signal ->
                    signal.setOnCancelListener { providerCancelled.countDown() }
                    openStarted.countDown()
                    providerCancelled.await()
                    throw OperationCanceledException("provider open cancelled")
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val active = scope.async { blockingRepository.inspect(Uri.EMPTY) }
            assertTrue("Provider open 应已开始", openStarted.await(5, TimeUnit.SECONDS))

            assertEquals(BackupCancelResult.CANCELLED, blockingRepository.cancelActive())

            assertTrue("流尚未创建时也必须取消 Provider", providerCancelled.await(5, TimeUnit.SECONDS))
            assertThrows(CancellationException::class.java) {
                runBlocking { active.await() }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelActive_cancelsExportProviderOpenBeforeStreamExists() {
        val openStarted = CountDownLatch(1)
        val providerCancelled = CountDownLatch(1)
        val blockingRepository =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                openOutputStream = { _, _, signal ->
                    signal.setOnCancelListener { providerCancelled.countDown() }
                    openStarted.countDown()
                    providerCancelled.await()
                    throw OperationCanceledException("provider open cancelled")
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val active = scope.async { blockingRepository.export(Uri.EMPTY) }
            assertTrue("导出 Provider open 应已开始", openStarted.await(5, TimeUnit.SECONDS))

            assertEquals(BackupCancelResult.CANCELLED, blockingRepository.cancelActive())

            assertTrue("导出流尚未创建时也必须取消 Provider", providerCancelled.await(5, TimeUnit.SECONDS))
            assertThrows(CancellationException::class.java) {
                runBlocking { active.await() }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun ordinaryJobCancellation_closesBlockingExportStream_andStaysCancellation() {
        repeat(CANCELLATION_RACE_ITERATIONS) { iteration ->
            val blockingOutput = CloseBlockingOutputStream()
            val blockingRepository =
                BackupRepository(
                    context = context,
                    database = db,
                    cardDao = db.cardDao(),
                    folderDao = db.cardFolderDao(),
                    transactionDao = db.transactionDao(),
                    normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                    openOutputStream = { _, _, _ -> blockingOutput },
                )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            try {
                val active = scope.async { blockingRepository.export(Uri.EMPTY) }
                assertTrue("导出应已进入阻塞写入", blockingOutput.writeStarted.await(5, TimeUnit.SECONDS))

                active.cancel(CancellationException("ordinary caller cancellation"))

                assertTrue("普通 Job.cancel 也必须关闭导出流", blockingOutput.closed.await(5, TimeUnit.SECONDS))
                assertThrows("第 ${iteration + 1} 轮必须保持取消语义", CancellationException::class.java) {
                    runBlocking { active.await() }
                }
            } finally {
                scope.cancel()
                blockingOutput.close()
            }
        }
    }

    @Test
    fun ordinaryJobCancellation_closesBlockingStream_andStaysCancellation() {
        val blockingInput = CloseBlockingInputStream()
        val blockingRepository =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                openInputStream = { _, _ -> blockingInput },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val active = scope.async { blockingRepository.inspect(Uri.EMPTY) }
            assertTrue("操作应已进入阻塞读取", blockingInput.readStarted.await(5, TimeUnit.SECONDS))

            active.cancel(CancellationException("ordinary caller cancellation"))

            assertTrue("普通 Job.cancel 也必须关闭流", blockingInput.closed.await(5, TimeUnit.SECONDS))
            assertThrows(CancellationException::class.java) {
                runBlocking { active.await() }
            }
        } finally {
            scope.cancel()
            blockingInput.close()
        }
    }

    @Test
    fun importCommitBoundary_rejectsCancellation_andReturnsCommittedResult() {
        val existingId = runBlocking { db.cardDao().upsert(card(name = "提交前")) }
        val file = tempJsonFile("commit-boundary.json")
        file.writeText(
            json().encodeToString(backupBundle(cards = listOf(card(id = 10L, name = "已提交")))),
            Charsets.UTF_8,
        )
        val reconcileStarted = CountDownLatch(1)
        val finishReconcile = CountDownLatch(1)
        val committingRepository =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = cardRepository::normalizeOverdueCyclesInTransaction,
                reconcileImagePermissions = {
                    reconcileStarted.countDown()
                    finishReconcile.await()
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val active = scope.async { importInspected(file, ImportMode.REPLACE, committingRepository) }
            assertTrue("应已完成数据库提交并进入权限收尾", reconcileStarted.await(5, TimeUnit.SECONDS))
            assertNull(runBlocking { db.cardDao().getById(existingId) })
            assertEquals(
                "已提交",
                runBlocking {
                    db
                        .cardDao()
                        .listAll()
                        .single()
                        .name
                },
            )

            assertEquals(BackupCancelResult.COMMIT_IN_PROGRESS, committingRepository.cancelActive())
            finishReconcile.countDown()

            assertEquals(1, runBlocking { active.await() }.cardsAdded)
            assertEquals(BackupCancelResult.NO_ACTIVE_OPERATION, committingRepository.cancelActive())
        } finally {
            finishReconcile.countDown()
            scope.cancel()
        }
    }

    @Test
    fun importCancellationDuringNormalization_rollsBackWholeTransaction() {
        val originalFolderId = runBlocking { db.cardFolderDao().insert(folder(name = "原分组")) }
        val originalCardId =
            runBlocking {
                db.cardDao().upsert(card(name = "原卡", folderId = originalFolderId))
            }
        val file = tempJsonFile("cancel-during-normalize.json")
        file.writeText(
            json().encodeToString(backupBundle(cards = listOf(card(id = 10L, name = "不应提交")))),
            Charsets.UTF_8,
        )
        val normalizeStarted = CompletableDeferred<Unit>()
        val keepNormalizing = CompletableDeferred<Unit>()
        val cancellableRepository =
            BackupRepository(
                context = context,
                database = db,
                cardDao = db.cardDao(),
                folderDao = db.cardFolderDao(),
                transactionDao = db.transactionDao(),
                normalizeInTransaction = {
                    normalizeStarted.complete(Unit)
                    keepNormalizing.await()
                    0
                },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            val active = scope.async { importInspected(file, ImportMode.REPLACE, cancellableRepository) }
            runBlocking { normalizeStarted.await() }

            assertEquals(BackupCancelResult.CANCELLED, cancellableRepository.cancelActive())
            assertThrows(CancellationException::class.java) {
                runBlocking { active.await() }
            }

            assertEquals("原卡", runBlocking { db.cardDao().getById(originalCardId)?.name })
            assertEquals(
                "原分组",
                runBlocking {
                    db
                        .cardFolderDao()
                        .listAll()
                        .single { it.id == originalFolderId }
                        .name
                },
            )
            assertTrue(runBlocking { db.cardDao().listAll() }.none { it.name == "不应提交" })
        } finally {
            keepNormalizing.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun import_missingRequiredEntityTimestamp_isRejected() =
        runTest {
            val file = tempJsonFile("missing-created-at.json")
            file.writeText(
                """
                {
                  "version": 1,
                  "cards": [{
                    "id": 20,
                    "name": "缺时间",
                    "bank": "测试银行",
                    "cardNumberMasked": "**** 1234",
                    "requiredCount": 5,
                    "colorArgb": -1
                  }],
                  "folders": [],
                  "transactions": []
                }
                """.trimIndent(),
                Charsets.UTF_8,
            )

            val exception =
                assertThrows(BackupException::class.java) {
                    runBlocking { importInspected(file, ImportMode.REPLACE) }
                }

            assertTrue(exception.message.orEmpty().contains("格式不正确"))
        }

    @Test
    fun schemaOneDto_rejectsEveryMissingPublishedEntityField() {
        val requiredFields =
            mapOf(
                "cards" to SCHEMA_ONE_CARD_FIELDS,
                "folders" to PUBLISHED_FOLDER_FIELDS,
                "transactions" to PUBLISHED_TRANSACTION_FIELDS,
            )

        requiredFields.forEach { (table, fields) ->
            fields.forEach { field ->
                val incompleteJson = frozenFixtureWithoutField(table, field)
                assertThrows("schema 1 必须拒绝缺失字段 $table.$field", SerializationException::class.java) {
                    json().decodeFromString<BackupBundle>(incompleteJson)
                }
            }
        }
    }

    @Test
    fun frozenSchemaOneFixture_importsInReplaceAndMergeModes() =
        runTest {
            val file = tempJsonFile("schema-one-frozen.json")
            file.writeText(FROZEN_SCHEMA_ONE_JSON, Charsets.UTF_8)

            val expectedFolder =
                folder(
                    name = "历史分组",
                    colorArgb = -65536,
                    sortOrder = 2,
                    createdAtMillis = 111L,
                )
            val expectedCard =
                card(
                    name = "历史卡",
                    bank = "历史银行",
                    cardNumberMasked = "**** 1234",
                    requiredCount = 5,
                    colorArgb = -16776961,
                    note = "历史备注",
                    imageSourceType = "NONE",
                    imageProviderKey = "visa",
                    cardOrientation = "LANDSCAPE",
                    createdAtMillis = 333L,
                )

            importInspected(file, ImportMode.REPLACE)

            val replacedFolder = db.cardFolderDao().listAll().single()
            val replacedCard = db.cardDao().listAll().single()
            val replacedTransaction = db.transactionDao().listAll().single()
            assertEquals(expectedFolder.copy(id = replacedFolder.id), replacedFolder)
            assertEquals(
                expectedCard.copy(id = replacedCard.id, folderId = replacedFolder.id),
                replacedCard,
            )
            assertEquals(CardType.UNSPECIFIED.key, replacedCard.cardType)
            assertNull(replacedCard.statementDay)
            assertNull(replacedCard.repaymentDay)
            assertEquals(replacedFolder.id, replacedCard.folderId)
            assertEquals(
                transaction(id = replacedTransaction.id, cardId = replacedCard.id, occurredAtMillis = 444L),
                replacedTransaction,
            )

            importInspected(file, ImportMode.MERGE)

            val mergedFolders = db.cardFolderDao().listAll()
            val mergedCards = db.cardDao().listAll()
            val mergedTransactions = db.transactionDao().listAll()
            assertEquals(2, mergedFolders.size)
            assertTrue(mergedFolders.all { it.copy(id = 0L) == expectedFolder })
            assertEquals(2, mergedCards.size)
            assertTrue(mergedCards.all { it.copy(id = 0L, folderId = null) == expectedCard })
            assertEquals(2, mergedTransactions.size)
            assertTrue(mergedTransactions.all { it.occurredAtMillis == 444L })
            assertTrue(mergedTransactions.all { transaction -> mergedCards.any { it.id == transaction.cardId } })
        }

    // ════════════════════════════════════════════════════════════
    // 跨设备 imageUri 提示
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_counts_user_image_cards() =
        runTest {
            // 备份里 2 张 USER 卡 + 1 张 PROVIDER 卡 + 1 张 NONE 卡
            // 显式使用稳定 key 覆盖 USER / PROVIDER / NONE 三类。
            val userCard = card(id = 10L, name = "U", imageUri = "content://image/u").copy(imageSourceType = "USER")
            val providerCard = card(id = 11L, name = "P").copy(imageSourceType = "PROVIDER")
            val noneCard = card(id = 12L, name = "N").copy(imageSourceType = "NONE")
            val bundle =
                backupBundle(
                    version = 1,
                    cards = listOf(userCard, providerCard, noneCard),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.REPLACE)
            assertEquals(1, result.imageUriUserCount) // 只有 USER 卡需要重新上传
        }

    @Test
    fun merge_counts_user_image_cards() =
        runTest {
            // 现库空 + 备份 3 张卡（USER / PROVIDER / NONE 各 1）→ expect 1
            val userCard = card(id = 10L, name = "U", imageUri = "content://image/u").copy(imageSourceType = "USER")
            val providerCard = card(id = 11L, name = "P").copy(imageSourceType = "PROVIDER")
            val noneCard = card(id = 12L, name = "N").copy(imageSourceType = "NONE")
            val bundle =
                backupBundle(
                    version = 1,
                    cards = listOf(userCard, providerCard, noneCard),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.MERGE)
            assertEquals(1, result.imageUriUserCount)
        }

    @Test
    fun merge_missingBackupFolder_doesNotAttachCardToSameNumericExistingFolder() =
        runTest {
            val existingFolderId = db.cardFolderDao().insert(folder(id = 41L, name = "现库无关文件夹"))
            val bundle =
                backupBundle(
                    cards = listOf(card(id = 10L, name = "备份卡", folderId = existingFolderId)),
                )
            val file = tempJsonFile("missing-backup-folder.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.MERGE)

            assertEquals(1, result.cardsSkippedInvalidFolder)
            assertNull(
                db
                    .cardDao()
                    .listAll()
                    .single()
                    .folderId,
            )
        }

    @Test
    fun imageUriUserCount_zero_when_no_USER_cards() =
        runTest {
            // 现库空 + 备份 0 张 USER 卡 → expect 0
            val bundle =
                backupBundle(
                    version = 1,
                    cards =
                        listOf(
                            card(id = 10L, name = "P").copy(imageSourceType = "PROVIDER"),
                            card(id = 11L, name = "N").copy(imageSourceType = "NONE"),
                        ),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.REPLACE)
            assertEquals(0, result.imageUriUserCount)
        }

    @Test
    fun imageUriUserCount_doesNotWarnForUserSourceWithoutImageUri() =
        runTest {
            val bundle =
                backupBundle(
                    cards = listOf(card(id = 10L, name = "旧默认卡", imageSourceType = ImageSourceType.USER.key)),
                )
            val file = tempJsonFile("user-without-uri.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)

            val result = importInspected(file, ImportMode.REPLACE)

            assertEquals(0, result.imageUriUserCount)
        }

    @Test
    fun export_omits_idRemap_field_in_bundle() =
        runTest {
            // MERGE 后重新导出，兼容格式中不应重新出现已删除的字段。
            seed(cards = listOf(card(id = 0L, name = "现库卡")))

            val bundle =
                backupBundle(
                    version = 1,
                    cards = listOf(card(id = 100L, name = "备份卡")),
                )
            val file = tempJsonFile(name = "input_for_omits_idremap.json")
            file.writeText(json().encodeToString(bundle), Charsets.UTF_8)
            importInspected(file, ImportMode.MERGE)

            val out = tempJsonFile(name = "output_for_omits_idremap.json")
            repo.export(Uri.fromFile(out))
            val text = out.readText(Charsets.UTF_8)
            assertFalse("bundle JSON 不应含 idRemap 字段", text.contains("idRemap"))
        }

    // ════════════════════════════════════════════════════════════
    // 事务原子性：中间失败时整段回滚
    // ════════════════════════════════════════════════════════════

    @Test
    fun replace_rolls_back_when_orphan_transaction_fails_reference_validation() =
        runTest {
            // 1) 现库：1 张卡 + 1 笔流水（用户的真实数据）
            val folderId = db.cardFolderDao().insert(folder(id = 0L, name = "F"))
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
            //    ⇒ REPLACE 找不到文件内 cardId 映射时主动拒绝 ⇒ withTransaction 应该 ROLLBACK
            val broken =
                backupBundle(
                    version = 1,
                    folders = listOf(folder(id = 10L, name = "备份分组")),
                    cards = listOf(card(id = 20L, name = "备份卡", folderId = 10L)),
                    transactions =
                        listOf(
                            transaction(id = 100L, cardId = 20L), // OK
                            transaction(id = 101L, cardId = 9999L), // 引用不存在的 card → 文件内映射失败
                        ),
                )
            val file = tempJsonFile()
            file.writeText(json().encodeToString(broken), Charsets.UTF_8)

            // 3) import 应该抛异常
            assertThrows(BackupException::class.java) {
                runBlocking { importInspected(file, ImportMode.REPLACE) }
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

    private fun frozenFixtureWithoutField(
        table: String,
        field: String,
    ): String {
        val root = json().parseToJsonElement(FROZEN_SCHEMA_ONE_JSON).jsonObject
        val record =
            root
                .getValue(table)
                .jsonArray
                .single()
                .jsonObject
        val incompleteRecord = JsonObject(record - field)
        return JsonObject(root + (table to JsonArray(listOf(incompleteRecord)))).toString()
    }

    private class CloseBlockingInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            closed.await()
            throw IOException("stream closed")
        }

        override fun close() {
            closed.countDown()
        }
    }

    private class CloseBlockingOutputStream : OutputStream() {
        val writeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun write(byte: Int) {
            writeStarted.countDown()
            closed.await()
            throw IOException("stream closed")
        }

        override fun close() {
            closed.countDown()
        }
    }

    private companion object {
        const val CANCELLATION_RACE_ITERATIONS = 25
        val PUBLISHED_TOP_LEVEL_FIELDS = setOf("version", "cards", "folders", "transactions")
        val PUBLISHED_CARD_FIELDS =
            setOf(
                "id",
                "name",
                "bank",
                "cardNumberMasked",
                "validUntilMillis",
                "nextDueDateMillis",
                "requiredCount",
                "colorArgb",
                "note",
                "imageUri",
                "imageSourceType",
                "imageProviderKey",
                "cardOrientation",
                "folderId",
                "createdAtMillis",
                "cardType",
                "statementDay",
                "repaymentDay",
            )
        val SCHEMA_ONE_CARD_FIELDS =
            PUBLISHED_CARD_FIELDS - setOf("cardType", "statementDay", "repaymentDay")
        val PUBLISHED_FOLDER_FIELDS = setOf("id", "name", "colorArgb", "sortOrder", "createdAtMillis")
        val PUBLISHED_TRANSACTION_FIELDS = setOf("id", "cardId", "occurredAtMillis")

        val FROZEN_SCHEMA_ONE_JSON =
            """
            {
              "version": 1,
              "cards": [{
                "id": 20,
                "name": "历史卡",
                "bank": "历史银行",
                "cardNumberMasked": "**** 1234",
                "validUntilMillis": null,
                "nextDueDateMillis": null,
                "requiredCount": 5,
                "colorArgb": -16776961,
                "note": "历史备注",
                "imageUri": null,
                "imageSourceType": "NONE",
                "imageProviderKey": "visa",
                "cardOrientation": "LANDSCAPE",
                "folderId": 10,
                "createdAtMillis": 333
              }],
              "folders": [{
                "id": 10,
                "name": "历史分组",
                "colorArgb": -65536,
                "sortOrder": 2,
                "createdAtMillis": 111
              }],
              "transactions": [{
                "id": 30,
                "cardId": 20,
                "occurredAtMillis": 444
              }]
            }
            """.trimIndent()
    }
}
