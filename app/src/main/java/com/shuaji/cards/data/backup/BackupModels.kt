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
 *
 * [version] 字段是 schema 版本号：导入时校验，不匹配直接拒绝（避免老格式 / 新格式混用导致
 * 静默丢字段）。改 schema 时升 [version]，并在导入处做迁移。
 *
 * `@Required` 要求 JSON 显式包含 `version`；缺失时反序列化失败，
 * 避免用默认版本解析格式不明的备份。
 *
 * imageProviderKey 是跨设备稳定的卡组织 key。只有 imageUri 依赖设备
 * （content:// URI 在另一台设备可能失效），但仍按现状导出，以保留同设备恢复的可能性；
 * UI 通过 [ImportResult.imageUriUserCount]
 * 提醒用户自定义卡面可能需要重新选择；导入过程不直接探测 URI 可访问性。
 */
@Serializable
data class BackupBundle(
    @Required
    val version: Int = SCHEMA_VERSION,
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
 */
enum class ImportMode { REPLACE, MERGE }

/**
 * 导入结果。返回给 UI 用：
 * - [cardsAdded] / [foldersAdded] / [transactionsAdded] — 实际插入条数
 * - [transactionsSkipped] — MERGE 模式下因 cardId 找不到映射被跳过的孤立 transaction 数
 * - [cardsSkippedInvalidFolder] — REPLACE 模式下被置为未分组的 card 数（folder 引用失效）
 * - [duplicateFolderNames] / [duplicateCardNames] — MERGE 模式下与现库重名的 folder / card 数
 * - [imageUriUserCount] — 备份里 `imageSourceType == USER` 的卡数；UI 据此提示用户
 *   自定义卡面可能需要重新选择，不等同于已确认 URI 失效
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
    val imageUriUserCount: Int = 0,
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
