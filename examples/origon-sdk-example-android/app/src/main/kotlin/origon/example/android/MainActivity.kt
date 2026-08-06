package origon.example.android

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import origon.example.android.data.StorageKeys
import origon.example.android.services.SDKManager
import origon.example.android.ui.chat.RootChatScreen
import origon.example.android.ui.endpoint.EndpointScreen
import origon.example.android.ui.theme.OrigonTheme

/**
 * Single-Activity host. Gates between the Endpoint screen and the chat surface
 * on the persisted endpoint:
 *   - no endpoint saved → [EndpointScreen]
 *   - endpoint present   → [RootChatScreen] (which boots the SDK)
 */
class MainActivity : ComponentActivity() {

    private val sdk: SDKManager get() = (application as OrigonExampleApp).sdk

    private val prefs by lazy {
        getSharedPreferences(StorageKeys.PREFS, Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge-to-edge on every API level (Android 15 enforces this for
        // SDK 35 targets anyway). The screens restore safe-area spacing with
        // the window-inset modifiers; the theme keeps the bar icons legible.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            OrigonTheme {
                // The Activity is recreated on a configuration change, at which
                // point this re-reads the store.
                var endpoint by remember { mutableStateOf(currentEndpoint()) }

                val saved = endpoint
                if (saved.isNullOrEmpty()) {
                    EndpointScreen(
                        sdk = sdk,
                        onAuthenticated = { url ->
                            prefs.edit { putString(StorageKeys.ORIGON_ENDPOINT, url) }
                            endpoint = url
                        },
                    )
                } else {
                    RootChatScreen(
                        sdk = sdk,
                        endpoint = saved,
                        onChangeEndpoint = {
                            sdk.teardown()
                            prefs.edit { remove(StorageKeys.ORIGON_ENDPOINT) }
                            endpoint = null
                        },
                    )
                }
            }
        }
    }

    private fun currentEndpoint(): String? =
        prefs.getString(StorageKeys.ORIGON_ENDPOINT, null)
}
