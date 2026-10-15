package com.shuaji.cards.ui.screen

import com.shuaji.cards.data.local.CardOrientation
import com.shuaji.cards.data.local.CardType
import com.shuaji.cards.data.local.ImageSourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CardEditDraft(
    val name: String,
    val bank: String,
    val cardNumberMasked: String,
    val cardTypeKey: String,
    val statementDay: String,
    val repaymentDay: String,
    val requiredCount: String,
    val validUntilMillis: Long?,
    val nextDueDateMillis: Long?,
    val colorArgb: Int,
    val note: String,
    val imageSourceTypeKey: String,
    val imageProviderKey: String?,
    val userImage: DraftUserImage,
    val cardOrientationKey: String,
    val folderId: Long?,
    val editingId: Long?,
    val persistedImagePreview: String?,
) {
    fun toUiState(): CardEditUiState =
        CardEditUiState(
            name = name,
            bank = bank,
            cardNumberMasked = cardNumberMasked,
            cardType = CardType.fromKey(cardTypeKey),
            statementDay = statementDay,
            repaymentDay = repaymentDay,
            requiredCount = requiredCount,
            validUntilMillis = validUntilMillis,
            nextDueDateMillis = nextDueDateMillis,
            colorArgb = colorArgb,
            note = note,
            imageSourceType = ImageSourceType.fromKey(imageSourceTypeKey),
            imageProviderKey = imageProviderKey,
            userImage = userImage.toUserImageDraft(),
            cardOrientation = CardOrientation.fromKey(cardOrientationKey),
            folderId = folderId,
            editingId = editingId,
            loadState = if (editingId == null) CardEditLoadState.NEW else CardEditLoadState.READY,
        )

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decodeOrNull(encoded: String): CardEditDraft? = runCatching { Json.decodeFromString(serializer(), encoded) }.getOrNull()

        fun fromUiState(
            state: CardEditUiState,
            persistedImagePreview: String?,
        ): CardEditDraft =
            CardEditDraft(
                name = state.name,
                bank = state.bank,
                cardNumberMasked = state.cardNumberMasked,
                cardTypeKey = state.cardType.key,
                statementDay = state.statementDay,
                repaymentDay = state.repaymentDay,
                requiredCount = state.requiredCount,
                validUntilMillis = state.validUntilMillis,
                nextDueDateMillis = state.nextDueDateMillis,
                colorArgb = state.colorArgb,
                note = state.note,
                imageSourceTypeKey =
                    ((state.userImage as? UserImageDraft.Selected)?.sourceTypeBeforeSelection ?: state.imageSourceType).key,
                imageProviderKey = state.imageProviderKey,
                userImage =
                    when (state.userImage) {
                        is UserImageDraft.Unchanged -> DraftUserImage.Unchanged(state.userImage.previewModel)
                        UserImageDraft.Cleared -> DraftUserImage.Cleared
                        is UserImageDraft.Selected ->
                            if (state.editingId == null) DraftUserImage.Cleared else DraftUserImage.Unchanged(persistedImagePreview)
                    },
                cardOrientationKey = state.cardOrientation.key,
                folderId = state.folderId,
                editingId = state.editingId,
                persistedImagePreview = persistedImagePreview,
            )
    }
}

@Serializable
internal sealed interface DraftUserImage {
    fun toUserImageDraft(): UserImageDraft

    @Serializable
    data class Unchanged(
        val previewModel: String?,
    ) : DraftUserImage {
        override fun toUserImageDraft() = UserImageDraft.Unchanged(previewModel)
    }

    @Serializable
    data object Cleared : DraftUserImage {
        override fun toUserImageDraft() = UserImageDraft.Cleared
    }
}
