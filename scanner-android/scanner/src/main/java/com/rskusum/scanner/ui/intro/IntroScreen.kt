package com.rskusum.scanner.ui.intro

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.CropPortrait
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.TextSnippet
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rskusum.scanner.R
import com.rskusum.scanner.ui.ScanColors
import kotlinx.coroutines.launch

/** First-run tour: shown once per install (see [IntroPrefs]). */
internal object IntroPrefs {
    private const val FILE = "rs_scanner"
    private const val KEY = "intro_shown_v1"
    fun shown(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun markShown(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

/** One explained control: numbered badge on the mockup + line in the legend. */
private class Item(val icon: ImageVector, @StringRes val title: Int, @StringRes val text: Int)

private val cameraItems = listOf(
    Item(Icons.Filled.FlashAuto, R.string.rs_scanner_intro_flash, R.string.rs_scanner_intro_flash_text),
    Item(Icons.Filled.CameraAlt, R.string.rs_scanner_intro_auto, R.string.rs_scanner_intro_auto_text),
    Item(Icons.Filled.CheckBox, R.string.rs_scanner_intro_hd, R.string.rs_scanner_intro_hd_text),
    Item(Icons.Filled.AutoAwesome, R.string.rs_scanner_intro_smart, R.string.rs_scanner_intro_smart_text),
    Item(Icons.Outlined.CropPortrait, R.string.rs_scanner_intro_frame, R.string.rs_scanner_intro_frame_text),
    Item(Icons.Outlined.ZoomOutMap, R.string.rs_scanner_intro_zoom, R.string.rs_scanner_intro_zoom_text),
    Item(Icons.Outlined.Description, R.string.rs_scanner_intro_thumb, R.string.rs_scanner_intro_thumb_text),
    Item(Icons.Filled.CameraAlt, R.string.rs_scanner_intro_shutter, R.string.rs_scanner_intro_shutter_text),
    Item(Icons.Outlined.PhotoLibrary, R.string.rs_scanner_intro_gallery, R.string.rs_scanner_intro_gallery_text),
)

private val modeItems = listOf(
    Item(Icons.Outlined.Description, R.string.rs_scanner_mode_document, R.string.rs_scanner_intro_document_text),
    Item(Icons.Outlined.ImportContacts, R.string.rs_scanner_mode_book, R.string.rs_scanner_intro_book_text),
    Item(Icons.Outlined.Book, R.string.rs_scanner_mode_book_cover, R.string.rs_scanner_intro_cover_text),
    Item(Icons.Outlined.Badge, R.string.rs_scanner_mode_id_card, R.string.rs_scanner_intro_id_text),
    Item(Icons.Outlined.ContactPage, R.string.rs_scanner_mode_business_card, R.string.rs_scanner_intro_bcard_text),
    Item(Icons.Outlined.Dashboard, R.string.rs_scanner_mode_whiteboard, R.string.rs_scanner_intro_whiteboard_text),
    Item(Icons.Filled.AutoAwesome, R.string.rs_scanner_mode_ai_text, R.string.rs_scanner_intro_ai_text),
    Item(Icons.Outlined.QrCodeScanner, R.string.rs_scanner_intro_qr, R.string.rs_scanner_intro_qr_text),
)

private val editItems = listOf(
    Item(Icons.Outlined.AddAPhoto, R.string.rs_scanner_tool_add, R.string.rs_scanner_intro_add_text),
    Item(Icons.Outlined.Crop, R.string.rs_scanner_tool_crop, R.string.rs_scanner_intro_crop_text),
    Item(Icons.Filled.RotateRight, R.string.rs_scanner_tool_rotate, R.string.rs_scanner_intro_rotate_text),
    Item(Icons.Outlined.AutoFixNormal, R.string.rs_scanner_tool_erase, R.string.rs_scanner_intro_erase_text),
    Item(Icons.Outlined.AutoFixHigh, R.string.rs_scanner_tool_filters, R.string.rs_scanner_intro_filters_text),
    Item(Icons.Outlined.TextSnippet, R.string.rs_scanner_tool_text, R.string.rs_scanner_intro_text_text),
    Item(Icons.Outlined.Delete, R.string.rs_scanner_tool_delete, R.string.rs_scanner_intro_delete_text),
    Item(Icons.Outlined.Description, R.string.rs_scanner_intro_save, R.string.rs_scanner_intro_save_text),
)

/**
 * Three-page tour of the scanner: camera controls, scan modes, editing and saving. Each page is
 * a drawn mockup of the real screen with numbered badges, explained in the list below it.
 */
@Composable
internal fun IntroScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    BackHandler { onDone() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20B0F14))
            .statusBarsPadding()
            .navigationBarsPadding()
            // Swallow taps so nothing under the tour (shutter, mode chips) is pressed.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.rs_scanner_intro_title),
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 12.dp),
            )
            TextButton(onClick = onDone) { Text(stringResource(R.string.rs_scanner_intro_skip), color = ScanColors.TextDim) }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> IntroPage(R.string.rs_scanner_intro_camera_title, cameraItems) { CameraMock() }
                1 -> IntroPage(R.string.rs_scanner_intro_modes_title, modeItems) { ModesMock() }
                else -> IntroPage(R.string.rs_scanner_intro_edit_title, editItems) { ReviewMock() }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                repeat(3) { i ->
                    Box(
                        Modifier
                            .size(width = if (i == pager.currentPage) 22.dp else 8.dp, height = 8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (i == pager.currentPage) ScanColors.Gradient else androidx.compose.ui.graphics.SolidColor(Color.White.copy(alpha = 0.3f))),
                    )
                }
            }
            val last = pager.currentPage == 2
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ScanColors.Gradient)
                    .clickable { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text(
                    stringResource(if (last) R.string.rs_scanner_intro_start else R.string.rs_scanner_intro_next),
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun IntroPage(@StringRes title: Int, items: List<Item>, mock: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(title), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        mock()
        Spacer(Modifier.height(14.dp))
        items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Badge(i + 1)
                Spacer(Modifier.width(10.dp))
                Icon(item.icon, null, tint = ScanColors.AccentBright, modifier = Modifier.size(20.dp).padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(item.title), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(item.text), color = ScanColors.TextDim, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Badge(n: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(20.dp).clip(CircleShape).background(ScanColors.Gradient),
        contentAlignment = Alignment.Center,
    ) { Text("$n", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

/** Phone-shaped frame for the mockups. */
@Composable
private fun Phone(content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .width(210.dp)
            .aspectRatio(0.56f)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF1A2027))
            .border(3.dp, Color(0xFF39424D), RoundedCornerShape(22.dp))
            .padding(6.dp),
        content = content,
    )
}

@Composable
private fun MockButton(icon: ImageVector, n: Int, modifier: Modifier = Modifier, size: Int = 26, filled: Boolean = false) {
    Box(modifier) {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (filled) ScanColors.Gradient else androidx.compose.ui.graphics.SolidColor(Color(0xFF2A323B))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp)) }
        Badge(n, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).size(16.dp))
    }
}

