package com.vibe.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.app.R
import com.vibe.app.core.VibeResult
import com.vibe.app.data.remote.ApiErrorKind
import com.vibe.app.data.repository.AuthRepository
import com.vibe.app.domain.FieldError
import com.vibe.app.identity.GoogleSignInGateway
import com.vibe.app.ui.components.ErrorText
import com.vibe.app.ui.components.PrimaryButton
import com.vibe.app.ui.components.TaglineText
import com.vibe.app.ui.components.VibeCard
import com.vibe.app.ui.components.Wordmark
import com.vibe.app.ui.theme.VibeCoral
import com.vibe.app.ui.theme.VibeLilac
import com.vibe.app.ui.theme.VibeMint
import com.vibe.app.ui.vibeViewModel
import kotlinx.coroutines.launch

/** ------------------------------------------------ Splash / onboarding ---- */

@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Wordmark()
        Spacer(modifier = Modifier.height(8.dp))
        TaglineText()
        Spacer(modifier = Modifier.height(24.dp))

        OnboardingCard("🎉", stringResource(R.string.onboarding_title_1), stringResource(R.string.onboarding_body_1))
        OnboardingCard("🗳️", stringResource(R.string.onboarding_title_2), stringResource(R.string.onboarding_body_2))
        OnboardingCard("📸", stringResource(R.string.onboarding_title_3), stringResource(R.string.onboarding_body_3))

        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(text = stringResource(R.string.action_get_started), onClick = onGetStarted)
    }
}

@Composable
private fun OnboardingCard(icon: String, title: String, body: String) {
    VibeCard(modifier = Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 26.sp, modifier = Modifier.padding(end = 12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** ---------------------------------------------------------- auth state ---- */

/** Everything the auth screens can say, so no magic strings float around. */
sealed interface AuthMessage {
    data object LoginFailed : AuthMessage
    data object Offline : AuthMessage
    data object GoogleNotConfigured : AuthMessage
    data object ResetSent : AuthMessage
    data object RegisterFailed : AuthMessage
}

data class AuthUiState(
    val loading: Boolean = false,
    val error: FieldError? = null,
    val message: AuthMessage? = null,
    val showPassword: Boolean = false,
)

class AuthViewModel(
    private val repository: AuthRepository,
    private val googleSignIn: GoogleSignInGateway,
) : ViewModel() {

    var state by mutableStateOf(AuthUiState())
        private set

    fun togglePasswordVisible() {
        state = state.copy(showPassword = !state.showPassword)
    }

    fun login(email: String, password: String, onSuccess: () -> Unit) {
        state = state.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            when (val result = repository.login(email, password)) {
                is VibeResult.Ok -> {
                    state = state.copy(loading = false, showPassword = false)
                    onSuccess()
                }

                is VibeResult.Problem -> state = state.copy(
                    loading = false,
                    error = result.error,
                    message = when {
                        result.error != null -> null
                        result.kind == ApiErrorKind.NETWORK -> AuthMessage.Offline
                        else -> AuthMessage.LoginFailed
                    },
                )
            }
        }
    }

    fun register(displayName: String, email: String, password: String, confirm: String, onSuccess: () -> Unit) {
        state = state.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            when (val result = repository.register(displayName, email, password, confirm)) {
                is VibeResult.Ok -> {
                    state = state.copy(loading = false, showPassword = false)
                    onSuccess()
                }

                is VibeResult.Problem -> state = state.copy(
                    loading = false,
                    error = result.error,
                    message = if (result.error == null) AuthMessage.RegisterFailed else null,
                )
            }
        }
    }

    /** PoE: Google SSO through Credential Manager; the API verifies the token. */
    fun signInWithGoogle(onSuccess: () -> Unit) {
        state = state.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val token = googleSignIn.requestIdToken()
            if (token.isFailure) {
                state = state.copy(loading = false, message = AuthMessage.GoogleNotConfigured)
                return@launch
            }
            when (val result = repository.signInWithGoogle(token.getOrThrow())) {
                is VibeResult.Ok -> {
                    state = state.copy(loading = false, showPassword = false)
                    onSuccess()
                }

                is VibeResult.Problem -> state = state.copy(loading = false, message = AuthMessage.LoginFailed)
            }
        }
    }

    fun requestPasswordReset(email: String) {
        state = state.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            when (val result = repository.requestPasswordReset(email)) {
                is VibeResult.Ok -> state = state.copy(loading = false, message = AuthMessage.ResetSent)
                is VibeResult.Problem -> state = state.copy(
                    loading = false,
                    error = result.error,
                    message = if (result.error == null) AuthMessage.Offline else null,
                )
            }
        }
    }
}

