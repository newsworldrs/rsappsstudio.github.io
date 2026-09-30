package com.rskusum.scanner.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.scanner.R
import com.rskusum.scanner.ScannerViewModel
import androidx.compose.ui.res.stringResource
import com.rskusum.scanner.data.Page
import com.rskusum.scanner.ocr.Insight
import com.rskusum.scanner.ui.ScanColors

/**
 * AI Text: the captured page with its recognised text (editable), the detected document type and
 * tappable key details. Everything runs on the device.
 */
@Composable
fun AiTextScreen(
    vm: ScannerViewModel,
    page: Page?,
    onBack: () -> Unit,
    onScanMore: () -> Unit,
    onDone: () -> Unit,
) {
    if (page == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val context = LocalContext.current
    var text by remember(page.id, page.ocrBusy) { mutableStateOf(page.ocrText.orEmpty()) }
    val insights = page.insights

    Column(
        Modifier
            .fillMaxSize()
            .background(ScanColors.Surface)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(ScanColors.Bar).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.rs_scanner_back), tint = Color.White) }
            Icon(Icons.Filled.AutoAwesome, null, tint = ScanColors.AccentBright, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rs_scanner_mode_ai_text), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (page.ocrText != null && !page.ocrBusy) {
                IconButton(onClick = { vm.extractText(page, force = true) }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.rs_scanner_read_again), tint = Color.White)
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Page + what it is
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(width = 78.dp, height = 104.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ScanColors.SurfaceHigh)
                        .border(1.dp, ScanColors.Accent, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb = page.thumbnail
                    if (thumb != null) Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    when {
                        page.ocrBusy -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = ScanColors.AccentBright, strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    stringResource(if (vm.ocrDownloading) R.string.rs_scanner_downloading_model else R.string.rs_scanner_reading_text),
                                    color = Color.White, fontSize = 15.sp,
                                )
                            }
                        }
                        page.ocrError != null -> {
                            Text(page.ocrError.orEmpty(), color = Color(0xFFFF8A80), fontSize = 14.sp)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = { vm.extractText(page, force = true) }) { Text(stringResource(R.string.rs_scanner_try_again)) }
                        }
                        insights != null -> {
                            Text(stringResource(R.string.rs_scanner_looks_like), color = ScanColors.TextDim, fontSize = 12.sp)
                            Text(stringResource(insights.type.labelRes), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text(
                                stringResource(R.string.rs_scanner_text_stats, insights.words, insights.lines, page.ocrConfidence),
                                color = ScanColors.TextDim, fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            // Key details
            if (insights != null && insights.items.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.rs_scanner_key_details), color = ScanColors.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    insights.items.forEach { item -> InsightChip(item) { act(context, item) } }
                }
            }

            // Text
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.rs_scanner_tool_text), color = ScanColors.TextDim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (page.ocrText != null) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; vm.setOcrText(page, it) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 8,
                    placeholder = { Text(stringResource(R.string.rs_scanner_no_text_found), color = ScanColors.TextDim) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = ScanColors.AccentBright, unfocusedBorderColor = ScanColors.SurfaceHigh,
                        cursorColor = ScanColors.AccentBright,
                    ),
                )
            } else if (!page.ocrBusy && page.ocrError == null) {
                Text("…", color = ScanColors.TextDim)
            }
        }

        // Actions
        Row(
            Modifier.fillMaxWidth().background(ScanColors.Bar).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hasText = text.isNotBlank()
            ActionIcon(Icons.Filled.ContentCopy, stringResource(R.string.rs_scanner_copy), enabled = hasText) { copy(context, text) }
            ActionIcon(Icons.Filled.Share, stringResource(R.string.rs_scanner_share), enabled = hasText) {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), context.getString(R.string.rs_scanner_share_text)),
                    )
                }
            }
            ActionIcon(Icons.Filled.CameraAlt, stringResource(R.string.rs_scanner_scan_more), enabled = true, onClick = onScanMore)
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onDone,
                enabled = !page.ocrBusy,
                colors = ButtonDefaults.buttonColors(containerColor = ScanColors.Accent),
            ) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.rs_scanner_done), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun InsightChip(item: Insight, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ScanColors.SurfaceHigh)
            .border(1.dp, ScanColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(stringResource(item.kind.labelRes), color = ScanColors.Marigold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(item.value, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val c = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
        Icon(icon, label, tint = c, modifier = Modifier.size(22.dp))
        Text(label, color = c, fontSize = 11.sp)
    }
}

/** Tap on a key detail: open links, write e-mails, dial numbers; anything else is copied. */
private fun act(context: Context, item: Insight) {
    val intent = when (item.kind) {
        Insight.Kind.LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(if (item.value.startsWith("http", true)) item.value else "https://${item.value}"))
        Insight.Kind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${item.value}"))
        Insight.Kind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${item.value.filter { it.isDigit() || it == '+' }}"))
        else -> null
    }
    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) copy(context, item.value)
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.rs_scanner_scanned_text), text))
    Toast.makeText(context, context.getString(R.string.rs_scanner_copied), Toast.LENGTH_SHORT).show()
}