@Composable
private fun CameraMock() {
    Phone {
        // Viewfinder with a detected page.
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF3B3F45))) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(y = (-14).dp)
                    .size(width = 96.dp, height = 132.dp)
                    .background(Color(0xFFEDEDED))
                    .border(2.dp, ScanColors.AccentBright),
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(9) { Box(Modifier.fillMaxWidth(if (it % 3 == 2) 0.6f else 1f).height(3.dp).background(Color(0xFF9AA0A6))) }
                }
            }
        }
        // Tool rail: flash, auto, HD, smart, frame.
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MockButton(Icons.Filled.FlashAuto, 1, size = 22)
            MockButton(Icons.Filled.CameraAlt, 2, size = 22)
            MockButton(Icons.Filled.CheckBox, 3, size = 22)
            MockButton(Icons.Filled.AutoAwesome, 4, size = 22)
            MockButton(Icons.Outlined.CropPortrait, 5, size = 22)
        }
        // Zoom chips.
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC101418)).padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("1x", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text("2x", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
            Badge(6, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp).size(16.dp))
        }
        // Bottom bar: pages, shutter, gallery.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                .background(ScanColors.Bar).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MockButton(Icons.Outlined.Description, 7, size = 28)
            MockButton(Icons.Filled.CameraAlt, 8, size = 38, filled = true)
            MockButton(Icons.Outlined.PhotoLibrary, 9, size = 28)
        }
    }
}

@Composable
private fun ModesMock() {
    Phone {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF3B3F45)))
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            modeItems.chunked(2).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEachIndexed { c, item ->
                        val n = r * 2 + c + 1
                        Box {
                            Row(
                                Modifier
                                    .width(78.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (n == 1) ScanColors.Gradient else androidx.compose.ui.graphics.SolidColor(Color(0xFF2A323B)))
                                    .padding(horizontal = 6.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(item.title), color = Color.White, fontSize = 9.sp, maxLines = 1)
                            }
                            Badge(n, Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp).size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewMock() {
    Phone {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF101418)))
        // Save button.
        Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(ScanColors.Gradient).padding(horizontal = 10.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.rs_scanner_intro_save), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            Badge(8, Modifier.align(Alignment.TopStart).offset(x = (-8).dp, y = (-6).dp).size(16.dp))
        }
        // Page.
        Box(
            Modifier.align(Alignment.Center).offset(y = (-10).dp).size(width = 110.dp, height = 152.dp).background(Color.White),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(11) { Box(Modifier.fillMaxWidth(if (it % 4 == 3) 0.5f else 1f).height(3.dp).background(Color(0xFF3C4043))) }
            }
        }
        // Tool bar.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            editItems.take(7).forEachIndexed { i, item -> MockButton(item.icon, i + 1, size = 20) }
        }
    }
}
