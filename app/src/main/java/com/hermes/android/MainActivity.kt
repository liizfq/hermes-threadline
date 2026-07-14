package com.hermes.android

import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hermes.android.presentation.chat.ChatScreen
import com.hermes.android.presentation.login.LoginScreen
import com.hermes.android.presentation.sessionlist.NewSessionScreen
import com.hermes.android.presentation.sessionlist.SessionListScreen
import com.hermes.android.presentation.settings.SettingsScreen
import com.hermes.android.presentation.splash.SplashViewModel
import com.hermes.android.ui.settings.ChatFontScaleState
import com.hermes.android.ui.settings.ChatFontSize
import com.hermes.android.ui.settings.LocaleManager
import com.hermes.android.ui.settings.LocalChatFontScale
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    /** Pending thread ID to navigate to after login. Survives re-login and notification re-taps. */
    private val pendingThreadNav: MutableState<String?> = mutableStateOf(null)
    /** Generation counter incremented per pending navigation request. */
    private val pendingGeneration: MutableState<Int> = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingThreadNav.value = extractThreadFromIntent(intent)
        if (pendingThreadNav.value != null) {
            pendingGeneration.value = 1
        }
        setContent {
            val locale = LocaleManager.current.value
            LaunchedEffect(locale) {
                val l = java.util.Locale.forLanguageTag(locale)
                val config = Configuration(resources.configuration)
                config.setLocale(l)
                resources.updateConfiguration(config, resources.displayMetrics)
            }
            LaunchedEffect(Unit) {
                ChatFontScaleState.state.floatValue = ChatFontSize.get(this@MainActivity)
            }
            CompositionLocalProvider(LocalChatFontScale provides ChatFontScaleState.state.floatValue) {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        HermesNavHost(
                            pendingThreadNav = pendingThreadNav,
                            pendingGeneration = pendingGeneration
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val threadId = extractThreadFromIntent(intent)
        if (threadId != null) {
            pendingThreadNav.value = threadId
            pendingGeneration.value++
        }
    }

    private fun extractThreadFromIntent(intent: android.content.Intent?): String? {
        val raw = intent?.getStringExtra(EXTRA_NAVIGATE_TO_THREAD)
        return if (raw.isNullOrBlank()) null else raw
    }

    companion object {
        /** Intent extra holding the target thread root ID after a notification tap. */
        const val EXTRA_NAVIGATE_TO_THREAD = "com.hermes.android.NAVIGATE_TO_THREAD"
    }
}

@Composable
fun HermesNavHost(
    pendingThreadNav: MutableState<String?>,
    pendingGeneration: MutableState<Int>
) {
    val navController = rememberNavController()
    val splashViewModel: SplashViewModel = hiltViewModel()
    val splashState by splashViewModel.splashState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    var lastHandledGen by remember { mutableIntStateOf(0) }

    // Splash → Sessions or Login.
    LaunchedEffect(splashState) {
        when (splashState) {
            is SplashViewModel.SplashState.LoggedIn -> {
                if (navController.currentDestination?.route == "splash") {
                    navController.navigate("sessions") {
                        popUpTo("splash") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            is SplashViewModel.SplashState.NotLoggedIn -> {
                if (navController.currentDestination?.route == "splash") {
                    navController.navigate("login") {
                        popUpTo("splash") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            else -> {}
        }
    }

    // Consume pending thread navigation reliably once sessions or chat is the current
    // destination and has not been consumed for the current generation.
    LaunchedEffect(currentRoute, pendingGeneration.value) {
        val target = pendingThreadNav.value
        if ((currentRoute == "sessions" || currentRoute?.startsWith("chat/") == true) &&
            pendingGeneration.value != 0 &&
            lastHandledGen != pendingGeneration.value &&
            target != null
        ) {
            lastHandledGen = pendingGeneration.value
            navController.navigate("chat/${Uri.encode(target)}") {
                popUpTo("sessions") { inclusive = false }
                launchSingleTop = true
            }
            // Clear the pending value so we don't re-trigger on returning to sessions.
            pendingThreadNav.value = null
        }
    }

    NavHost(navController = navController, startDestination = "splash") {
        composable("splash") {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("sessions") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }
        composable("sessions") {
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

            AdaptiveSessionsLayout(
                isLandscape = isLandscape,
                onSessionClick = { threadRootId ->
                    navController.navigate("chat/$threadRootId") {
                        popUpTo("sessions") { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onNewSession = {
                    navController.navigate("new_session")
                },
                onSettings = {
                    navController.navigate("settings")
                }
            )
        }
        composable(
            "chat/{threadRootId}",
            arguments = listOf(navArgument("threadRootId") { type = NavType.StringType })
        ) { backStackEntry ->
            val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            val encoded = backStackEntry.arguments?.getString("threadRootId")
            // Nav decodes the arg automatically via route matching; also ensure decoding for safety.
            val threadRootId = encoded?.let { Uri.decode(it) } ?: encoded

            if (isLandscape) {
                TwoPaneLayout(
                    listContent = {
                        SessionListScreen(
                            onSessionClick = { id ->
                                navController.navigate("chat/$id") {
                                    popUpTo("sessions") { inclusive = false }
                                    launchSingleTop = true
                                }
                            },
                            onNewSession = {
                                navController.navigate("new_session")
                            },
                            onSettings = {
                                navController.navigate("settings")
                            }
                        )
                    },
                    chatContent = {
                        ChatScreen(
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                )
            } else {
                ChatScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
        composable("new_session") {
            val parentEntry = remember(it) {
                navController.getBackStackEntry("sessions")
            }
            NewSessionScreen(
                onBack = { navController.popBackStack() },
                onSessionCreated = { navController.popBackStack() },
                viewModel = hiltViewModel(parentEntry)
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate("login") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}

@Composable
private fun AdaptiveSessionsLayout(
    isLandscape: Boolean,
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onSettings: () -> Unit
) {
    if (isLandscape) {
        TwoPaneLayout(
            listContent = {
                SessionListScreen(
                    onSessionClick = onSessionClick,
                    onNewSession = onNewSession,
                    onSettings = onSettings
                )
            },
            chatContent = null
        )
    } else {
        SessionListScreen(
            onSessionClick = onSessionClick,
            onNewSession = onNewSession,
            onSettings = onSettings
        )
    }
}
