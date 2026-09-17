package com.honeynotify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class HoneyNotify(private val context: Context, private val baseUrl: String, private val clientKey: String) {
    fun createNotificationChannels(labels: HoneyNotifyChannelLabels = HoneyNotifyChannelLabels()) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(
                HoneyNotifyChannels.PASSIVE,
                labels.passive,
                HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.PASSIVE)
            ).apply {
                description = labels.passiveDescription
                enableVibration(false)
                setSound(null, null)
            },
            NotificationChannel(
                HoneyNotifyChannels.ACTIVE,
                labels.active,
                HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.ACTIVE)
            ).apply {
                description = labels.activeDescription
            },
            NotificationChannel(
                HoneyNotifyChannels.TIME_SENSITIVE,
                labels.timeSensitive,
                HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.TIME_SENSITIVE)
            ).apply {
                description = labels.timeSensitiveDescription
                enableVibration(true)
            },
            NotificationChannel(
                HoneyNotifyChannels.CRITICAL,
                labels.critical,
                HoneyNotifyChannels.importanceFor(HoneyNotifyInterruptionLevel.CRITICAL)
            ).apply {
                description = labels.criticalDescription
                enableVibration(true)
            }
        )
        manager.createNotificationChannels(channels)
    }

    fun register(token: String, externalUserId: String? = null, tags: Map<String, String> = emptyMap(), identityToken: String? = null): String {
        val payload = JSONObject().put("platform", "android").put("push_token", token).put("tags", JSONObject(tags))
        externalUserId?.let { payload.put("external_user_id", it) }
        identityToken?.let { payload.put("identity_token", it) }
        payload.put("locale", Locale.getDefault().toLanguageTag())
        payload.put("timezone", TimeZone.getDefault().id)
        payload.put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        payload.put("os_version", Build.VERSION.RELEASE)
        context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let { payload.put("app_version", it) }
        val result = post("/v1/devices/register", payload)
        return result.getString("device_id").also { context.getSharedPreferences("honeynotify", Context.MODE_PRIVATE).edit().putString("device_id", it).putString("push_token", token).putString("external_user_id", externalUserId).putString("tags", JSONObject(tags).toString()).apply() }
    }

    fun onTokenRefresh(token: String): String {
        val preferences = context.getSharedPreferences("honeynotify", Context.MODE_PRIVATE)
        val tagsJson = JSONObject(preferences.getString("tags", "{}") ?: "{}")
        val tags = tagsJson.keys().asSequence().associateWith { tagsJson.getString(it) }
        return register(token, preferences.getString("external_user_id", null), tags)
    }

    fun registerCurrentToken(externalUserId: String? = null, tags: Map<String, String> = emptyMap(), completion: (Result<String>) -> Unit) {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                completion(Result.failure(task.exception ?: HoneyNotifyException("Unable to obtain FCM token")))
                return@addOnCompleteListener
            }
            Thread { completion(runCatching { register(task.result, externalUserId, tags) }) }.start()
        }
    }

    fun identify(externalUserId: String, tags: Map<String, String> = emptyMap(), identityToken: String? = null): String {
        val token = context.getSharedPreferences("honeynotify", Context.MODE_PRIVATE).getString("push_token", null) ?: throw HoneyNotifyException("No FCM token is registered")
        return register(token, externalUserId, tags, identityToken)
    }

    fun identifyAsync(externalUserId: String, tags: Map<String, String> = emptyMap(), identityToken: String? = null, completion: (Result<String>) -> Unit) {
        Thread { completion(runCatching { identify(externalUserId, tags, identityToken) }) }.start()
    }

    fun track(event: String, notificationId: String? = null, metadata: Map<String, String> = emptyMap()) {
        val standardEvents = setOf("received", "opened", "clicked", "dismissed")
        val payload = JSONObject().put("event_type", if (event in standardEvents) event else "custom").put("occurred_at", Instant.now().toString()).put("metadata", JSONObject(metadata))
        if (event !in standardEvents) payload.put("event_name", event)
        notificationId?.let { payload.put("notification_id", it) }
        context.getSharedPreferences("honeynotify", Context.MODE_PRIVATE).getString("device_id", null)?.let { payload.put("device_id", it) }
        post("/v1/events", payload)
    }

    fun logout() {
        val preferences = context.getSharedPreferences("honeynotify", Context.MODE_PRIVATE)
        val id = preferences.getString("device_id", null) ?: return
        request("/v1/devices/$id", "DELETE", null)
        preferences.edit().clear().apply()
    }

    fun logoutAsync(completion: (Result<Unit>) -> Unit) {
        Thread { completion(runCatching { logout() }) }.start()
    }

    fun trackAsync(event: String, notificationId: String? = null, metadata: Map<String, String> = emptyMap(), completion: (Result<Unit>) -> Unit = {}) {
        Thread { completion(runCatching { track(event, notificationId, metadata) }) }.start()
    }

    fun notificationFrom(data: Map<String, String>) = HoneyNotifyNotification.from(data)

    fun trackReceived(data: Map<String, String>) = track("received", notificationFrom(data).id)

    fun trackOpened(data: Map<String, String>, actionId: String? = null) = track(
        if (actionId == null) "opened" else "clicked",
        notificationFrom(data).id,
        actionId?.let { mapOf("action_id" to it) } ?: emptyMap()
    )

    private fun post(path: String, payload: JSONObject) = request(path, "POST", payload)

    private fun request(path: String, method: String, payload: JSONObject?): JSONObject {
        var lastStatus = 0
        var lastBody = ""
        repeat(3) { attempt ->
            val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $clientKey")
            connection.setRequestProperty("Content-Type", "application/json")
            payload?.let { connection.doOutput = true; connection.outputStream.use { stream -> stream.write(it.toString().toByteArray()) } }
            lastStatus = connection.responseCode
            val stream = if (lastStatus in 200..299) connection.inputStream else connection.errorStream
            lastBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (lastStatus in 200..299) return JSONObject(lastBody.ifBlank { "{}" })
            if (lastStatus != 429 && lastStatus < 500) throw HoneyNotifyException(lastStatus, errorMessage(lastBody))
            if (attempt < 2) Thread.sleep(250L * (attempt + 1))
        }
        throw HoneyNotifyException(lastStatus, errorMessage(lastBody))
    }

    private fun errorMessage(body: String): String = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrDefault("Request failed")
}

