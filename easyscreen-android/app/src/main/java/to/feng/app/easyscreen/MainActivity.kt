package to.feng.app.easyscreen

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import to.feng.app.easyscreen.ui.screens.GuestScreen
import to.feng.app.easyscreen.ui.screens.HostScreen
import to.feng.app.easyscreen.ui.screens.MainScreen
import to.feng.app.easyscreen.ui.screens.SettingsScreen
import to.feng.app.easyscreen.ui.theme.EasyScreenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 强制 decor 不为系统栏让位 —— 内容延伸到挖孔/手势条之下
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        setContent {
            EasyScreenTheme {
                // 不在 Surface 上加 windowInsetsPadding —— Guest 观看页需要绘制到挖孔/手势条之下
                // 由各个 screen 自己按需添加 safeDrawing padding
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EasyScreenApp()
                }
            }
        }
    }
}

@Composable
fun EasyScreenApp() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main"
    ) {
        composable("main") {
            MainScreen(
                onNavigateToHost = { serverUrl ->
                    navController.navigate("host/${Uri.encode(serverUrl)}")
                },
                onNavigateToGuest = { serverUrl ->
                    navController.navigate("guest/${Uri.encode(serverUrl)}")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "host/{serverUrl}",
            arguments = listOf(navArgument("serverUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val serverUrl = Uri.decode(
                backStackEntry.arguments?.getString("serverUrl") ?: ""
            )
            HostScreen(
                onBack = { navController.popBackStack() },
                serverUrl = serverUrl
            )
        }

        composable(
            route = "guest/{serverUrl}",
            arguments = listOf(navArgument("serverUrl") { type = NavType.StringType })
        ) { backStackEntry ->
            val serverUrl = Uri.decode(
                backStackEntry.arguments?.getString("serverUrl") ?: ""
            )
            GuestScreen(
                onBack = { navController.popBackStack() },
                serverUrl = serverUrl
            )
        }
    }
}
