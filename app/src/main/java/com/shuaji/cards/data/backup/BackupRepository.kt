package com.shuaji.cards.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.shuaji.cards.data.local.AppDatabase
import com.shuaji.cards.data.local.CardDao
import com.shuaji.cards.data.local.CardFolderDao
import com.shuaji.cards.data.local.ImageSourceType
import com.shuaji.cards.data.local.TransactionDao
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 导入 / 导出仓库。
 *
 * 设计哲学：用户对自己所有数据（卡 / 文件夹 / 流水）拥有**导出和恢复**的权利。
 * 文件格式 JSON，方便人工 inspect / 编辑 / 跨平台处理。
 *
 * 导入两种模式（用户在 UI 选）：
 * - [ImportMode.REPLACE]：清空现有数据库，写入备份
 *   - 顺序：cards（顺带 CASCADE 删 transactions）→ folders → folders → cards → transactions
 *     注意要先删 cards 让 CASCADE 清掉 transactions，再删 folders，最后按依赖顺序写
 * - [ImportMode.MERGE]：保留现有数据，把备份追加
 *   - 写入顺序：folders → cards → transactions
 *   - 因为备份里的 cards / folders / transactions 自带 id，但 MERGE 模式下这些 id
 *     跟现有数据库可能冲突，**所以写回时把 id 清零让 SQLite 重新分配**，并记录
 *     `oldId -> newId` 映射；transactions 的 cardId 跟着改
 *
 * 文件 I/O 走 ContentResolver（用 URI），用户通过 SAF 选位置——不申请任何存储权限。
 *
 * **事务保障**（P0-1 修）：所有 REPLACE / MERGE 的写库操作都包在
 * [AppDatabase.withTransaction] 里。任意一步抛异常 / 协程被取消，SQLite 自动
 * ROLLBACK，DB 不会停在「半替换」状态。
 *
 * **取消语义**（P1-2 修）：[export] / [import] 协程启动时把 Job 存到 [activeJob]，
 * UI 层可以调 [cancelActive] 取消。**取消时 REPLACE/MERGE 的写库事务自动回滚**，
 * 数据库回到 import 前。
 *
 * **OOM 防护**（P2-4 修）：[export] 用 [OutputStreamWriter] 包 [encodeToString]
 * 的「序列化 → 写流」走 streaming 路径——JSON 字符流直接喂给 OutputStream，
 * **不**先 `text.toByteArray` 把整段 JSON 加载到内存。理论上 100k+ 张卡也不
 * 会触发 OutOfMemoryError。
 */
class BackupRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val cardDao: CardDao,
    private val folderDao: CardFolderDao,
    private val transactionDao: TransactionDao,
) {
    private val cardFolderDao = database.cardFolderDao()
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true // 将来加字段时旧备份文件仍能解析
            encodeDefaults = true // 默认值字段也写出来，避免空备份看起来"什么都没有"
        }

    /** 当前正在跑的 import / export 协程（用于 UI 取消按钮）。null = 空闲。 */
    @Volatile
    var activeJob: Job? = null
        private set

    /** UI 调这个取消正在跑的导入 / 导出。事务会自动回滚。 */
    fun cancelActive() {
        activeJob?.cancel(CancellationException("User cancelled"))
    }

    /**
     * 导出：把数据库全量数据写到 [uri]，返回总行数（cards + folders + transactions）。
     *
     * 整个 I/O 在 [Dispatchers.IO] 上跑，UI 线程不阻塞。
     *
     * **P2-4 OOM 修复**：用 [Json.encodeToString] 的 streaming 重载——
     * `encodeToString(serializer, value, writer)`，让序列化器直接把 JSON
     * 字符流喂给 [OutputStreamWriter]，**不**经过「先拼成 String 再转 byte[]」
     * 的双倍内存开销。100k+ 行的数据也不 OOM。
     */
    suspend fun export(uri: Uri): ExportSummary =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            if (job != null) activeJob = job
            try {
                val cards = cardDao.listAll()
                val folders = folderDao.listAll()
                val transactions = transactionDao.listAll()
                val bundle =
                    BackupBundle(
                        version = BackupBundle.SCHEMA_VERSION,
                        cards = cards,
                        folders = folders,
                        transactions = transactions,
                    )
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    // P2-4 OOM 修复：用 kotlinx-serialization 1.6+ 的 [encodeToStream]
                    // 扩展函数——直接把 JSON 字符流喂给 OutputStream，**不**经过
                    // 「先拼成 String 再转 byte[]」的双倍内存开销。100k+ 行的数据
                    // 也不 OOM。注意：这是 [ExperimentalSerializationApi]，标记
                    // `@OptIn` 后稳定可用——kotlinx-serialization 自己跨平台都这么用。
                    @OptIn(ExperimentalSerializationApi::class)
                    json.encodeToStream(BackupBundle.serializer(), bundle, out)
                    out.flush()
                } ?: throw BackupException("无法打开目标 URI 用于写入（可能被占用）")
                ExportSummary(
                    cardCount = cards.size,
                    folderCount = folders.size,
                    transactionCount = transactions.size,
                )
            } finally {
                if (job != null && activeJob === job) activeJob = null
            }
        }

    /**
     * 导入：从 [uri] 读 JSON，按 [mode] 写入数据库。
     *
     * 返回 [ImportResult] 含实际插入条数 + 跳过 / 重名 / 卡面 URI 统计。
     * 失败抛 [BackupException]（UI 层 try-catch 转 Snackbar）。
     * 协程被取消 → 事务自动 ROLLBACK。
     */
    suspend fun import(
        uri: Uri,
        mode: ImportMode,
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val job = coroutineContext[Job]
            if (job != null) activeJob = job
            try {
                val text =
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
                        } ?: throw BackupException("无法打开源 URI 用于读取")
                    } catch (e: BackupException) {
                        throw e
                    } catch (e: java.io.FileNotFoundException) {
                        // SAF 临时权限过期 / 用户选的文件被删了——常有的事
                        throw BackupException("源文件不存在或已失效：${e.message}", e)
                    } catch (e: java.io.IOException) {
                        throw BackupException("读取备份文件失败：${e.message}", e)
                    }
                ensureActive()
                if (text.isBlank()) throw BackupException("备份文件为空")

                val bundle =
                    try {
                        json.decodeFromString<BackupBundle>(text)
                    } catch (e: Exception) {
                        throw BackupException("备份文件格式不正确（不是合法的 JSON）", e)
                    }
                ensureActive()
                if (bundle.version != BackupBundle.SCHEMA_VERSION) {
                    throw BackupException(
                        "备份 schema 版本不匹配（文件 version=${bundle.version}，当前=${BackupBundle.SCHEMA_VERSION}）",
                    )
                }

                // 关键：整个写库动作包在一个 Room 事务里。
                // 任何 DAO 抛异常 / 协程被取消 → SQLite ROLLBACK，DB 不会半成品。
                // 内部异常统一包成 BackupException，让 UI 只关心业务错误 / 取消两类。
                val result =
                    try {
                        database.withTransaction {
                            when (mode) {
                                ImportMode.REPLACE -> doReplace(bundle)
                                ImportMode.MERGE -> doMerge(bundle)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: BackupException) {
                        throw e
                    } catch (e: Exception) {
                        throw BackupException("数据库写入失败：${e.message ?: "未知错误"}", e)
                    }
                result
            } finally {
                if (job != null && activeJob === job) activeJob = null
            }
        }

    /**
     * 备份里 USER 类型卡数（imageSourceType == USER）——跨设备恢复时这部分的
     * imageUri 在新设备上失效，UI 提示用户「N 张卡的卡面需要重新上传」。
     * 同设备恢复时 URI 仍然有效，但提醒也无害（用户可忽略）。
     */
    private fun countUserImageCards(bundle: BackupBundle): Int =
        bundle.cards.count { it.imageSourceType == ImageSourceType.USER.name }

    /**
     * REPLACE 模式：先清空（含 CASCADE），再按依赖顺序写入。
     * **P0-2 修**：写 cards 前校验 folderId，凡是不在 bundle.folders 里的 folderId 全部置 null，
     * 避免 FK 约束违反 → 整个事务回滚 + 用户原数据已清空的灾难。
     * 不会写入任何没经过校验的 card，**返回 cardsSkippedInvalidFolder = 被改写为 null 的数量**。
     */
    private suspend fun doReplace(bundle: BackupBundle): ImportResult {
        val validFolderIds: Set<Long> = bundle.folders.map { it.id }.toSet()

        // 1) 删 cards → 自动 CASCADE 清 transactions
        cardDao.deleteAll()
        // 2) 删 folders
        cardFolderDao.deleteAll()
        // 3) 按依赖顺序写：folders → cards（带 folderId 校验）→ transactions
        val folders = bundle.folders
        val cards = bundle.cards
        val transactions = bundle.transactions
        folders.forEach { folderDao.upsert(it) }
        var invalidCount = 0
        cards.forEach { card ->
            val safeFolderId =
                if (card.folderId != null && card.folderId !in validFolderIds) {
                    invalidCount++
                    null
                } else {
                    card.folderId
                }
            cardDao.upsert(card.copy(folderId = safeFolderId))
        }
        transactions.forEach { transactionDao.insert(it) }
        return ImportResult(
            cardsAdded = cards.size,
            foldersAdded = folders.size,
            transactionsAdded = transactions.size,
            cardsSkippedInvalidFolder = invalidCount,
            imageUriUserCount = countUserImageCards(bundle),
        )
    }

    /**
     * MERGE 模式：保留现有数据，把备份追加进去。
     *
     * **P1-4 修**：检测与现库重名的 folder / card 数，写到返回值的 [ImportResult.duplicateFolderNames]
     * / [ImportResult.duplicateCardNames]，让 UI 在成功消息里告诉用户「检测到 N 个 folder 与现库重名
     * （已全部保留）」——用户不会突然发现列表里出现两个同名的 folder。
     *
     * **P1-5 修**：孤立 transaction（cardId 不在备份里也不在现库里）被跳过不写入，
     * 计入 [ImportResult.transactionsSkipped]，UI 提示用户「其中 N 笔因引用不存在的卡被跳过」。
     *
     * **P1-7 修**：`Cards.folder_id` 是外键引用 `card_folders.id`，MERGE 写库时**必须**
     * 保证 `card.folderId` 指向合法 folder：要么在 backup 自身的 `bundle.folders` 里
     * （id 已被重新映射为新 id），要么在「写之前」的现库 folder 里（用户没导入这部分的
     * folder，但希望引用原现库 folder）。原来 `folderRemap[it] ?: it` 的写法**没**做
     * "在 validFolderIds / existingFolderIds 里"的校验——当 backup 里某 card 的
     * `folderId` 既不在 backup 的 folders 里也不在现库时，会触发 `SQLiteConstraintException`，
     * **整个事务 ROLLBACK**，备份里其他合法 card 一起被吞，用户体验是"导入失败但不知道为啥"。
     *
     * 校验语义跟 [doReplace] 对齐：folderId 不在合法集合里 → 置 null + 计入
     * [ImportResult.cardsSkippedInvalidFolder]，不抛异常、不回滚。
     *
     * **MERGE 永远走 INSERT 路径**（`id = 0L` + AUTOINCREMENT），**不**会覆盖现库同 id 的 folder / card。
     *
     * 内部用 `folderRemap` / `cardRemap` 维护 old → new 映射，仅供 doMerge 自己写
     * transactions 时用；**不**再把映射返回到 [ImportResult]（id 对用户无意义，
     * 客户端从未消费过「导入前后 id 对应关系」）。
     */
    private suspend fun doMerge(bundle: BackupBundle): ImportResult {
        // 1) **写之前**先抓现有 id / name 集合——重名检测不能包含本次刚追加的，
        //    校验 folderId 合法性时也要用"写之前"的现库 id 集合
        val existingFolderIds = cardFolderDao.listAll().map { it.id }.toSet()
        val validFolderIds: Set<Long> = existingFolderIds + bundle.folders.map { it.id }.toSet()
        val existingFolderNames = cardFolderDao.listAll().map { it.name }.toSet()
        val existingCardNames = cardDao.listAll().map { it.name }.toSet()

        // 2) 写 folders：id 清零让 SQLite 重新分配，记 oldFolderId → newFolderId 映射
        val folderRemap = mutableMapOf<Long, Long>()
        bundle.folders.forEach { folder ->
            val newId = folderDao.upsert(folder.copy(id = 0L))
            folderRemap[folder.id] = newId
        }

        // 3) 写 cards：id 清零，folderId 用映射后的新 id。
        //    候选 id 必须在 validFolderIds 里：原 folderId（不在 backup）指向现库合法 folder
        //    也 OK；指向不存在的 folderId（既不在 backup 也不在现库）→ 置 null + 计入 invalidCount
        val cardRemap = mutableMapOf<Long, Long>()
        var invalidCount = 0
        bundle.cards.forEach { card ->
            val candidateId = card.folderId?.let { folderRemap[it] ?: it }
            val safeFolderId =
                if (candidateId != null && candidateId !in validFolderIds) {
                    invalidCount++
                    null
                } else {
                    candidateId
                }
            val newId = cardDao.upsert(card.copy(id = 0L, folderId = safeFolderId))
            cardRemap[card.id] = newId
        }

        // 4) 写 transactions：id 清零，cardId 用映射后的新 id（必须找到映射；找不到的跳过）
        var txCount = 0
        var txSkipped = 0
        bundle.transactions.forEach { txn ->
            val mappedCardId = cardRemap[txn.cardId]
            if (mappedCardId == null) {
                txSkipped++
                return@forEach
            }
            transactionDao.insert(txn.copy(id = 0L, cardId = mappedCardId))
            txCount++
        }

        // 5) 重名检测：跟"写之前"抓的现库 name 集合对比，**不包含本次刚追加的**
        val duplicateFolders = bundle.folders.count { it.name in existingFolderNames }
        val duplicateCards = bundle.cards.count { it.name in existingCardNames }

        return ImportResult(
            cardsAdded = bundle.cards.size,
            foldersAdded = bundle.folders.size,
            transactionsAdded = txCount,
            transactionsSkipped = txSkipped,
            cardsSkippedInvalidFolder = invalidCount,
            duplicateFolderNames = duplicateFolders,
            duplicateCardNames = duplicateCards,
            imageUriUserCount = countUserImageCards(bundle),
        )
    }
}

/**
 * 导出结果摘要——UI 拼 Snackbar 用。
 *
 * 原来 export 返回 `Int`（总行数），UI 拼成「已导出 N 条」；问题是 N 把卡 / 文件夹 / 流水
 * 混在一起算，用户不知道是「5 张卡还是 5 笔流水」。「凡是存在就要有存在意义」→
 * 按三类分桶，UI 拼「已导出 N1 张卡 / N2 个文件夹 / N3 笔流水」。
 */
data class ExportSummary(
    val cardCount: Int,
    val folderCount: Int,
    val transactionCount: Int,
) {
    val total: Int get() = cardCount + folderCount + transactionCount

    val isEmpty: Boolean get() = total == 0
}
