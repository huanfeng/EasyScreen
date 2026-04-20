package com.example.easyscreen

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.easyscreen.ui.screens.GuestScreen
import com.example.easyscreen.ui.screens.HostScreen
import com.example.easyscreen.ui.screens.MainScreen
import com.example.easyscreen.ui.theme.EasyScreenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EasyScreenTheme {
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
                }
            )
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
