package com.rskusum.whocaller.feature.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Surface
import androidx.compose.material.icons.outlined.Email
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
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
import com.rskusum.whocaller.core.ui.component.ProfileAvatar
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
    onEditProfile: () -> Unit,
    onSetUpId: () -> Unit = {},
    onPrivacy: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val user by viewModel.user.collectAsStateWithLifecycle()
    val local by viewModel.localProfile.collectAsStateWithLifecycle()
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
                    ProfileAvatar(local.copy(name = local.name.ifBlank { user.name.orEmpty() }), size = 96.dp, description = stringResource(R.string.profile_your_picture))
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            local.name.ifBlank { user.name ?: if (user.isGuest) stringResource(R.string.profile_guest) else user.email.orEmpty() },
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (local.isComplete && !user.isGuest) {
                            Spacer(Modifier.width(6.dp))
                            com.rskusum.whocaller.core.ui.component.VerifiedTick(22.dp, description = stringResource(com.rskusum.whocaller.core.ui.R.string.label_verified_id))
                        }
                    }
                    if (local.isComplete && !user.isGuest) {
                        Text(
                            stringResource(com.rskusum.whocaller.core.ui.R.string.label_verified_id) + " · " + local.phoneNumber,
                            style = MaterialTheme.typography.bodyMedium,
                            color = com.rskusum.whocaller.core.ui.component.VerifiedBlue,
                        )
                    }
                    val subtitle = listOf(local.profession, local.institute).filter { it.isNotBlank() }.joinToString(" · ")
                    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    local.email.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onEditProfile) { Text(stringResource(R.string.profile_edit)) }
                    // Skipped the WhoCaller ID: a clear way back to it.
                    if (!user.isGuest && !local.isComplete) {
                        Spacer(Modifier.height(12.dp))
                        Card(
                            onClick = onSetUpId,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.rskusum.whocaller.core.ui.component.VerifiedTick(32.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.profile_setup_id_title), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.profile_setup_id_text), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (user.isGuest) {
                        Spacer(Modifier.height(12.dp))
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
    /** First-run sign-in: no guest option, no back button (an account is required for the WhoCaller ID). */
    mandatory: Boolean = false,
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

    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        topBar = {
            // Only the email/phone steps need a top bar; the welcome screen is full-bleed.
            if (mode != "choose") {
                TopAppBar(
                    title = { Text(stringResource(if (mode == "email") R.string.signin_email else R.string.signin_phone)) },
                    navigationIcon = {
                        IconButton(onClick = { mode = "choose" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mode == "choose") {
                // Hero: logo on a soft brand gradient, app name and what you get.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.primaryContainer, colors.primaryContainer.copy(alpha = 0.35f), colors.background),
                            ),
                        )
                        .padding(top = 40.dp, bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!mandatory) {
                        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(112.dp)
                                .shadow(16.dp, CircleShape, ambientColor = colors.primary, spotColor = colors.primary)
                                .clip(CircleShape)
                                .background(colors.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (config.logoRes != 0) {
                                Image(painterResource(config.logoRes), contentDescription = null, modifier = Modifier.size(84.dp))
                            } else {
                                Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = colors.primary, modifier = Modifier.size(56.dp))
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            stringResource(R.string.signin_welcome),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = colors.onBackground,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.signin_tagline),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SignInBenefit(Icons.Outlined.Search, R.string.signin_benefit_identify)
                    SignInBenefit(Icons.Outlined.Block, R.string.signin_benefit_spam)
                    SignInBenefit(Icons.Outlined.VerifiedUser, R.string.signin_benefit_tick)
                }
                Spacer(Modifier.height(24.dp))
            }

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
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
                            shape = RoundedCornerShape(16.dp),
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
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { viewModel.signInWithEmail(create = false) },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.signin_submit)) }
                        OutlinedButton(
                            onClick = { viewModel.signInWithEmail(create = true) },
                            enabled = !state.busy,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text(stringResource(R.string.signin_create)) }
                        TextButton(onClick = viewModel::resetPassword, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(stringResource(R.string.signin_forgot))
                        }
                    }
                    "phone" -> {
                        OutlinedTextField(
                            value = state.phone,
                            onValueChange = { v -> viewModel.edit { it.copy(phone = v.filter { c -> c.isDigit() || c in "+ -()" }.take(24)) } },
                            label = { Text(stringResource(R.string.signin_phone_label)) },
                            singleLine = true,
                            enabled = !state.codeSent,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!state.codeSent) {
                            Button(
                                onClick = { context.findActivity()?.let(viewModel::sendCode) },
                                enabled = !state.busy,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text(stringResource(R.string.signin_send_code)) }
                        } else {
                            OutlinedTextField(
                                value = state.code,
                                onValueChange = { v -> viewModel.edit { it.copy(code = v.filter(Char::isDigit).take(8)) } },
                                label = { Text(stringResource(R.string.signin_code_label)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = viewModel::verifyCode,
                                enabled = !state.busy && state.code.length >= 6,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text(stringResource(R.string.signin_verify)) }
                        }
                    }
                    else -> {
                        val googleReady = config.googleWebClientId.isNotBlank()
                        // Google: white button with the multi-colour "G", as Google's guidelines ask.
                        Surface(
                            onClick = ::googleSignIn,
                            enabled = googleReady && !state.busy,
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFDADCE0)),
                            shadowElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                GoogleG(Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.signin_google), color = Color(0xFF1F1F1F), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (!googleReady) {
                            Text(stringResource(R.string.signin_google_unavailable), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { mode = "email" },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Icon(Icons.Outlined.Email, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.signin_email), style = MaterialTheme.typography.titleMedium)
                        }
                        if (viewModel.phoneAvailable && !mandatory) {
                            OutlinedButton(
                                onClick = { mode = "phone" },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) { Text(stringResource(R.string.signin_phone)) }
                        }
                        if (!mandatory) {
                            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.signin_guest)) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.signin_terms), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                state.validation?.let { Text(stringResource(it), color = colors.error) }
                state.error?.let { Text(stringResource(it.messageRes()), color = colors.error) }
                state.info?.let { Text(stringResource(it), color = colors.primary) }
            }
        }
    }
}

@Composable
private fun SignInBenefit(icon: ImageVector, text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Google's four-colour "G" mark, drawn in code (no image asset needed). */
@Composable
private fun GoogleG(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = size.minDimension * 0.18f
        val inset = stroke / 2
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        val style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
        drawArc(Color(0xFFEA4335), -40f, -100f, false, topLeft, arcSize, style = style) // red (top)
        drawArc(Color(0xFFFBBC05), -140f, -80f, false, topLeft, arcSize, style = style) // yellow (left)
        drawArc(Color(0xFF34A853), 140f, -100f, false, topLeft, arcSize, style = style) // green (bottom)
        drawArc(Color(0xFF4285F4), 40f, -80f, false, topLeft, arcSize, style = style) // blue (right)
        // The bar of the G.
        drawLine(
            Color(0xFF4285F4),
            start = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2),
            end = androidx.compose.ui.geometry.Offset(size.width - inset, size.height / 2),
            strokeWidth = stroke,
        )
    }
}
