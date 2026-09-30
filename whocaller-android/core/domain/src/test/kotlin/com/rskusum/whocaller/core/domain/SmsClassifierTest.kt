package com.rskusum.whocaller.core.domain

import com.rskusum.whocaller.core.domain.sms.SmsClassifier
import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.model.SmsSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsClassifierTest {
    private val classifier = SmsClassifier()

    @Test
    fun `phishing message is flagged as scam with reasons`() {
        val c = classifier.classify(
            "+919000000000",
            "URGENT: Your account will be blocked today. Update your KYC at http://bit.ly/abc123 immediately",
        )
        assertEquals(SmsCategory.SCAM, c.category)
        assertTrue(SmsSignal.SHORTENED_LINK in c.signals)
        assertTrue(SmsSignal.ACCOUNT_THREAT in c.signals)
        assertTrue(c.riskScore >= 50)
    }

    @Test
    fun `bank otp is a transaction`() {
        val c = classifier.classify("VM-EXBANK", "123456 is your OTP for login. Do not share it with anyone.")
        assertEquals(SmsCategory.TRANSACTIONS, c.category)
    }

    @Test
    fun `promotion from a brand`() {
        val c = classifier.classify("AD-SHOPEX", "Big sale! Flat 50% off on shoes this weekend.")
        assertEquals(SmsCategory.PROMOTIONS, c.category)
    }

    @Test
    fun `plain message from a person is personal`() {
        val c = classifier.classify("+919812345678", "See you at 6?")
        assertEquals(SmsCategory.PERSONAL, c.category)
        assertEquals(0, c.riskScore)
    }

    @Test
    fun `blocked sender is spam`() {
        assertEquals(SmsCategory.SPAM, classifier.classify("+919812345678", "hello", senderBlocked = true).category)
    }
}
