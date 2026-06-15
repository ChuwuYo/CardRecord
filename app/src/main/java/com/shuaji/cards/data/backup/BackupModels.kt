package com.shuaji.cards.data.backup

import com.shuaji.cards.data.local.CardEntity
import com.shuaji.cards.data.local.CardFolderEntity
import com.shuaji.cards.data.local.TransactionEntity
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

/**
 * 导出文件 schema。
 *
 * 顶层是 [BackupBundle]，包含 cards / folders / transactions 三个表的全量数据。
 * 任何「用户能填 / app 会生成」的字段都在这里有归宿——「凡是存在就要有存在意义」。
 *
 * [version] 字段是 schema 版本号：导入时校验，不匹配直接拒绝（避免老格式 / 新格式混用导致
 * 静默丢字段）。改 schema 时升 [version]，并在导入处做迁移。
 *
 * **`@Required` 强制要求 JSON 必须有 `version` 字段**——否则反序列化抛 `MissingFieldException`，
 * 走到 catch 转成「备份文件格式不正确」Snackbar。这避免了「用户手编的 / 漏字段的老 schema
 * 文件被默认值 1 静默通过」——「凡是存在就要有存在意义」原则：version 字段存在就必须被强制。
 *
 * imageUri / imageProviderKey 这两个字段虽然依赖设备（content:// URI 在另一台设备失效），
 * 但仍按现状导出——**用户在同设备 / 同备份恢复时仍可用**；跨设备恢复时这两个字段会失效，
 * 这是 Android 文件系统的天然限制，文案里说明。
 */
@Serializable
data class BackupBundle(
    @Required
    val version: Int = SCHEMA_VERSION,
    val exportedAtMillis: Long = 0L,
    val cards: List<CardEntity> = emptyList(),
    val folders: List<CardFolderEntity> = emptyList(),
    val transactions: List<TransactionEntity> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * 导入模式。导入前用户选一个：
 * - [REPLACE] 先清空数据库全部表，再写入备份
 * - [MERGE]   保留现有数据，把备份追加进去；card / folder / transaction 都会自增分配新 id
 *            （旧 id 在 [ImportResult.idRemap] 里给到调用方做映射）
 */
enum class ImportMode { REPLACE, MERGE }

/**
 * 导入结果。返回给 UI 用：
 * - [cardsAdded] / [foldersAdded] / [transactionsAdded] — 实际插入条数
 * - [transactionsSkipped] — MERGE 模式下因 cardId 找不到映射被跳过的孤立 transaction 数
 * - [duplicateFolderNames] / [duplicateCardNames] — MERGE 模式下与现库重名的 folder / card 数
 * - [idRemap] — `oldCardId -> newCardId`，MERGE 模式下用于日志 / 调试
 * - 出错时抛 [BackupException]，UI 层 try-catch 转 Snackbar
 */
data class ImportResult(
    val cardsAdded: Int,
    val foldersAdded: Int,
    val transactionsAdded: Int,
    val transactionsSkipped: Int = 0,
    val cardsSkippedInvalidFolder: Int = 0,
    val duplicateFolderNames: Int = 0,
    val duplicateCardNames: Int = 0,
    val idRemap: Map<Long, Long> = emptyMap(),
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
