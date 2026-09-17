package com.example.goon

import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.goon.ui.AgentWorkbench
import com.example.goon.ui.theme.GoonTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 随包引入的社区 skill（assets/skills/）在这里装载一次；没装载时只剩内嵌 skill，功能不受影响。
        com.example.goon.core.BuiltInSkills.install(applicationContext)
        runCatching {
            Class.forName("com.example.goon.debug.TestBridge").getMethod("start", Context::class.java).invoke(null, applicationContext)
        }
        setContent { GoonTheme { AgentWorkbench() } }
    }
}
