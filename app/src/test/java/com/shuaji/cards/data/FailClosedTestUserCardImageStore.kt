package com.shuaji.cards.data

import java.io.InputStream

/** 不涉及图片的单测依赖；图片入口 fail closed，避免测试默认值掩盖装配错误。 */
internal object FailClosedTestUserCardImageStore : UserCardImageStore {
    override suspend fun stageFromUri(uri: String): StagedUserImage = throw UserImageImportException()

    override suspend fun stageFromBackup(
        expectedAssetId: ImageAssetId,
        input: InputStream,
        maxBytes: Long,
    ): StagedUserImage = throw UserImageImportException()

    override suspend fun validateBackupImage(
        expectedAssetId: ImageAssetId,
        input: InputStream,
        maxBytes: Long,
    ) = throw UserImageImportException()

    override fun releaseLeases(images: Set<StagedUserImage>) = Unit

    override suspend fun migrateLegacyImages() = Unit

    override suspend fun collectGarbage() = Unit

    override fun resolve(assetId: ImageAssetId): String = ""

    override fun openAsset(assetId: ImageAssetId): InputStream = throw UserImageMissingException()

    override fun assetSize(assetId: ImageAssetId): Long = throw UserImageMissingException()
}
