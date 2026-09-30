package com.rskusum.whocaller.feature.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.rskusum.whocaller.core.common.result.AppError
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.permissions.findActivity
import com.rskusum.whocaller.core.ui.component.CallerAvatar
import com.rskusum.whocaller.core.ui.component.NavigationRow
import com.rskusum.whocaller.core.ui.component.SectionHeader
import com.rskusum.whocaller.core.ui.component.StatCard
import com.rskusum.whocaller.core.ui.util.messageRes
import kotlinx.coroutines.launch
import com.rskusum.whocaller.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onPrivacy: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    var editName by rememberSaveable { mutableStateOf<String?>(null) }
    var showSecurity by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CallerAvatar(user.name ?: user.email, CallerLabel.PERSON, size = 80.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        user.name ?: if (user.isGuest) stringResource(R.string.profile_guest) else user.email.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    if (user.isGuest) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.profile_guest_desc), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onSignIn) { Text(stringResource(R.string.profile_sign_in)) }
                    }
                }
            }
            if (!user.isGuest) {
                item {
                    ListItem(
                        overlineContent = { Text(stringResource(R.string.profile_name)) },
                        headlineContent = { Text(user.name ?: stringResource(R.string.profile_not_set)) },
                        trailingContent = { TextButton(onClick = { editName = user.name.orEmpty() }) { Text(stringResource(R.string.profile_edit_name)) } },
                    )
                }
                item { ListItem(overlineContent = { Text(stringResource(R.string.profile_email)) }, headlineContent = { Text(user.email ?: stringResource(R.string.profile_not_set)) }) }
                item { ListItem(overlineContent = { Text(stringResource(R.string.profile_phone)) }, headlineContent = { Text(user.phone ?: stringResource(R.string.profile_not_set)) }) }
            }
            item { SectionHeader(stringResource(R.string.profile_activity)) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(stats.numbersReported.toString(), stringResource(R.string.profile_reports), Icons.Outlined.Flag, Modifier.weight(1f))
                    StatCard(stats.numbersSearched.toString(), stringResource(R.string.profile_searched), Icons.Outlined.Search, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(stats.spamBlocked.toString(), stringResource(R.string.profile_blocked), Icons.Outlined.Block, Modifier.weight(1f))
                    StatCard(stats.callsIdentified.toString(), stringResource(R.string.profile_identified), Icons.Outlined.VerifiedUser, Modifier.weight(1f))
                }
            }
            item { Spacer(Modifier.height(8.dp)); HorizontalDivider() }
            item { NavigationRow(stringResource(R.string.profile_privacy), Icons.Outlined.Lock, onPrivacy) }
            item { NavigationRow(stringResource(R.string.profile_security), Icons.Outlined.Security, onClick = { showSecurity = true }) }
            if (!user.isGuest) {
                item { NavigationRow(stringResource(R.string.profile_delete_account), Icons.Outlined.Block, onPrivacy) }
                item { NavigationRow(stringResource(R.string.profile_sign_out), Icons.AutoMirrored.Outlined.Logout, onClick = { viewModel.signOut() }) }
            }
        }
    }

    editName?.let { current ->
        var value by rememberSaveable { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editName = null },
            title = { Text(stringResource(R.string.profile_edit_name)) },
            text = { OutlinedTextField(value = value, onValueChange = { value = it.take(60) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.updateName(value)
                    editName = null
                }) { Text(stringResource(R.string.profile_save)) }
            },
            dismissButton = { TextButton(onClick = { editName = null }) { Text(stringResource(UiR.string.action_cancel)) } },
        )
    }
    if (showSecurity) {
        AlertDialog(
            onDismissRequest = { showSecurity = false },
            title = { Text(stringResource(R.string.profile_security)) },
            text = { Text(stringResource(R.string.profile_security_desc)) },
            confirmButton = { TextButton(onClick = { showSecurity = false }) { Text(stringResource(UiR.string.action_close)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    config: SignInConfig,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.signIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf("choose") }

    LaunchedEffect(state.signedIn) { if (state.signedIn) onDone() }
    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    fun googleSignIn() {
        val activity = context.findActivity() ?: return
        scope.launch {
            try {
                val option = GetGoogleIdOption.Builder()
                    .setServerClientId(config.googleWebClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(activity).getCredential(activity, request)
                val token = GoogleIdTokenCredential.createFrom(result.credential.data).idToken
                viewModel.signInWithGoogleToken(token)
            } catch (_: GetCredentialCancellationException) {
                // User dismissed the sheet.
            } catch (_: GetCredentialException) {
                viewModel.googleFailed(AppError.UNAUTHORIZED)
            } catch (_: Exception) {
                viewModel.googleFailed(AppError.UNKNOWN)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.signin_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (mode == "choose") onBack() else mode = "choose" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!viewModel.accountsAvailable) {
                Text(stringResource(R.string.signin_unavailable))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signin_guest)) }
                return@Column
            }
            when (mode) {
                "email" -> {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = { v -> viewModel.edit { it.copy(email = v.take(120)) } },
                        label = { Text(stringResource(R.string.signin_email_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { v -> viewModel.edit { it.copy(password = v.take(128)) } },
                        label = { Text(stringResource(R.string.signin_password_label)) },
                        supportingText = { Text(stringResource(R.string.signin_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { viewModel.signInWithEmail(create = false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.signin_submit))
                    }
                    OutlinedButton(onClick = { viewModel.signInWithEmail(create = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.signin_create))
                    }
                    TextButton(onClick = viewModel::resetPassword) { Text(stringResource(R.string.signin_forgot)) }
                }
                "phone" -> {
                    OutlinedTextField(
                        value = state.phone,
                        onValueChange = { v -> viewModel.edit { it.copy(phone = v.filter { c -> c.isDigit() || c in "+ -()" }.take(24)) } },
                        label = { Text(stringResource(R.string.signin_phone_label)) },
                        singleLine = true,
                        enabled = !state.codeSent,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!state.codeSent) {
                        Button(
                            onClick = { context.findActivity()?.let(viewModel::sendCode) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.signin_send_code)) }
                    } else {
                        OutlinedTextField(
                            value = state.code,
                            onValueChange = { v -> viewModel.edit { it.copy(code = v.filter(Char::isDigit).take(8)) } },
                            label = { Text(stringResource(R.string.signin_code_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = viewModel::verifyCode, enabled = !state.busy && state.code.length >= 6, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.signin_verify))
                        }
                    }
                }
                else -> {
                    val googleReady = config.googleWebClientId.isNotBlank()
                    Button(onClick = ::googleSignIn, enabled = googleReady && !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.signin_google))
                    }
                    if (!googleReady) {
                        Text(stringResource(R.string.signin_google_unavailable), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { mode = "email" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signin_email)) }
                    if (viewModel.phoneAvailable) {
                        OutlinedButton(onClick = { mode = "phone" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signin_phone)) }
                    }
                    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signin_guest)) }
                    Text(stringResource(R.string.signin_terms), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            state.validation?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            state.error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            state.info?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.primary) }
        }
    }
}
