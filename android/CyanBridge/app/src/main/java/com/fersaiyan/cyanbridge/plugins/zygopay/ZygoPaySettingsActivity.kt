package com.fersaiyan.cyanbridge.plugins.zygopay

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fersaiyan.cyanbridge.ui.appearance.AppearancePreferences
import com.fersaiyan.cyanbridge.ui.appearance.rememberAppearanceSettings
import com.fersaiyan.cyanbridge.ui.theme.CyanBridgeTheme

/**
 * Where the service endpoint gets changed without a rebuild.
 *
 * This is the piece `docs/upi-payments.md` names as missing: without it, `ZygoPayPreferences`
 * only ever reads the Gradle-property default baked in at build time, so pointing a demo APK at a
 * different deployment meant a new build. Whoever builds the APK is rarely whoever is about to run
 * it, and a same-day change to a demo URL is exactly the case a rebuild is the wrong answer to.
 *
 * Not itself a payment surface: nothing here can spend anything, so it carries none of
 * `ZygoPayActivity`'s confirmation ceremony. It uses the app's own theme via [CyanBridgeTheme]
 * rather than the plain [MaterialTheme] wrapper the payment screen uses, matching every other
 * plugin's settings screen in this app.
 */
class ZygoPaySettingsActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val appearance = rememberAppearanceSettings(AppearancePreferences(this))
            CyanBridgeTheme(appearance.value) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(onBack = ::finish)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var serviceUrl by remember {
        mutableStateOf(ZygoPayPreferences.storedServiceUrl(context).orEmpty())
    }
    var proxyToken by remember {
        mutableStateOf(ZygoPayPreferences.storedProxyToken(context).orEmpty())
    }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UPI Pay settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Points this plugin at the Zygo service in services/zygo/. Leave blank to use " +
                    "the value built into this APK.",
                style = MaterialTheme.typography.bodyMedium,
            )

            OutlinedTextField(
                value = serviceUrl,
                onValueChange = { serviceUrl = it },
                label = { Text("Service URL") },
                placeholder = { Text("https://your-deployment.vercel.app") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Zygo service URL" },
            )

            OutlinedTextField(
                value = proxyToken,
                onValueChange = { proxyToken = it },
                label = { Text("Proxy token") },
                placeholder = { Text("Matches ZYGO_PROXY_TOKEN on the service") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Zygo proxy token" },
            )

            Text(
                // Said plainly rather than implied: this is not the merchant secret, and it ships
                // inside the APK either way. See services/zygo/README.md for what it is worth.
                "Not the merchant key — that never leaves the service. This is only the token the " +
                    "app presents to it.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = {
                    ZygoPayPreferences.setServiceUrl(context, serviceUrl)
                    ZygoPayPreferences.setProxyToken(context, proxyToken)
                    savedMessage = if (ZygoPayPreferences.isConfigured(context)) {
                        "Saved. UPI Pay can be turned on now."
                    } else {
                        "Saved, but no URL and token are set yet — from either this screen or the " +
                            "build. UPI Pay stays off until both are present."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            if (serviceUrl.isNotBlank() || proxyToken.isNotBlank()) {
                TextButton(
                    onClick = {
                        ZygoPayPreferences.setServiceUrl(context, "")
                        ZygoPayPreferences.setProxyToken(context, "")
                        serviceUrl = ""
                        proxyToken = ""
                        savedMessage = "Cleared. Back to whatever this APK was built with, if anything."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Clear override, use the build default")
                }
            }

            savedMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
