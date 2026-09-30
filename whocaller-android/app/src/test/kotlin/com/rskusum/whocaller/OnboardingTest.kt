package com.rskusum.whocaller

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.ui.theme.WhoCallerTheme
import com.rskusum.whocaller.ui.OnboardingScreen
import com.rskusum.whocaller.ui.SplashContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnboardingTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun walksThroughAllPagesToGetStarted() {
        var finished = false
        compose.setContent { WhoCallerTheme { OnboardingScreen(onFinished = { finished = true }) } }

        compose.onNodeWithText("Know who's calling").assertExists()
        repeat(3) {
            compose.onNodeWithText("Continue").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Your privacy matters").assertExists()
        compose.onNodeWithText("Get Started").performClick()
        assertTrue(finished)
    }

    @Test
    fun skipFinishesImmediately() {
        var finished = false
        compose.setContent { WhoCallerTheme { OnboardingScreen(onFinished = { finished = true }) } }
        compose.onNodeWithText("Skip").performClick()
        assertTrue(finished)
    }

    @Test
    fun splashShowsBrand() {
        compose.setContent { WhoCallerTheme { SplashContent() } }
        compose.onNodeWithText("WhoCaller").assertExists()
        compose.onNodeWithText("RS APPS STUDIO").assertExists()
    }
}
