package com.rskusum.whocaller.feature.dialer

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.rskusum.whocaller.core.model.ThemeMode
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * WhoCaller's phone app: Recents, Contacts, Keypad, Favorites and More. Handles ACTION_DIAL and
 * tel: links (required for the default phone app role).
 */
@AndroidEntryPoint
class DialerActivity : ComponentActivity() {

    private val viewModel: DialerViewModel by viewModels()
    private var openKeypad by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) numberFrom(intent).takeIf { it.isNotEmpty() }?.let(viewModel::setNumber)
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            WhoCallerTheme(themeMode = themeMode, dynamicColor = false) {
                CompositionLocalProvider(LocalDialerPalette provides remember(dark) { dialerPalette(dark) }) {
                    DialerHome(viewModel, openKeypad)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        numberFrom(intent).takeIf { it.isNotEmpty() }?.let {
            viewModel.setNumber(it)
            openKeypad++
        }
    }

    private fun numberFrom(intent: Intent?): String =
        intent?.data?.schemeSpecificPart?.let(DialerViewModel::clean).orEmpty()
}

enum class DialerTab(val label: Int, val icon: ImageVector) {
    RECENTS(R.string.dialer_tab_recents, Icons.Filled.History),
    CONTACTS(R.string.dialer_tab_contacts, Icons.Filled.Contacts),
    KEYPAD(R.string.dialer_tab_keypad, Icons.Filled.Dialpad),
    FAVORITES(R.string.dialer_tab_favorites, Icons.Filled.Star),
    MORE(R.string.dialer_tab_more, Icons.Filled.MoreHoriz),
}

@Composable
private fun DialerHome(viewModel: DialerViewModel, openKeypad: Int) {
    val palette = LocalDialerPalette.current
    var tab by rememberSaveable { mutableStateOf(DialerTab.KEYPAD) }
    var details by remember { mutableStateOf<DetailsTarget?>(null) }
    val actions = rememberCallActions()
    LaunchedEffect(openKeypad) { if (openKeypad > 0) tab = DialerTab.KEYPAD }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }
    val context = LocalContext.current
    val deleted by viewModel.deletedCount.collectAsState()
    LaunchedEffect(deleted) {
        val n = deleted ?: return@LaunchedEffect
        val text = if (n < 0) {
            context.getString(R.string.dialer_delete_failed)
        } else {
            context.resources.getQuantityString(R.plurals.dialer_calls_deleted, n, n)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeDeleted()
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Box(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { current ->
                when (current) {
                    DialerTab.RECENTS -> RecentsTab(viewModel, actions) { details = it }
                    DialerTab.CONTACTS -> ContactsTab(viewModel, actions) { details = it }
                    DialerTab.KEYPAD -> KeypadTab(viewModel, actions, onShowDetails = { details = it }, onShowRecents = { tab = DialerTab.RECENTS })
                    DialerTab.FAVORITES -> FavoritesTab(viewModel, actions) { details = it }
                    DialerTab.MORE -> MoreTab(viewModel, actions)
                }
            }
        }
        BottomTabs(tab) { tab = it }
    }

    details?.let { target -> DetailsSheet(target, viewModel, actions) { details = null } }
    CallActionsHost(actions)
}

@Composable
private fun BottomTabs(selected: DialerTab, onSelect: (DialerTab) -> Unit) {
    val palette = LocalDialerPalette.current
    Column(Modifier.fillMaxWidth().background(palette.surface).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(color = palette.divider)
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            DialerTab.entries.forEach { tab ->
                val isSelected = tab == selected
                val color = if (isSelected) palette.accent else palette.subtle
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Tab) { onSelect(tab) }
                        .semantics { this.selected = isSelected }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isSelected) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                    ) { Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(tab.label),
                        color = color,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
