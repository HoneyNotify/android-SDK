package com.honeynotify

import org.junit.Assert.assertEquals
import org.junit.Test

class HoneyNotifyTest {
    @Test
    fun exceptionIncludesStatusCode() {
        val exception = HoneyNotifyException(429, "Rate limited")

        assertEquals("Rate limited (429)", exception.message)
        assertEquals(429, exception.status)
    }
}