enum class HoneyNotifyInterruptionLevel(val wireValue: String) {
    PASSIVE("passive"),
    ACTIVE("active"),
    TIME_SENSITIVE("time_sensitive"),
    CRITICAL("critical");

    companion object {
        fun fromWireValue(value: String?): HoneyNotifyInterruptionLevel =
            entries.firstOrNull { it.wireValue == value } ?: ACTIVE
    }
}

object HoneyNotifyChannels {
    const val PASSIVE = "honeynotify_passive"
    const val ACTIVE = "honeynotify_active"
    const val TIME_SENSITIVE = "honeynotify_time_sensitive"
    const val CRITICAL = "honeynotify_critical"

    fun forLevel(level: HoneyNotifyInterruptionLevel): String = when (level) {
        HoneyNotifyInterruptionLevel.PASSIVE -> PASSIVE
        HoneyNotifyInterruptionLevel.ACTIVE -> ACTIVE
        HoneyNotifyInterruptionLevel.TIME_SENSITIVE -> TIME_SENSITIVE
        HoneyNotifyInterruptionLevel.CRITICAL -> CRITICAL
    }

    fun importanceFor(level: HoneyNotifyInterruptionLevel): Int = when (level) {
        HoneyNotifyInterruptionLevel.PASSIVE -> NotificationManager.IMPORTANCE_LOW
        HoneyNotifyInterruptionLevel.ACTIVE -> NotificationManager.IMPORTANCE_DEFAULT
        HoneyNotifyInterruptionLevel.TIME_SENSITIVE,
        HoneyNotifyInterruptionLevel.CRITICAL -> NotificationManager.IMPORTANCE_HIGH
    }
}

data class HoneyNotifyChannelLabels(
    val passive: String = "Quiet notifications",
    val active: String = "Notifications",
    val timeSensitive: String = "Time-sensitive notifications",
    val critical: String = "Critical notifications",
    val passiveDescription: String = "Notifications delivered without sound",
    val activeDescription: String = "Standard notifications",
    val timeSensitiveDescription: String = "Urgent notifications requiring timely attention",
    val criticalDescription: String = "Highest-priority urgent notifications; device settings still apply"
)

data class HoneyNotifyNotification(
    val id: String?,
    val clickUrl: String?,
    val imageUrl: String?,
    val interruptionLevel: HoneyNotifyInterruptionLevel,
    val channelId: String,
    val data: Map<String, String>
) {
    companion object {
        fun from(data: Map<String, String>): HoneyNotifyNotification {
            val level = HoneyNotifyInterruptionLevel.fromWireValue(data["honeynotify_interruption_level"])
            return HoneyNotifyNotification(
                id = data["honeynotify_notification_id"],
                clickUrl = data["honeynotify_click_url"],
                imageUrl = data["honeynotify_image_url"],
                interruptionLevel = level,
                channelId = data["honeynotify_android_channel_id"] ?: HoneyNotifyChannels.forLevel(level),
                data = data
            )
        }
    }
}

class HoneyNotifyException(val status: Int, message: String) : RuntimeException("$message ($status)") {
    constructor(message: String) : this(0, message)
}
