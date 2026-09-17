package com.honeynotify

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test

class HoneyNotifyTest {
    @Test
    fun exceptionIncludesStatusCode() {
        val exception = HoneyNotifyException(429, "Rate limited")

        assertEquals("Rate limited (429)", exception.message)
        assertEquals(429, exception.status)
    }

    @Test
    fun interruptionLevelsMapToStableChannels() {
        assertEquals(HoneyNotifyInterruptionLevel.ACTIVE, HoneyNotifyInterruptionLevel.fromWireValue(null))
        assertEquals(HoneyNotifyInterruptionLevel.TIME_SENSITIVE, HoneyNotifyInterruptionLevel.fromWireValue("time_sensitive"))
        assertEquals(HoneyNotifyChannels.CRITICAL, HoneyNotifyChannels.forLevel(HoneyNotifyInterruptionLevel.CRITICAL))
        assertEquals(HoneyNotifyChannels.PASSIVE, HoneyNotifyChannels.forLevel(HoneyNotifyInterruptionLevel.PASSIVE))
        assertEquals(NotificationManager.IMPORTANCE_LOW, HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.PASSIVE))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.ACTIVE))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.TIME_SENSITIVE))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.CRITICAL))
    }

    @Test
    fun payloadParsingDefaultsAndPreservesCustomChannelOverrides() {
        val defaultNotification = HoneyNotifyNotification.from(emptyMap())
        val overriddenNotification = HoneyNotifyNotification.from(
            mapOf(
                "honeynotify_interruption_level" to "critical",
                "honeynotify_android_channel_id" to "alarms"
            )
        )

        assertEquals(HoneyNotifyInterruptionLevel.ACTIVE, defaultNotification.interruptionLevel)
        assertEquals(HoneyNotifyChannels.ACTIVE, defaultNotification.channelId)
        assertEquals(HoneyNotifyInterruptionLevel.CRITICAL, overriddenNotification.interruptionLevel)
        assertEquals("alarms", overriddenNotification.channelId)
    }
}
