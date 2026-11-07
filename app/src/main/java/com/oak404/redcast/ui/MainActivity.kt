package com.oak404.redcast.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.oak404.redcast.auth.LoginActivity
import kotlinx.coroutines.launch

// AppCompat because the Cast device picker needs a FragmentActivity and an AppCompat theme.
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var appLock: AppLock
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        appLock = AppLock(this)

        lifecycleScope.launch {
            viewModel.cast.sessionStarted.collect {
                // Android keeps Cast controls out of the shade without this.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        setContent {
            val settings by viewModel.library.settings.collectAsStateWithLifecycle()
            val colors = colorsFor(settings.theme, isSystemInDarkTheme())
            val view = LocalView.current
            val lightBars = colors.surface.luminance() > 0.5f && viewModel.backStack.lastOrNull() !is Route.Player
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            MaterialTheme(colorScheme = colors) {
                if (appLock.locked) {
                    Surface {
                        Message("Redcast is locked") {
                            Button(onClick = appLock::prompt) { Text("Unlock") }
                        }
                    }
                } else if (!viewModel.ageConfirmed) {
                    Surface {
                        Message("Redcast shows adult content. You must be 18 or older to use it.") {
                            Button(onClick = viewModel::confirmAge) { Text("I'm 18 or older") }
                            OutlinedButton(onClick = ::finish) { Text("Leave") }
                        }
                    }
                } else {
                    Root(viewModel, appLock)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        appLock.onReturned()
    }

    override fun onStop() {
        appLock.onLeftApp()
        super.onStop()
    }
}

fun startLogin(context: Context) {
    context.startActivity(Intent(context, LoginActivity::class.java))
}

@Composable
private fun Root(viewModel: MainViewModel, appLock: AppLock) {
    val context = LocalContext.current
    val message by viewModel.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    val route = viewModel.backStack.lastOrNull()
    BackHandler(enabled = route != null) { viewModel.back() }

    when (route) {
        null -> HomeScreen(viewModel, appLock)
        is Route.Listing -> ListingScreen(viewModel, route.feed)
        is Route.Creator -> CreatorScreen(viewModel, route)
        is Route.Player -> PlayerScreen(viewModel, route.feed, route.startIndex)
        Route.Settings -> SettingsScreen(viewModel)
    }
}
