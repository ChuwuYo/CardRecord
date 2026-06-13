package com.example.creditcardtracker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体策略：使用系统默认无衬线字体但加粗对比度，
 * 让标题更"锋利"。
 */
private val Display = FontFamily.Default
private val Body = FontFamily.Default

val CreditCardTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.Black,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                letterSpacing = (-1).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.5).sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
                lineHeight = 32.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = Display,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.15.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.5.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = Body,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.5.sp,
            ),
    )
