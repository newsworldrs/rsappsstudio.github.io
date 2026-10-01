package com.rskusum.whocaller.core.common

import com.rskusum.whocaller.core.common.phone.IndianCallSeries
import com.rskusum.whocaller.core.common.phone.IndianCallSeries.Series
import com.rskusum.whocaller.core.model.SpamCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndianCallSeriesTest {

    @Test
    fun `recognises the 140 and 1600 series in any format`() {
        assertEquals(Series.PROMOTIONAL_140, IndianCallSeries.of("+911401234567"))
        assertEquals(Series.PROMOTIONAL_140, IndianCallSeries.of("01401234567"))
        assertEquals(Series.PROMOTIONAL_140, IndianCallSeries.of("1401234567"))
        assertEquals(Series.SERVICE_1600, IndianCallSeries.of("+91 1600 123 456"))
        assertEquals(Series.SERVICE_1600, IndianCallSeries.of("911600123456"))
    }

    @Test
    fun `other numbers are not in a series`() {
        assertNull(IndianCallSeries.of("+919876543210"))
        assertNull(IndianCallSeries.of("+11401234567")) // not India
        assertNull(IndianCallSeries.of("140123")) // too short
        assertNull(IndianCallSeries.of(null))
    }

    @Test
    fun `140 is telemarketing, 1600 is a verified service`() {
        assertEquals(SpamCategory.TELEMARKETING, IndianCallSeries.infoFor("k", "+911401234567", 0)?.category)
        val service = IndianCallSeries.infoFor("k", "+911600123456", 0)!!
        assertEquals(SpamCategory.BUSINESS, service.category)
        assertTrue(service.verified)
    }
}
