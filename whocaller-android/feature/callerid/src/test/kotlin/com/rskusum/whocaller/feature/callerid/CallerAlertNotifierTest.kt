package com.rskusum.whocaller.feature.callerid

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.model.CallDecision
import com.rskusum.whocaller.core.model.CallerInfo
import com.rskusum.whocaller.core.model.CallerLabel
import com.rskusum.whocaller.core.model.CallerResult
import com.rskusum.whocaller.core.model.DecisionReason
import com.rskusum.whocaller.core.model.IdentityType
import com.rskusum.whocaller.core.model.NotificationCategory
import com.rskusum.whocaller.core.model.SpamCategory
import com.rskusum.whocaller.core.model.SpamScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CallerAlertNotifierTest {

    private val notifier = CallerAlertNotifier(ApplicationProvider.getApplicationContext<Context>())
    private val number = (PhoneNumberNormalizer().normalize("+919876543210", "IN") as NormalizationResult.Parsed).number

    private fun result(label: CallerLabel, info: CallerInfo?, score: SpamScore, decision: CallDecision = CallDecision.ALLOW, reason: DecisionReason = DecisionReason.NONE) =
        CallerResult(number, null, info, score, decision, reason, label)

    @Test
    fun verifiedBusiness() {
        val info = CallerInfo(number.key, "ABC Internet Services", IdentityType.BUSINESS, SpamCategory.BUSINESS, verified = true)
        val alert = notifier.buildAlert(result(CallerLabel.VERIFIED_BUSINESS, info, SpamScore(2, SpamCategory.BUSINESS, 0.9f, 1)))!!
        assertEquals(NotificationCategory.CALLER_ALERTS, alert.category)
        assertEquals("Incoming call from ABC Internet Services", alert.title)
        assertTrue(alert.text.contains("Verified Business"))
    }

    @Test
    fun suspectedSpamShowsCategoryAndReports() {
        val info = CallerInfo(number.key, reportCount = 84, category = SpamCategory.TELEMARKETING)
        val alert = notifier.buildAlert(result(CallerLabel.TELEMARKETING, info, SpamScore(70, SpamCategory.TELEMARKETING, 0.8f, 84)))!!
        assertEquals("Incoming call from +91 98765 43210", alert.title)
        assertEquals("⚠ Suspected Spam · Telemarketing · Reported by 84 users", alert.text)
        assertEquals(NotificationCategory.SPAM_ALERTS, alert.category)
    }

    @Test
    fun possibleScam() {
        val alert = notifier.buildAlert(result(CallerLabel.POSSIBLE_SCAM, null, SpamScore(90, SpamCategory.SCAM, 0.9f, 50)))!!
        assertEquals("Incoming call from +91 98765 43210", alert.title)
        assertTrue(alert.text.startsWith("⚠ Possible Scam"))
    }

    @Test
    fun blockedCall() {
        val alert = notifier.buildAlert(
            result(CallerLabel.SUSPECTED_SPAM, null, SpamScore(90, SpamCategory.SPAM, 1f, 1), CallDecision.BLOCK, DecisionReason.USER_BLOCK_LIST),
        )!!
        assertEquals("Spam call blocked", alert.title)
    }

    @Test
    fun unknownCallerShowsTheNumberBeforeItRings() {
        val alert = notifier.buildAlert(result(CallerLabel.UNKNOWN, null, SpamScore.NONE))!!
        assertEquals("Incoming call from +91 98765 43210", alert.title)
        assertEquals("Not in your contacts · no reports", alert.text)
    }
}
