package com.shuaji.cards

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shuaji.cards.ui.ShuajiApp
import com.shuaji.cards.ui.theme.ShuajiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShuajiTheme {
                ShuajiApp()
            }
        }
    }
}
