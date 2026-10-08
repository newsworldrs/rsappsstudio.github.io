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

    @Test
    fun `lottery scam from a phone number is caught by the learned model`() {
        val c = classifier.classify(
            "+919876543210",
            "Congratulations! You have won Rs 25,00,000 in KBC lucky draw. Call 9876543210 to claim your prize now",
        )
        assertTrue(SmsSignal.SPAM_WORDING in c.signals)
        assertTrue(c.category == SmsCategory.SPAM || c.category == SmsCategory.SCAM)
    }

    @Test
    fun `bank alerts are never judged by the learned model`() {
        val debit = classifier.classify(
            "VM-HDFCBK",
            "Rs.2,500.00 debited from A/c XX1234 to VPA swiggy@icici. Avl Bal Rs.10,234.50. Not you? Call 18002586161",
        )
        assertTrue(SmsSignal.SPAM_WORDING !in debit.signals)
        assertEquals(SmsCategory.TRANSACTIONS, debit.category)
        val otp = classifier.classify("+919812345678", "123456 is your OTP for login. Do not share it with anyone.")
        assertTrue(SmsSignal.SPAM_WORDING !in otp.signals)
    }

    @Test
    fun `model file is bundled and tokenised like the training script`() {
        assertTrue(com.rskusum.whocaller.core.domain.sms.SpamTextModel.bundled != null)
        assertEquals(
            setOf("win", "zzmoney", "zznum", "call", "zzlongnum", "zzurl"),
            com.rskusum.whocaller.core.domain.sms.SpamTextModel.tokens("WIN Rs 500! Call 08452810075 www.example.com"),
        )
    }
}

class SmsSenderContextTest {
    private val classifier = SmsClassifier()
    private val lottery = "Congratulations! You have won Rs 25,00,000 in KBC lucky draw. Call 9876543210 to claim your prize now"

    @Test
    fun `contacts are not judged by wording alone`() {
        val c = classifier.classify("+919876543210", lottery, senderIsContact = true)
        assertTrue(SmsSignal.SPAM_WORDING !in c.signals)
        assertTrue(c.category != SmsCategory.SPAM)
    }

    @Test
    fun `not spam wins over everything except a block`() {
        assertEquals(SmsCategory.PERSONAL, classifier.classify("+919876543210", lottery, senderTrusted = true).category)
        assertEquals(SmsCategory.SPAM, classifier.classify("+919876543210", "hi", senderTrusted = true, senderBlocked = true).category)
    }

    @Test
    fun `community spam data flags the sender`() {
        assertEquals(SmsCategory.SPAM, classifier.classify("+919876543210", "Hello sir", senderReported = true).category)
    }
}
