package com.flog4.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.flog4.app.ui.detail.FlightLogDetailScreen
import com.flog4.app.ui.home.HomeScreen
import com.flog4.app.ui.recording.RecordingScreen
import com.flog4.app.ui.scanner.BoardingPassScreen

@Composable
fun FlogNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onStartWithBoardingPass = {
                    navController.navigate("scanner")
                },
                onStartDirectRecording = {
                    navController.navigate("recording/0")
                },
                onOpenFlightDetail = { flightId ->
                    navController.navigate("detail/$flightId")
                },
                onResumeActiveRecording = { flightId ->
                    navController.navigate("recording/$flightId")
                }
            )
        }

        composable("scanner") {
            BoardingPassScreen(
                onBack = { navController.popBackStack() },
                onStartLiveRecording = { flightId ->
                    navController.navigate("recording/$flightId") {
                        popUpTo("home")
                    }
                },
                onOpenGeneratedDetail = { flightId ->
                    navController.navigate("detail/$flightId") {
                        popUpTo("home")
                    }
                }
            )
        }

        composable(
            route = "recording/{flightId}",
            arguments = listOf(navArgument("flightId") { type = NavType.LongType })
        ) { backStackEntry ->
            val flightId = backStackEntry.arguments?.getLong("flightId") ?: 0L
            RecordingScreen(
                flightId = flightId,
                onBack = { navController.popBackStack() },
                onRecordingCompleted = { completedFlightId ->
                    navController.navigate("detail/$completedFlightId") {
                        popUpTo("home")
                    }
                }
            )
        }

        composable(
            route = "detail/{flightId}",
            arguments = listOf(navArgument("flightId") { type = NavType.LongType })
        ) { backStackEntry ->
            val flightId = backStackEntry.arguments?.getLong("flightId") ?: 0L
            FlightLogDetailScreen(
                flightId = flightId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
