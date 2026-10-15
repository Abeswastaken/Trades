package com.abeswastaken.trades

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.abeswastaken.trades.ui.AppViewModel
import com.abeswastaken.trades.ui.TradesApp
import com.abeswastaken.trades.ui.theme.TradesTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TradesTheme { TradesApp(vm) } }
    }
}
