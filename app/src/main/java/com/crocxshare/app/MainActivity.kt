package com.crocxshare.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.crocxshare.app.ui.AppRoot
import com.crocxshare.app.ui.theme.CrocXTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val app get() = application as CrocXApp

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) app.container.pendingSourcesFlow.value = uris
    }

    private val pickFolderSend = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.container.pendingFolderTreeUri = uri
            app.container.folderPickCount.value += 1
        }
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            app.container.settings.receiveDirUri = it.toString()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShareIntent(intent)
        handleShortcut(intent)
        setContent {
            CrocXTheme(
                theme = app.container.settings.theme,
                colorName = app.container.settings.themeColor
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        container = app.container,
                        onPickFiles = {
                            pickFiles.launch(app.container.pendingMime)
                        },
                        onRecreate = { recreate() },
                        onPickFolder = { pickFolder.launch(null) },
                        onPickFolderSend = { pickFolderSend.launch(null) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        handleShortcut(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrEmpty()) app.container.shareText = text.take(100_000)
        }
    }

    private fun handleShortcut(intent: Intent?) {
        val screen = intent?.getStringExtra("screen")
        if (!screen.isNullOrEmpty()) app.container.initialScreen = screen
    }
}

object LocaleHelper {
    fun wrap(context: Context): Context {
        val prefs = context.getSharedPreferences("crocxshare_settings", Context.MODE_PRIVATE)
        val code = prefs.getString("language", "SYSTEM") ?: "SYSTEM"
        if (code == "SYSTEM") return context
        val locale = Locale(code.lowercase())
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
