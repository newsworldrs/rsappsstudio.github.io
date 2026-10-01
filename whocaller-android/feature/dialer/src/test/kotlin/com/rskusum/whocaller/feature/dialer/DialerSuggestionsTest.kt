package com.rskusum.whocaller.feature.dialer

import com.rskusum.whocaller.core.model.Contact
import com.rskusum.whocaller.core.model.ContactPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialerSuggestionsTest {

    private fun contact(id: Long, name: String, number: String) =
        Contact(id, "k$id", name, null, false, listOf(ContactPhone(number, number, null)))

    private val contacts = listOf(
        contact(1, "Asha Verma", "+91 90000 00001"),
        contact(2, "Test Pharmacy", "+1 555 010 0199"),
        contact(3, "Ravi Kumar", "+91 90000 00002"),
    )

    @Test
    fun `t9 maps letters to keypad digits`() {
        assertEquals("2742", DialerViewModel.t9("Asha"))
        assertEquals("7284", DialerViewModel.t9("Ravi"))
    }

    @Test
    fun `matches by T9 name prefix of any word`() {
        assertEquals(listOf("Ravi Kumar"), DialerViewModel.match("728", contacts).map { it.name })
        assertEquals(listOf("Ravi Kumar"), DialerViewModel.match("586", contacts).map { it.name })
    }

    @Test
    fun `matches by number digits and caps results`() {
        assertEquals(listOf("Asha Verma", "Ravi Kumar"), DialerViewModel.match("9000", contacts).map { it.name })
        assertTrue(DialerViewModel.match("5", contacts).isEmpty())
    }
}
