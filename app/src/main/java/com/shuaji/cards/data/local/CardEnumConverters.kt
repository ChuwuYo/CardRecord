package com.shuaji.cards.data.local

import androidx.room.TypeConverter

object CardEnumConverters {
    @TypeConverter
    fun cardTypeToKey(value: CardType): String = value.key

    @TypeConverter
    fun keyToCardType(key: String): CardType = CardType.fromKey(key)

    @TypeConverter
    fun imageSourceTypeToKey(value: ImageSourceType): String = value.key

    @TypeConverter
    fun keyToImageSourceType(key: String): ImageSourceType = ImageSourceType.fromKey(key)

    @TypeConverter
    fun cardOrientationToKey(value: CardOrientation): String = value.key

    @TypeConverter
    fun keyToCardOrientation(key: String): CardOrientation = CardOrientation.fromKey(key)
}
