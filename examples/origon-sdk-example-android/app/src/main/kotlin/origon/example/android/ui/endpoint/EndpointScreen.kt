package origon.example.android.ui.endpoint

import ai.origon.sdk.SessionException
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import origon.example.android.R
import origon.example.android.services.SDKManager
import origon.example.android.ui.components.InvertedButton
import origon.example.android.ui.components.OrigonInput
import origon.example.android.ui.components.OrigonInputDefaultKeyboard
import origon.example.android.ui.components.ToastHost
import origon.example.android.ui.components.rememberToastState
import origon.example.android.ui.theme.OrigonTheme
import origon.example.android.util.SdkErrorKinds

/**
 * Endpoint-login screen. Takes a URL, hands it to the SDK via
 * `SDKManager.initialize(endpoint)`, and on success reports it back so the host
 * can persist it and move on to the chat surface.
 *
 * This is the whole of this example's authentication: an example connects to an
 * endpoint, it never signs a person in.
 */
@Composable
fun EndpointScreen(
    sdk: SDKManager,
    onAuthenticated: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToastState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val connectFailed = stringResource(R.string.endpoint_connect_failed)
    val emptyError = stringResource(R.string.endpoint_empty_error)

    // Focus the input and raise the keyboard once the screen has settled.
    LaunchedEffect(Unit) {
        delay(350)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val submit = {
        if (!loading) {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                error = emptyError
            } else {
                error = null
                loading = true
                scope.launch {
                    try {
                        sdk.initialize(endpoint = trimmed)
                        loading = false
                        onAuthenticated(trimmed)
                    } catch (e: SessionException) {
                        loading = false
                        toast.show(e.userFacingMessage(connectFailed))
                    } catch (e: Throwable) {
                        loading = false
                        toast.show(connectFailed)
                    }
                }
            }
        }
        Unit
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // safeDrawing = system bars ∪ IME ∪ cutout. Chaining
                // navigationBarsPadding() and imePadding() instead would add
                // both insets while the keyboard is up.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_origon_logo),
                contentDescription = null,
                modifier = Modifier.size(56.dp).padding(bottom = 24.dp),
            )
            Text(
                text = stringResource(R.string.endpoint_title),
                style = MaterialTheme.typography.headlineSmall,
                color = OrigonTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.endpoint_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = OrigonTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            )
            OrigonInput(
                value = value,
                onValueChange = {
                    value = it
                    if (error != null) error = null
                },
                placeholder = stringResource(R.string.endpoint_hint),
                errorMessage = error,
                keyboardOptions = EndpointKeyboard,
                keyboardActions = KeyboardActions(onGo = { submit() }),
                focusRequester = focusRequester,
                modifier = Modifier.fillMaxWidth(),
            )
            InvertedButton(
                title = stringResource(R.string.endpoint_continue),
                onClick = submit,
                loading = loading,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        ToastHost(toast)
    }
}

/** Short, user-facing message for a [SessionException] on this screen. */
private fun SessionException.userFacingMessage(fallback: String): String = when (kind) {
    SdkErrorKinds.MISSING_FIELD ->
        "Missing ${code ?: "field"}. Please enter a valid endpoint."
    SdkErrorKinds.HTTP -> {
        if (statusCode == 403 && code == "bundle_id_not_allowed") {
            "This app isn't authorized for that endpoint."
        } else {
            message?.takeIf { it.isNotEmpty() } ?: "Server error (HTTP $statusCode)."
        }
    }
    SdkErrorKinds.SERVER_UNAVAILABLE -> "Server unavailable. Please try again shortly."
    SdkErrorKinds.OTHER ->
        message?.takeIf { it.isNotEmpty() }
            ?: "Can't reach the server. Check the URL and your connection."
    else -> message ?: fallback
}

/**
 * The shared no-autocorrect/no-autocapitalise field options plus a **Go** key —
 * this screen's only action, so the keyboard offers it directly.
 */
private val EndpointKeyboard = KeyboardOptions(
    capitalization = OrigonInputDefaultKeyboard.capitalization,
    autoCorrectEnabled = OrigonInputDefaultKeyboard.autoCorrectEnabled,
    imeAction = ImeAction.Go,
)
