# HoneyNotify Android SDK

The HoneyNotify Android SDK registers FCM devices, maintains user identity and tags across token refreshes, parses notification payloads, and reports notification lifecycle events.

The canonical source lives in [`sdks/android`](https://github.com/charlesbradber/HoneyNotify/tree/main/sdks/android). Changes merged there are tested and mirrored automatically to this repository.

## Requirements

- Android API 26 or later
- Java 17
- Firebase Cloud Messaging
- A HoneyNotify public mobile client key (`ps_public_...`)

Public mobile client keys are restricted by the HoneyNotify API to device registration and event reporting. Never bundle a notification-send key in an app.

## Install

Until a Maven package is published, include this repository as a Gradle module or copy the module into your project. The module already declares the Firebase Messaging dependency.

Create a client and register the current FCM token:

```kotlin
val honeyNotify = HoneyNotify(
    context = applicationContext,
    baseUrl = "https://api.honeynotify.com",
    clientKey = "ps_public_your_key"
)

honeyNotify.registerCurrentToken(
    externalUserId = "customer-123",
    tags = mapOf("plan" to "pro")
) { result ->
    result.onSuccess { deviceId -> println(deviceId) }
}
```

Forward token refreshes from your `FirebaseMessagingService`:

```kotlin
override fun onNewToken(token: String) {
    Thread { honeyNotify.onTokenRefresh(token) }.start()
}
```

Use `identify` after login, `logout` on sign-out, and `track` from notification handlers. When verified identity is enabled, obtain the ES256 identity token from your backend and pass it to `register` or `identify`.

Network methods are synchronous; call them off the main thread or use the provided asynchronous helpers.

## Test

```bash
gradle testDebugUnitTest assembleDebug
```
