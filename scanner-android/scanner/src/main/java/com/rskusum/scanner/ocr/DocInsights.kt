package com.rskusum.scanner.ocr

import android.content.Context
import androidx.annotation.StringRes
import com.rskusum.scanner.R

/** One useful detail found in the text. */
data class Insight(val kind: Kind, val value: String) {
    enum class Kind(@StringRes val labelRes: Int) {
        LINK(R.string.rs_scanner_insight_link), EMAIL(R.string.rs_scanner_insight_email),
        PHONE(R.string.rs_scanner_insight_phone), DATE(R.string.rs_scanner_insight_date),
        AMOUNT(R.string.rs_scanner_insight_amount), PAN(R.string.rs_scanner_insight_pan),
        GSTIN(R.string.rs_scanner_insight_gstin), PINCODE(R.string.rs_scanner_insight_pincode),
    }
}

/** Document types [TextInsights] can recognise. */
enum class DocType(@StringRes val labelRes: Int, internal val keys: List<String>) {
    INVOICE(R.string.rs_scanner_doc_invoice, listOf("invoice", "bill no", "gstin", "gst", "tax", "subtotal", "sub total", "grand total", "amount due", "hsn", "qty", "rate")),
    RECEIPT(R.string.rs_scanner_doc_receipt, listOf("receipt", "paid", "cash", "change", "thank you", "txn", "transaction", "upi", "card no")),
    BANK_STATEMENT(R.string.rs_scanner_doc_bank_statement, listOf("statement", "account no", "a/c", "balance", "debit", "credit", "ifsc", "branch", "opening balance")),
    ID_DOCUMENT(R.string.rs_scanner_doc_id, listOf("government of india", "date of birth", "dob", "aadhaar", "passport", "licence", "license", "voter", "permanent account number", "identity")),
    RESUME(R.string.rs_scanner_doc_resume, listOf("resume", "curriculum vitae", "experience", "education", "skills", "objective", "projects", "certifications")),
    LETTER(R.string.rs_scanner_doc_letter, listOf("dear", "sincerely", "regards", "subject:", "yours", "to,", "respected")),
    EXAM(R.string.rs_scanner_doc_exam, listOf("marks", "question", "answer", "section", "time allowed", "attempt", "maximum marks")),
    PRESCRIPTION(R.string.rs_scanner_doc_prescription, listOf("rx", "tab", "tablet", "capsule", "mg", "dosage", "twice", "daily", "dr.", "patient")),
    FORM(R.string.rs_scanner_doc_form, listOf("name:", "address:", "signature", "date:", "applicant", "please fill", "father's name")),
    BOOK_PAGE(R.string.rs_scanner_doc_book_page, listOf("chapter", "exercise", "textbook", "lesson", "page", "unit", "ncert")),
    BUSINESS_CARD(R.string.rs_scanner_doc_business_card, listOf("www.", "mobile", "tel", "director", "manager", "ceo", "founder")),
    DOCUMENT(R.string.rs_scanner_doc_document, emptyList());

    fun label(context: Context): String = context.getString(labelRes)
}

/**
 * What a page is about, worked out on the device from its text.
 * @param type best guess of the document type ([DocType.DOCUMENT] when unsure).
 * @param items links, e-mails, phone numbers, dates, amounts, PAN / GSTIN numbers, PIN codes.
 * @param words / [lines] simple counts.
 */
data class DocInsights(val type: DocType, val items: List<Insight>, val words: Int, val lines: Int)

/** Rule-based document understanding: runs instantly and offline on the OCR text. */
object TextInsights {


    private val link = Regex("""\b((https?://|www\.)[^\s,;]+|[a-z0-9-]+\.(com|in|org|net|co|io|gov|edu)(/[^\s,;]*)?)\b""", RegexOption.IGNORE_CASE)
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val phone = Regex("""(?<!\d)(\+?\d{1,3}[\s-]?)?(\(?\d{2,5}\)?[\s-]?)?\d{3,5}[\s-]?\d{4,5}(?!\d)""")
    private val date = Regex(
        """\b(\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}|\d{4}-\d{2}-\d{2}|\d{1,2}\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?,?\s+\d{2,4})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val amount = Regex("""(₹|rs\.?|inr|\$|€|£)\s?\d[\d,]*(\.\d{1,2})?|\d[\d,]*\.\d{2}\s?(₹|rs|inr)""", RegexOption.IGNORE_CASE)
    private val pan = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val gstin = Regex("""\b\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b""")
    private val pincode = Regex("""(?i)(pin|pincode|pin code)[:\s-]*(\d{6})\b""")

    fun analyze(text: String): DocInsights {
        val lower = text.lowercase()
        val items = LinkedHashMap<String, Insight>()
        fun add(kind: Insight.Kind, v: String) {
            val value = v.trim().trimEnd('.', ',', ')')
            if (value.length >= 3) items.putIfAbsent("${kind.name}:$value", Insight(kind, value))
        }
        email.findAll(text).forEach { add(Insight.Kind.EMAIL, it.value) }
        val emails = items.values.map { it.value }
        link.findAll(text).forEach { m -> if (emails.none { it.contains(m.value) }) add(Insight.Kind.LINK, m.value) }
        gstin.findAll(text).forEach { add(Insight.Kind.GSTIN, it.value) }
        pan.findAll(text).forEach { m -> if (items.values.none { it.kind == Insight.Kind.GSTIN && it.value.contains(m.value) }) add(Insight.Kind.PAN, m.value) }
        date.findAll(text).forEach { add(Insight.Kind.DATE, it.value) }
        amount.findAll(text).forEach { add(Insight.Kind.AMOUNT, it.value) }
        phone.findAll(text).forEach { m ->
            val digits = m.value.count { it.isDigit() }
            // 10-13 digits = a phone number (not a date, an amount or an ID number)
            if (digits in 10..13 && items.values.none { it.value.contains(m.value.trim()) }) add(Insight.Kind.PHONE, m.value)
        }
        pincode.findAll(text).forEach { add(Insight.Kind.PINCODE, it.groupValues[2]) }

        val scored = DocType.entries.filter { it.keys.isNotEmpty() }
            .map { t -> t to t.keys.count { lower.contains(it) } }.maxBy { it.second }
        val type = if (scored.second >= 2) scored.first else DocType.DOCUMENT
        val lines = text.lines().count { it.isNotBlank() }
        val words = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }
        return DocInsights(type, items.values.take(40), words, lines)
    }
}
