package com.openaria.openaria_echo_mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.openaria.openaria_echo_mobile.ui.EchoApp
import com.openaria.openaria_echo_mobile.ui.theme.EchoTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        requestNotificationPermissionIfNeeded()

        val localeStore = LocaleStore(this)
        val updateState = mutableStateOf<AppUpdateManager.State?>(null)
        val updateManager = AppUpdateManager(this) { state ->
            runOnUiThread { updateState.value = state }
        }
        updateState.value = updateManager.state()

        setContent {
            var localeTag by remember { mutableStateOf(localeStore.currentOrDefault()) }
            val baseContext = LocalContext.current
            val localizedContext = remember(baseContext, localeTag) {
                baseContext.localized(localeTag)
            }
            val localizedConfiguration = remember(localizedContext, localeTag) {
                Configuration(localizedContext.resources.configuration)
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfiguration,
            ) {
                EchoTheme {
                    EchoApp(
                        localeTag = localeTag,
                        updateState = updateState.value ?: updateManager.state(),
                        onLocaleChange = { next ->
                            localeStore.set(next)
                            localeTag = next
                        },
                        onCheckUpdate = updateManager::check,
                        onInstallUpdate = updateManager::downloadAndInstall,
                    )
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 1001
    }
}

private class LocaleStore(context: Context) {
    private val preferences = context.getSharedPreferences("openaria_echo_locale", Context.MODE_PRIVATE)

    fun currentOrDefault(): String {
        val current = preferences.getString(KEY, null)
        if (!current.isNullOrBlank()) {
            return current
        }
        preferences.edit().putString(KEY, DEFAULT).apply()
        return DEFAULT
    }

    fun set(localeTag: String) {
        val normalized = when (localeTag) {
            "en" -> "en"
            else -> DEFAULT
        }
        preferences.edit().putString(KEY, normalized).apply()
    }

    private companion object {
        const val DEFAULT = "zh-CN"
        const val KEY = "locale_tag"
    }
}

private fun Context.localized(localeTag: String): Context {
    val locale = Locale.forLanguageTag(localeTag)
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(locale))
    Locale.setDefault(locale)
    return createConfigurationContext(configuration)
}
