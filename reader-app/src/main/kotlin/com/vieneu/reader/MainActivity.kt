package com.vieneu.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vieneu.reader.ui.AppSettingsScreen
import com.vieneu.reader.ui.BookDetailScreen
import com.vieneu.reader.ui.BookVoiceScreen
import com.vieneu.reader.ui.LibraryScreen
import com.vieneu.reader.ui.ListenScreen
import com.vieneu.reader.ui.Routes
import com.vieneu.reader.ui.SpeechSettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
                        composable(Routes.LIBRARY) {
                            LibraryScreen(
                                onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                                onOpenSettings = { navController.navigate(Routes.APP_SETTINGS) },
                            )
                        }
                        composable(Routes.APP_SETTINGS) {
                            AppSettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            Routes.BOOK_DETAIL,
                            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                        ) { entry ->
                            val bookId = entry.arguments!!.getLong("bookId")
                            BookDetailScreen(
                                bookId = bookId,
                                onBack = { navController.popBackStack() },
                                onOpenChapter = { chapterId -> navController.navigate(Routes.listen(bookId, chapterId)) },
                                onOpenBookVoice = { navController.navigate(Routes.bookVoice(bookId)) },
                            )
                        }
                        composable(
                            Routes.LISTEN,
                            arguments = listOf(
                                navArgument("bookId") { type = NavType.LongType },
                                navArgument("chapterId") { type = NavType.LongType },
                            ),
                        ) { entry ->
                            val bookId = entry.arguments!!.getLong("bookId")
                            val chapterId = entry.arguments!!.getLong("chapterId")
                            ListenScreen(
                                bookId = bookId,
                                chapterId = chapterId,
                                onBack = { navController.popBackStack() },
                                onOpenSpeechSettings = { navController.navigate(Routes.SPEECH_SETTINGS) },
                            )
                        }
                        composable(Routes.SPEECH_SETTINGS) {
                            SpeechSettingsScreen(onBack = { navController.popBackStack() })
                        }
                        composable(
                            Routes.BOOK_VOICE,
                            arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
                        ) { entry ->
                            val bookId = entry.arguments!!.getLong("bookId")
                            BookVoiceScreen(bookId = bookId, onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}
