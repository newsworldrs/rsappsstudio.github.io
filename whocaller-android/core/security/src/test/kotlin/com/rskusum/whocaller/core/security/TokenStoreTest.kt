package com.rskusum.whocaller.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenStoreTest {
    private class MemoryStorage : SecureStorage {
        val map = mutableMapOf<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
        override fun clear() = map.clear()
    }

    @Test
    fun tokenExpires() {
        val store = TokenStore(MemoryStorage())
        store.save("abc", expiresAtMillis = 1_000_000)
        assertEquals("abc", store.validToken(nowMillis = 100_000))
        assertNull(store.validToken(nowMillis = 999_000))
        assertNull(store.validToken(nowMillis = 100_000)) // cleared after expiry
    }

    @Test
    fun missingTokenIsNull() {
        assertNull(TokenStore(MemoryStorage()).validToken(0))
    }
}
