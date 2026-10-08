package com.rskusum.whocaller.core.common

import com.rskusum.whocaller.core.common.phone.NormalizationResult
import com.rskusum.whocaller.core.common.phone.PhoneNumberNormalizer
import com.rskusum.whocaller.core.model.NumberType
import com.rskusum.whocaller.core.model.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberNormalizerTest {

    private val normalizer = PhoneNumberNormalizer()

    private fun key(input: String?, region: String? = "IN") = normalizer.keyOf(input, region)

    @Test
    fun `indian number variants collapse to one E164 key`() {
        val expected = "+919876543210"
        assertEquals(expected, key("+919876543210"))
        assertEquals(expected, key("919876543210"))
        assertEquals(expected, key("09876543210"))
        assertEquals(expected, key("9876543210"))
        assertEquals(expected, key("+91 98765 43210"))
        assertEquals(expected, key("(+91) 98765-43210"))
        assertEquals(expected, key("0091 98765 43210"))
    }

    @Test
    fun `country code without plus is detected even for a foreign default region`() {
        assertEquals("+919876543210", key("919876543210", region = "US"))
    }

    @Test
    fun `north american numbers`() {
        assertEquals("+16502530000", key("+1 650-253-0000", region = "IN"))
        assertEquals("+16502530000", key("(650) 253-0000", region = "US"))
        assertEquals("+16502530000", key("1 650 253 0000", region = "US"))
    }

    @Test
    fun `international number keeps its own country regardless of default region`() {
        val r = normalizer.normalize("+44 20 7946 0321", "IN") as NormalizationResult.Parsed
        assertEquals("+442079460321", r.number.key)
        assertEquals("GB", r.number.regionCode)
        assertEquals(44, r.number.countryCallingCode)
    }

    @Test
    fun `parsed number exposes metadata`() {
        val n = (normalizer.normalize("9876543210", "IN") as NormalizationResult.Parsed).number
        assertTrue(n.isValid)
        assertEquals("IN", n.regionCode)
        assertEquals(91, n.countryCallingCode)
        assertEquals(NumberType.MOBILE, n.type)
        assertEquals("+91 98765 43210", n.internationalFormat)
    }

    @Test
    fun `private and hidden numbers`() {
        listOf(null, "", "   ", "-1", "-2", "PRIVATE", "Unknown", "Restricted", "anonymous").forEach {
            assertEquals("input=$it", NormalizationResult.Hidden, normalizer.normalize(it, "IN"))
        }
    }

    @Test
    fun `invalid input`() {
        assertEquals(
            NormalizationResult.Invalid(NormalizationResult.Reason.NOT_A_NUMBER),
            normalizer.normalize("hello", "IN"),
        )
        assertEquals(NormalizationResult.Invalid(NormalizationResult.Reason.TOO_SHORT), normalizer.normalize("12", "IN"))
        assertEquals(
            NormalizationResult.Invalid(NormalizationResult.Reason.TOO_LONG),
            normalizer.normalize("123456789012345678901", "IN"),
        )
    }

    @Test
    fun `short codes get a raw key that cannot collide with E164`() {
        val n = (normalizer.normalize("121", "IN") as NormalizationResult.Parsed).number
        assertEquals(PhoneNumber.RAW_PREFIX + "121", n.key)
        assertFalse(n.isValid)
        assertNull(n.e164)
    }

    @Test
    fun `unknown default region still parses international input`() {
        assertEquals("+919876543210", key("+919876543210", region = null))
        assertEquals("+919876543210", key("+919876543210", region = "XX"))
    }

    @Test
    fun `clean strips formatting and keeps leading plus only`() {
        assertEquals("+919876543210", PhoneNumberNormalizer.clean(" +91 (98765) 43-210 "))
        assertEquals("12345", PhoneNumberNormalizer.clean("1+2.3-4 5"))
    }

    @Test
    fun `non ascii digits are normalized`() {
        // Arabic-Indic digits for 9876543210
        assertEquals("+919876543210", key("٩٨٧٦٥٤٣٢١٠"))
    }
}
