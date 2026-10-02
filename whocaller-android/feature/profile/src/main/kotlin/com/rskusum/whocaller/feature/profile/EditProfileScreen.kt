package com.rskusum.whocaller.feature.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rskusum.whocaller.core.ui.component.AvatarImage
import com.rskusum.whocaller.core.ui.component.Avatars
import com.rskusum.whocaller.core.ui.component.PrimaryWideButton
import com.rskusum.whocaller.core.ui.component.ProfileAvatar
import com.rskusum.whocaller.core.ui.R as UiR

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(onBack: () -> Unit, onChangeNumber: () -> Unit = {}, viewModel: ProfileViewModel = hiltViewModel()) {
    val profile by viewModel.localProfile.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var loaded by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var profession by rememberSaveable { mutableStateOf("") }
    var institute by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var avatarId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showName by rememberSaveable { mutableStateOf(true) }

    // Fill the form once from the stored profile.
    LaunchedEffect(profile.updatedAt) {
        if (!loaded) {
            name = profile.name
            profession = profile.profession
            institute = profile.institute
            email = profile.email
            avatarId = profile.avatarId
            showName = profile.showNameToCallers
            loaded = profile.updatedAt > 0 || profile.name.isNotEmpty()
        }
    }
    LaunchedEffect(Unit) { viewModel.toasts.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    // System photo picker: no storage permission needed.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            avatarId = null
            viewModel.choosePhoto(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProfileAvatar(
                profile.copy(avatarId = avatarId, name = name),
                size = 112.dp,
                description = stringResource(R.string.profile_your_picture),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text(stringResource(R.string.profile_choose_photo), Modifier.padding(start = 6.dp))
                }
                if (profile.photoPath != null) {
                    TextButton(onClick = viewModel::removePhoto) {
                        Icon(Icons.Outlined.Delete, contentDescription = null)
                        Text(stringResource(R.string.profile_remove_photo), Modifier.padding(start = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.profile_choose_avatar), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // "Your own avatar": any picture from the gallery.
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .border(if (profile.photoPath != null) 3.dp else 1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(role = Role.Button) { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AddPhotoAlternate,
                        contentDescription = stringResource(R.string.profile_own_avatar),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Avatars.ALL.forEachIndexed { index, style ->
                    val selected = avatarId == index && profile.photoPath == null
                    val label = stringResource(R.string.profile_avatar_n, index + 1)
                    AvatarImage(
                        style,
                        size = 60.dp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            .clickable(role = Role.RadioButton) {
                                avatarId = index
                                if (profile.photoPath != null) viewModel.removePhoto()
                            }
                            .semantics {
                                contentDescription = label
                                this.selected = selected
                            },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Field(name, R.string.profile_name, KeyboardType.Text, KeyboardCapitalization.Words) { name = it.take(80) }
            // WhoCaller ID number: verified by SMS code, changed only through verification.
            androidx.compose.material3.ListItem(
                headlineContent = { Text(profile.phoneNumber.ifBlank { stringResource(R.string.profile_mobile_missing) }) },
                overlineContent = { Text(stringResource(R.string.profile_mobile)) },
                supportingContent = if (profile.phoneVerified) {
                    { Text(stringResource(R.string.complete_verified), color = MaterialTheme.colorScheme.primary) }
                } else {
                    null
                },
                trailingContent = { TextButton(onClick = onChangeNumber) { Text(stringResource(R.string.complete_change)) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.complete_show_name), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                androidx.compose.material3.Switch(checked = showName, onCheckedChange = { showName = it })
            }
            Field(profession, R.string.profile_profession, KeyboardType.Text, KeyboardCapitalization.Sentences) { profession = it.take(80) }
            Field(institute, R.string.profile_institute, KeyboardType.Text, KeyboardCapitalization.Words) { institute = it.take(80) }
            Field(email, R.string.profile_email, KeyboardType.Email, KeyboardCapitalization.None) { email = it.take(80) }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.profile_local_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            PrimaryWideButton(
                stringResource(R.string.profile_save),
                onClick = {
                    viewModel.saveProfile(name, profession, institute, email, avatarId, showName)
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    label: Int,
    keyboardType: KeyboardType,
    capitalization: KeyboardCapitalization,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
