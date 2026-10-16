package com.yuanbao.minioperit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.yuanbao.minioperit.ui.AppColors
import com.yuanbao.minioperit.ui.AppShapes
import com.yuanbao.minioperit.ui.AppTypography
import com.yuanbao.minioperit.ui.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MiniOperitApp() }
    }
}

@Composable
fun MiniOperitApp() {
    MaterialTheme(
        colorScheme = AppColors,
        shapes = AppShapes,
        typography = AppTypography
    ) {
        ChatScreen()
    }
}
