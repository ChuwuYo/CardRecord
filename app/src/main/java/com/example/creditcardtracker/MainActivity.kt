package com.example.creditcardtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.creditcardtracker.ui.CreditCardApp
import com.example.creditcardtracker.ui.theme.CreditCardTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CreditCardTrackerTheme {
                CreditCardApp()
            }
        }
    }
}
