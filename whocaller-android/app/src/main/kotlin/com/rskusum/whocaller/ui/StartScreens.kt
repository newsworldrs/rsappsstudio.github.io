package com.rskusum.whocaller.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rskusum.whocaller.R
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import kotlinx.coroutines.launch

/** Branded start screen: "WhoCaller — Know who's calling. Stay protected." */
@Composable
fun SplashContent() {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_splash_logo), contentDescription = null, modifier = Modifier.size(120.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.splash_tagline), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Text(
            stringResource(R.string.developer_name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        )
    }
}

private data class OnboardingPage(val icon: ImageVector, val title: Int, val body: Int)

private val PAGES = listOf(
    OnboardingPage(Icons.Outlined.PhoneInTalk, R.string.onboarding_1_title, R.string.onboarding_1_body),
    OnboardingPage(Icons.Outlined.Block, R.string.onboarding_2_title, R.string.onboarding_2_body),
    OnboardingPage(Icons.Outlined.Search, R.string.onboarding_3_title, R.string.onboarding_3_body),
    OnboardingPage(Icons.Outlined.Lock, R.string.onboarding_4_title, R.string.onboarding_4_body),
)

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES.lastIndex
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = onFinished) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val page = PAGES[index]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(140.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(page.icon, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    stringResource(page.title),
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(page.body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }
        val pageDescription = stringResource(R.string.onboarding_page, pager.currentPage + 1, PAGES.size)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp).semantics(mergeDescendants = true) { contentDescription = pageDescription },
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(PAGES.size) { i ->
                Box(
                    Modifier
                        .padding(4.dp)
                        .size(if (i == pager.currentPage) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Button(
            onClick = { if (last) onFinished() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(if (last) R.string.onboarding_get_started else R.string.onboarding_continue))
        }
    }
}

@Preview
@Composable
private fun OnboardingPreview() {
    WhoCallerTheme { OnboardingScreen(onFinished = {}) }
}

@Preview
@Composable
private fun SplashPreview() {
    WhoCallerTheme { SplashContent() }
}
