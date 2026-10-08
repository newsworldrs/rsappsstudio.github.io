package com.rskusum.whocaller.core.domain.sms

import com.rskusum.whocaller.core.model.SmsCategory
import com.rskusum.whocaller.core.model.SmsClassification
import com.rskusum.whocaller.core.model.SmsSignal
import java.util.Locale
import javax.inject.Inject

/**
 * On-device, rule-based SMS classifier. Message text never leaves the device.
 *
 * It explains *why* a message looks suspicious ([SmsSignal]) instead of issuing a verdict, and only
 * uses SCAM when several independent phishing indicators appear together.
 */
class SmsClassifier @Inject constructor() {


    fun classify(
        sender: String,
        body: String,
        senderBlocked: Boolean = false,
        senderReported: Boolean = false,
        /** Sender is a saved contact: never judged by wording alone. */
        senderIsContact: Boolean = false,
        /** User said "Not spam" for this sender: never flagged (unless they also blocked it). */
        senderTrusted: Boolean = false,
    ): SmsClassification {
        val text = body.lowercase(Locale.ROOT)
        val signals = mutableSetOf<SmsSignal>()

        val links = URL_REGEX.findAll(text).map { it.value }.toList()
        if (links.isNotEmpty()) signals += SmsSignal.CONTAINS_LINK
        if (links.any { link -> SHORTENERS.any { link.contains(it) } } || links.any { IP_LINK.containsMatchIn(it) }) {
            signals += SmsSignal.SHORTENED_LINK
        }
        if (URGENCY.any { text.contains(it) }) signals += SmsSignal.URGENCY
        if (PRIZE.any { text.contains(it) }) signals += SmsSignal.PRIZE_OR_LOTTERY
        if (ACCOUNT_THREAT.any { text.contains(it) }) signals += SmsSignal.ACCOUNT_THREAT
        if (CREDENTIALS.any { text.contains(it) }) signals += SmsSignal.CREDENTIAL_REQUEST
        if (PAYMENT.any { text.contains(it) }) signals += SmsSignal.PAYMENT_REQUEST
        if (OTP_REGEX.containsMatchIn(text)) signals += SmsSignal.OTP
        if (TRANSACTION.any { text.contains(it) }) signals += SmsSignal.TRANSACTION
        if (PROMOTION.any { text.contains(it) }) signals += SmsSignal.PROMOTION
        if (senderBlocked) signals += SmsSignal.SENDER_BLOCKED
        if (senderReported) signals += SmsSignal.SENDER_REPORTED

        // Learned wording model. Only for messages from ordinary phone numbers: in India real businesses
        // must send from registered alphanumeric senders (e.g. VM-HDFCBK), and the training data (UK
        // messages) would otherwise mistake genuine OTPs and bank alerts for spam.
        val fromPhoneNumber = sender.none { it.isLetter() } && sender.count { it.isDigit() } >= 7
        val transactional = SmsSignal.OTP in signals || SmsSignal.TRANSACTION in signals
        val judgeWording = fromPhoneNumber && !transactional && !senderIsContact && !senderTrusted
        val spamProbability = if (judgeWording) SpamTextModel.active?.spamProbability(body) ?: 0.0 else 0.0
        if (spamProbability >= SpamTextModel.THRESHOLD) signals += SmsSignal.SPAM_WORDING

        val phishingIndicators = listOf(
            SmsSignal.SHORTENED_LINK, SmsSignal.URGENCY, SmsSignal.PRIZE_OR_LOTTERY,
            SmsSignal.ACCOUNT_THREAT, SmsSignal.CREDENTIAL_REQUEST, SmsSignal.PAYMENT_REQUEST,
        ).count { it in signals }
        val hasLink = SmsSignal.CONTAINS_LINK in signals

        var risk = phishingIndicators * 18 + (if (hasLink) 10 else 0)
        if (senderReported) risk += 25
        if (senderBlocked) risk += 30
        if (SmsSignal.SPAM_WORDING in signals) risk += if (spamProbability >= SpamTextModel.STRONG) 50 else 30
        // A genuine OTP usually tells you NOT to share it; asking for it back is a red flag.
        if (SmsSignal.OTP in signals && SmsSignal.CREDENTIAL_REQUEST !in signals) risk -= 10
        risk = risk.coerceIn(0, 100)

        val isAlphaSender = sender.any { it.isLetter() }
        if (senderTrusted && !senderBlocked) {
            // The user's own "Not spam" always wins.
            val category = when {
                SmsSignal.OTP in signals || SmsSignal.TRANSACTION in signals -> SmsCategory.TRANSACTIONS
                SmsSignal.PROMOTION in signals -> SmsCategory.PROMOTIONS
                else -> SmsCategory.PERSONAL
            }
            return SmsClassification(category = category, signals = signals - SmsSignal.SPAM_WORDING, riskScore = 0)
        }
        val category = when {
            phishingIndicators >= 2 && (hasLink || SmsSignal.CREDENTIAL_REQUEST in signals) -> SmsCategory.SCAM
            senderBlocked || senderReported -> SmsCategory.SPAM
            // A saved contact needs clear phishing (handled above) before it's treated as spam.
            !senderIsContact && risk >= 50 -> SmsCategory.SPAM
            SmsSignal.OTP in signals || SmsSignal.TRANSACTION in signals -> SmsCategory.TRANSACTIONS
            SmsSignal.PROMOTION in signals -> SmsCategory.PROMOTIONS
            !isAlphaSender && sender.any { it.isDigit() } -> SmsCategory.PERSONAL
            else -> SmsCategory.UNKNOWN
        }
        return SmsClassification(category = category, signals = signals, riskScore = risk)
    }

    private companion object {
        val URL_REGEX = Regex("""(https?://\S+|www\.\S+|\b[a-z0-9-]+\.(?:ly|gl|link|xyz|top|click|info|online|site|in|com|co|me|io)(?:/\S*)?)""")
        val IP_LINK = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
        val OTP_REGEX = Regex("""\b(otp|one[- ]time password|verification code|code is)\b""")
        val SHORTENERS = listOf("bit.ly", "tinyurl", "t.co/", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "shorturl", "tiny.cc")
        val URGENCY = listOf(
            "urgent", "immediately", "within 24 hours", "last chance", "act now", "expire", "expires today",
            "final notice", "turant", "तुरंत",
        )
        val PRIZE = listOf("you have won", "you've won", "lottery", "jackpot", "prize", "reward points expire", "lucky draw", "cashback of rs")
        val ACCOUNT_THREAT = listOf(
            "account will be blocked", "account has been blocked", "account suspended", "kyc", "pan card",
            "electricity will be disconnected", "power will be disconnected", "sim will be blocked", "deactivated",
        )
        val CREDENTIALS = listOf(
            "share otp", "share your otp", "send otp", "enter your pin", "cvv", "password", "login to verify",
            "verify your account", "update your details", "netbanking id",
        )
        val PAYMENT = listOf("pay now", "processing fee", "registration fee", "upi pin", "send money", "gift card")
        val TRANSACTION = listOf(
            "debited", "credited", "a/c", "acct", "txn", "transaction", "balance", "order", "delivered", "shipped",
            "booking", "pnr", "invoice", "bill of rs", "payment of", "received rs",
        )
        val PROMOTION = listOf("% off", "sale", "offer", "discount", "coupon", "shop now", "deal", "subscribe", "free trial")
    }
}
