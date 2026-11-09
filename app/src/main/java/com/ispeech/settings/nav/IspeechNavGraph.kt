package com.ispeech.settings.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ispeech.settings.AboutScreen
import com.ispeech.settings.CsBabyLoginScreen
import com.ispeech.settings.DictionaryScreen
import com.ispeech.settings.HomeScreen
import com.ispeech.settings.KeyboardScreen
import com.ispeech.settings.QuickPhrasesScreen
import com.ispeech.settings.SyncScreen
import com.ispeech.settings.ThemeScreen
import com.ispeech.settings.StatsScreen
import com.ispeech.settings.asr.AsrSettingsScreen
import com.ispeech.settings.ai.AiSettingsScreen

/** 设置模块路由表。 */
object IspeechRoutes {
    const val HOME = "home"
    const val DICTIONARY = "dictionary"
    const val KEYBOARD = "keyboard"
    const val QUICK = "quick"
    const val LOGIN = "login"
    const val SYNC = "sync"
    const val THEME = "theme"
    const val ABOUT = "about"
    const val STATS = "stats"
    const val ASR = "asr"
    const val AI = "ai"
}

/**
 * 设置界面导航图：startDestination 为 home。
 *
 * home → dictionary / keyboard / quick / theme / about 直接进入；
 * 「csBaby 账号与同步」卡片已登录进 sync、未登录先进 login。
 */
@Composable
fun IspeechNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = IspeechRoutes.HOME,
    ) {
        composable(IspeechRoutes.HOME) {
            HomeScreen(
                onOpenDictionary = { navController.navigate(IspeechRoutes.DICTIONARY) },
                onOpenKeyboard = { navController.navigate(IspeechRoutes.KEYBOARD) },
                onOpenQuickPhrases = { navController.navigate(IspeechRoutes.QUICK) },
                onOpenStats = { navController.navigate(IspeechRoutes.STATS) },
                onOpenAccount = { loggedIn ->
                    // 已登录直接看登录态与同步；未登录先去登录 / 注册
                    navController.navigate(if (loggedIn) IspeechRoutes.SYNC else IspeechRoutes.LOGIN)
                },
                onOpenTheme = { navController.navigate(IspeechRoutes.THEME) },
                onOpenAbout = { navController.navigate(IspeechRoutes.ABOUT) },
                onOpenAsr = { navController.navigate(IspeechRoutes.ASR) },
                onOpenAi = { navController.navigate(IspeechRoutes.AI) },
            )
        }
        composable(IspeechRoutes.DICTIONARY) {
            DictionaryScreen(onBack = { navController.popBackStack() })
        }
        composable(IspeechRoutes.KEYBOARD) {
            KeyboardScreen(onBack = { navController.popBackStack() })
        }
        composable(IspeechRoutes.QUICK) {
            QuickPhrasesScreen(
                onBack = { navController.popBackStack() },
                onOpenSync = { navController.navigate(IspeechRoutes.SYNC) },
            )
        }
        composable(IspeechRoutes.LOGIN) {
            CsBabyLoginScreen(
                onBack = { navController.popBackStack() },
                // 登录 / 注册成功后凭证已写入 DataStore，直接返回上一页
                onLoginSuccess = { navController.popBackStack() },
            )
        }
        composable(IspeechRoutes.SYNC) {
            SyncScreen(
                onBack = { navController.popBackStack() },
                onOpenLogin = { navController.navigate(IspeechRoutes.LOGIN) },
            )
        }
        composable(IspeechRoutes.THEME) {
            ThemeScreen(onBack = { navController.popBackStack() })
        }
        composable(IspeechRoutes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable(IspeechRoutes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(IspeechRoutes.ASR) {
            AsrSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(IspeechRoutes.AI) {
            AiSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
