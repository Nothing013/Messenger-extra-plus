package app.messengerhook.xposed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.messengerhook.xposed.ui.MessengerHookTheme
import app.messengerhook.xposed.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MessengerHookTheme {
                SettingsScreen()
            }
        }
    }
}