@Composable
private fun AuthMessageView(message: AuthMessage?) {
    when (message) {
        AuthMessage.LoginFailed -> ErrorText(stringResource(R.string.error_login_failed))
        AuthMessage.Offline -> ErrorText(stringResource(R.string.error_offline_auth))
        AuthMessage.GoogleNotConfigured -> ErrorText(stringResource(R.string.error_generic))
        AuthMessage.RegisterFailed -> ErrorText(stringResource(R.string.error_generic))
        AuthMessage.ResetSent -> Text(
            text = stringResource(R.string.password_reset_sent),
            color = VibeMint,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        null -> Unit
    }
}

/** ---------------------------------------------------------------- login --- */

@Composable
fun LoginScreen(onSignedIn: () -> Unit, onGoToRegister: () -> Unit) {
    val viewModel = vibeViewModel { AuthViewModel(it.authRepository, it.googleSignIn) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Wordmark()
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )

        EmailField(value = email, onValueChange = { email = it })
        PasswordField(
            label = stringResource(R.string.field_password),
            value = password,
            onValueChange = { password = it },
            visible = viewModel.state.showPassword,
            onToggleVisibility = { viewModel.togglePasswordVisible() },
        )

        ErrorText(viewModel.state.error)
        AuthMessageView(viewModel.state.message)
        Text(
            text = stringResource(R.string.password_reset_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))
        PrimaryButton(
            text = stringResource(R.string.action_login),
            enabled = !viewModel.state.loading,
            onClick = { viewModel.login(email, password, onSignedIn) },
        )
        TextButton(onClick = { viewModel.requestPasswordReset(email) }) {
            Text(text = stringResource(R.string.forgot_password), color = VibeLilac)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Divider(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.auth_or_divider),
                modifier = Modifier.padding(horizontal = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Divider(modifier = Modifier.weight(1f))
        }

        SsoButton(
            badge = "G",
            badgeColor = VibeCoral,
            label = stringResource(R.string.continue_with_google),
            enabled = !viewModel.state.loading,
        ) {
            viewModel.signInWithGoogle(onSignedIn)
        }
        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onGoToRegister) {
            Text(
                text = stringResource(R.string.link_to_register),
                color = VibeCoral,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** ------------------------------------------------------------- register --- */

@Composable
fun RegisterScreen(onSignedIn: () -> Unit, onGoToLogin: () -> Unit) {
    val viewModel = vibeViewModel { AuthViewModel(it.authRepository, it.googleSignIn) }

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Wordmark()
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = stringResource(R.string.register_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.register_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.field_display_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(10.dp))
        EmailField(value = email, onValueChange = { email = it })
        PasswordField(
            label = stringResource(R.string.field_password),
            value = password,
            onValueChange = { password = it },
            visible = viewModel.state.showPassword,
            onToggleVisibility = { viewModel.togglePasswordVisible() },
        )
        PasswordField(
            label = stringResource(R.string.field_confirm_password),
            value = confirm,
            onValueChange = { confirm = it },
            visible = viewModel.state.showPassword,
            onToggleVisibility = { viewModel.togglePasswordVisible() },
        )

        ErrorText(viewModel.state.error)
        AuthMessageView(viewModel.state.message)

        Spacer(modifier = Modifier.height(12.dp))
        PrimaryButton(
            text = stringResource(R.string.action_register),
            enabled = !viewModel.state.loading,
            onClick = { viewModel.register(name, email, password, confirm, onSignedIn) },
        )
        TextButton(onClick = onGoToLogin) {
            Text(
                text = stringResource(R.string.link_to_login),
                color = VibeCoral,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.password_reset_help),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** ------------------------------------------------------------ fields ------ */

@Composable
private fun EmailField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.field_email)) },
        leadingIcon = { Text(text = "✉️", fontSize = 18.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Text(text = "🔒", fontSize = 18.sp) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        trailingIcon = {
            // Eye control: shows or hides the password. The value itself is never
            // logged, emailed or stored in plain text anywhere in the app.
            IconButton(onClick = onToggleVisibility) {
                Text(text = if (visible) "🙈" else "👁", fontSize = 18.sp)
            }
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun SsoButton(
    badge: String,
    badgeColor: Color,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(
            modifier = Modifier.width(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = badge, color = badgeColor, fontWeight = FontWeight.ExtraBold)
        }
        Text(text = label, fontWeight = FontWeight.SemiBold)
    }
}
