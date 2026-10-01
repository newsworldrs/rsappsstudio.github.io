package com.rskusum.whocaller.core.domain.sms

import java.util.Locale
import kotlin.math.exp

/**
 * Small learned model: logistic regression over which words a message contains. Trained on the
 * SMS Spam Collection (Almeida & Gómez Hidalgo, UCI Machine Learning Repository, CC BY 4.0); on held-out
 * messages it caught ~87 % of spam and flagged ~0.1 % of normal messages at [THRESHOLD].
 * Weights live in resources/whocaller/sms_spam_model.txt ("word weight" lines plus "bias").
 * Runs entirely on the device.
 */
class SpamTextModel internal constructor(private val bias: Double, private val weights: Map<String, Double>) {

    /** Probability (0–1) that [text] is spam, judged by its wording alone. */
    fun spamProbability(text: String): Double {
        val z = bias + tokens(text).sumOf { weights[it] ?: 0.0 }
        return 1.0 / (1.0 + exp(-z))
    }

    companion object {
        const val THRESHOLD = 0.7
        const val STRONG = 0.9

        private val URL = Regex("""(https?://\S+|www\.\S+|\b[a-z0-9-]+\.(?:ly|gl|link|xyz|top|click|info|online|site|in|com|co|uk|me|io)(?:/\S*)?)""")
        private val MONEY = Regex("""[£$€₹]|\brs\.?\s?(?=\d)|\binr\b""")
        private val LONG_NUMBER = Regex("""\d{5,}""")
        private val NUMBER = Regex("""\d+""")
        private val WORD = Regex("""[a-z]{2,}""")

        /** Same preprocessing as the training script (tools/sms-model/train.py). */
        internal fun tokens(text: String): Set<String> {
            var t = text.lowercase(Locale.ROOT)
            t = URL.replace(t, " zzurl ")
            t = MONEY.replace(t, " zzmoney ")
            t = LONG_NUMBER.replace(t, " zzlongnum ")
            t = NUMBER.replace(t, " zznum ")
            return WORD.findAll(t).map { it.value }.toSet()
        }

        /** Parses the model file; returns null if it is missing or malformed (the rules still work). */
        fun parse(lines: Sequence<String>): SpamTextModel? {
            var bias: Double? = null
            val weights = HashMap<String, Double>()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val parts = trimmed.split(' ')
                if (parts.size != 2) continue
                val value = parts[1].toDoubleOrNull() ?: continue
                if (parts[0] == "bias") bias = value else weights[parts[0]] = value
            }
            return bias?.let { SpamTextModel(it, weights) }
        }

        /** Bundled model, loaded once. */
        val bundled: SpamTextModel? by lazy {
            SpamTextModel::class.java.getResourceAsStream("/whocaller/sms_spam_model.txt")
                ?.bufferedReader()
                ?.useLines { parse(it) }
        }
    }
}
