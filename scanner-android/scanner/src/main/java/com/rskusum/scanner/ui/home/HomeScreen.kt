package com.rskusum.scanner.ui.home

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.rskusum.scanner.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.scanner.ScannerViewModel
import com.rskusum.scanner.data.SavedDocument
import com.rskusum.scanner.ui.ScanColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ScannerViewModel, onScan: () -> Unit) {
    val context = LocalContext.current
    var renameTarget by remember { mutableStateOf<SavedDocument?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedDocument?>(null) }

    fun open(doc: SavedDocument) {
        try {
            context.startActivity(vm.store.openIntent(doc.file))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.rs_scanner_no_pdf_viewer), Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = ScanColors.Surface,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
                title = { Text(stringResource(R.string.rs_scanner_recent_scans), fontWeight = FontWeight.SemiBold) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onScan,
                containerColor = Color.Transparent,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier.background(ScanColors.Gradient, RoundedCornerShape(16.dp)),
                icon = { Icon(Icons.Outlined.DocumentScanner, null) },
                text = { Text(if (vm.pages.isEmpty()) stringResource(R.string.rs_scanner_scan) else stringResource(R.string.rs_scanner_resume_scan, vm.pages.size)) },
            )
        },
    ) { padding ->
        if (vm.documents.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Description, null, tint = ScanColors.TextDim, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.rs_scanner_no_scans), color = Color.White, fontSize = 18.sp)
                Text(stringResource(R.string.rs_scanner_no_scans_hint), color = ScanColors.TextDim)
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(vm.documents, key = { it.file.absolutePath }) { doc ->
                    DocumentRow(
                        vm = vm,
                        doc = doc,
                        onOpen = { open(doc) },
                        onShare = { runCatching { context.startActivity(vm.store.shareIntent(listOf(doc.file), "application/pdf")) } },
                        onRename = { renameTarget = doc },
                        onDelete = { deleteTarget = doc },
                    )
                    HorizontalDivider(color = Color(0x22FFFFFF))
                }
            }
        }
    }

    renameTarget?.let { doc ->
        var text by remember(doc) { mutableStateOf(doc.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.rs_scanner_rename)) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank() && text != doc.name) vm.renameDocument(doc, text)
                    renameTarget = null
                }) { Text(stringResource(R.string.rs_scanner_ok)) }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.rs_scanner_cancel)) } },
        )
    }
    deleteTarget?.let { doc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.rs_scanner_delete_doc_title, doc.name)) },
            text = { Text(stringResource(R.string.rs_scanner_delete_doc_message)) },
            confirmButton = { TextButton(onClick = { vm.deleteDocument(doc); deleteTarget = null }) { Text(stringResource(R.string.rs_scanner_tool_delete)) } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.rs_scanner_cancel)) } },
        )
    }
}

@Composable
private fun DocumentRow(
    vm: ScannerViewModel,
    doc: SavedDocument,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(null, doc.file, doc.modified) {
        value = withContext(Dispatchers.IO) { vm.store.thumbnail(doc.file, 160)?.asImageBitmap() }
    }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 54.dp, height = 72.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White),
        ) {
            thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(doc.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                "${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(doc.modified))} · " +
                    pluralStringResource(R.plurals.rs_scanner_pages, doc.pageCount, doc.pageCount) + " · ${formatSize(doc.sizeBytes)}",
                color = ScanColors.TextDim,
                fontSize = 12.sp,
            )
        }
        IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, stringResource(R.string.rs_scanner_share), tint = Color.White) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.rs_scanner_more), tint = Color.White) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_open)) }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_share)) }, onClick = { menu = false; onShare() })
                DropdownMenuItem(text = { Text(stringResource(R.string.rs_scanner_tool_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
