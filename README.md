# Origon Android SDK

Android SDK for the Origon platform.

## About

The Origon SDK for Android lets you embed Origon directly in your Android
app: **audio calls**, **chat**, and **session history**.

A basic chat + voice integration takes around 15 minutes; allow a little
longer if you also wire up push notifications. The SDK
authenticates your app by its **Package Name**, which you register once in
the Origon Connect web app (see [Prerequisites](#prerequisites)). At runtime
all you pass is your Origon **endpoint**.

## Features

- **Audio calls** — low-latency voice, with automatic Bluetooth device
  routing.
- **Chat** — messaging with typing indicators and attachments.
- **Push notifications** — wake your app for incoming calls and messages.
- **Session history** — retrieve past sessions and their messages.

## Requirements

- Android API 23+ (Android 6.0 Marshmallow)

  The native audio backend uses [Oboe](https://github.com/google/oboe),
  which selects AAudio on API 27+ and OpenSL ES on API 23-26 at runtime.
  No special integration is required on any supported API level.

## Prerequisites

Register your app in the Origon Connect web app before the SDK can connect.
Your account owner or admin has access to it. The SDK authenticates each app
by the **Package Name** it reports, so that Package Name has to be on your
tenant's allow-list first.

1. Sign in to **Origon Connect** at <https://origon.ai/connect>.
2. Go to **Settings → Integrations → Mobile → Setup Mobile SDK**.
3. Fill in your app details — **Company Name**, **logo**, and the
   **routing** rules for your flow (where calls and chats are sent). Press
   **Next**.
4. In the **Deployment** tab, add your app's **Package Name** (your
   `applicationId`, e.g. `com.domain.yourapp`) to the **Bundle IDs** field.
   It accepts multiple entries, so you can register several apps (e.g.
   staging and production) against the same config. To run and test the
   [sample app](#sample-app), add its package name `origon.example.android`
   here as well.
5. Copy the **endpoint** shown in the **Deployment** tab and pass it as the
   `endpoint` in `ClientConfig` when you initialize `OrigonClient` (see
   [Quick Start](#quick-start)).
6. **Save** the config.

Your Package Name is the `applicationId` in your app module's
`build.gradle.kts`; it must match exactly what the app reports at runtime.

## Installation

### 1. Add the repository

The SDK is published to [Maven Central](https://central.sonatype.com/artifact/ai.origon/sdk).
No authentication is required.

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### 2. Add the dependency

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ai.origon:sdk:0.2.0")
}
```

## Sample app

You'll find the Origon SDK for Android on GitHub
[here](https://github.com/Origon/android-sdk). The repo also includes a
runnable sample app — a minimal Android app that integrates chat and voice
calls — under
[`examples/origon-sdk-example-android`](https://github.com/Origon/android-sdk/tree/main/examples/origon-sdk-example-android).
See its [README](https://github.com/Origon/android-sdk/blob/main/examples/origon-sdk-example-android/README.md)
for build and run instructions (Android Studio or a no-IDE `run.sh` path),
plus a guide to which files to read first when wiring the SDK into your own
app. `RootChatFragment.startCall()` there shows the full mic + Bluetooth
permission flow in one place.

## Quick Start

```kotlin
import ai.origon.sdk.*

// Optional: install Rust-side logging once at app launch.
OrigonClient.initLogging()

// Create the client. `context` is an Android Context (usually
// `applicationContext`); the SDK uses it to read `packageName` and
// send it as `X-Bundle-Id` on every HTTPS call.
// `userId` is optional — when omitted, the SDK uses a random app-install id
// under no-backup storage as an opaque anonymous id. It never uses ANDROID_ID.
val client = OrigonClient(
    context,
    ClientConfig(endpoint = "https://origon.ai/chat/api/<id>"),
)

// Start a voice session.
val response = client.startSession(StartSessionOptions(channel = Channel.VOICE))
println("session ${response.sessionId} dialing ${response.url}")

// Drain the event stream.
while (true) {
    when (val event = client.pollEvent()) {
        is ClientEvent.Connected -> println("connected")
        is ClientEvent.PeerAttached -> println("peer ${event.peerEndpointId}")
        is ClientEvent.AudioRouteChanged -> speakerOn = event.route == AudioOutputRoute.SPEAKER
        is ClientEvent.Disconnected -> { println("disconnected: ${event.reason}"); break }
        null -> Thread.sleep(50)
        else -> {}
    }
}

client.close()
```

### Microphone permission (required for voice calls)

The SDK's manifest declares `RECORD_AUDIO`, so it merges into your app
automatically — but `RECORD_AUDIO` is a **runtime ("dangerous")
permission**. On Android 6+ (API 23+) the manifest declaration alone is
not enough: **your app must request the grant at runtime before starting
a voice session.** Without it, the SDK's audio-capture (the input)
stream fails to open — playback still works, but the call has no outgoing
audio (the peer hears silence). This is the consumer app's
responsibility, not the SDK's — the SDK has no `Activity` to drive the
permission dialog.

```kotlin
// In an Activity / Fragment — request before calling startSession(VOICE).
private val requestMic = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted ->
    if (granted) startVoiceCall() else showMicRequiredMessage()
}

private fun onCallButtonTapped() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED
    ) {
        startVoiceCall()
    } else {
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }
}
```

> Symptom if you skip this: logcat shows the capture stream failing to
> open (e.g. `IAudioFlinger: createRecord returned error -1`), followed by
> the SDK logging `audio device start failed, continuing without audio`.

### Bluetooth permission (for headset calls on Android 12+)

When a Bluetooth (hands-free / HFP) headset is connected, the SDK routes
the call to it automatically — mic **and** earpiece — matching the native
phone app. The required permissions are declared in the SDK manifest and
merge into your app:

| Permission | API | Type | Who grants it |
| --- | --- | --- | --- |
| `MODIFY_AUDIO_SETTINGS` | all | normal | auto-granted at install |
| `BLUETOOTH` (`maxSdkVersion=30`) | ≤ 30 | normal | auto-granted at install |
| `BLUETOOTH_CONNECT` | 31+ | **runtime** | **your app must request it** |

On **Android 11 and below nothing extra is needed** — `BLUETOOTH` is a
normal permission and is granted at install.

On **Android 12+ (API 31+)**, `BLUETOOTH_CONNECT` is a runtime permission.
The SDK declares it but **cannot grant it** — it has no `Activity` to show
the permission dialog, so **your app must request it at runtime** for
Bluetooth headset routing to work.

Request it **only when a Bluetooth headset is actually connected** — its
system dialog reads "find, connect to, and determine the relative position
of nearby devices" (the generic _Nearby devices_ group text; the permission
itself only connects to already-paired devices, no scanning/location), so
prompting every user for it would be confusing. `AudioManager.getDevices`
needs no Bluetooth permission, so it's safe to check first:

```kotlin
// Only needed on Android 12+, and only when a BT headset is present.
private val requestBt = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
) { /* granted or not — the call proceeds either way (see fallback below) */ }

private fun maybeRequestBluetooth(am: AudioManager) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val headsetConnected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    if (headsetConnected &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) {
        requestBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }
}
```

See `RootChatFragment.startCall()` in the example app for the full flow
(mic + conditional Bluetooth) in one place.

**Graceful fallback:** this permission is _not_ required for a call to
succeed. If `BLUETOOTH_CONNECT` is missing (or the headset's SCO link
otherwise fails to come up), the SDK waits ~4 s for the link, then falls
back to the built-in mic/earpiece so the call keeps working — you simply
don't get Bluetooth routing. Requesting it is what lets the headset be
used.

> Symptom if you skip this on Android 12+: logcat shows
> `Bluetooth SCO did not connect within 4s; falling back to built-in audio`,
> and the call uses the phone's mic/earpiece instead of the headset.

### Voice controls

```kotlin
// Mute (per session).
client.setMute(id = response.sessionId, muted = true)

// Audio output route — process-global, so no session id. Applied via
// AudioManager (speakerphone / Bluetooth SCO). Resets to AUTOMATIC on each
// new call.
client.setAudioOutput(AudioOutputRoute.SPEAKER)     // force the loudspeaker
client.setAudioOutput(AudioOutputRoute.AUTOMATIC)   // back to the default route (earpiece / wired / Bluetooth)
```

A speaker toggle is typically
`client.setAudioOutput(if (on) AudioOutputRoute.SPEAKER else AudioOutputRoute.AUTOMATIC)`.

### Multiple sessions

```kotlin
val active: List<ActiveSession> = client.activeSessions()
client.setMuteAll(muted = true)
client.endAllSessions()
```

### Joining a pre-obtained session

```kotlin
client.joinSession(JoinSessionInput(
    channel = Channel.VOICE,
    sessionId = "...",
    url = "...",
    token = "...",
))
```

### Chat

`sendMessage`, `notifyTyping`, and `stopTyping` all require an active
chat session. **Call `startSession(channel = Channel.CHAT, ...)`
first** — otherwise these throw `SessionException(kind = NO_SESSION)`.
The same applies after `endSession(id)`.

```kotlin
// Outbound send. The SDK fires ClientEvent.MessageAdded (status =
// SENDING) before the wire round-trip and ClientEvent.MessageUpdated
// (delivered or failed) after — both surface on pollEvent(). The
// return value is the server-issued Message.
val msg = client.sendMessage(
    id = sessionId,
    payload = SendMessagePayload(text = "hello", html = "hello"),
)

// Typing — call per keystroke (e.g. from a TextWatcher). The SDK
// debounces; only one outbound `{state: "on"}` fires per typing burst
// and a `{state: "off"}` is auto-emitted after ~3 s of no further
// calls. Fire `stopTyping(id)` explicitly when the input clears
// (e.g. user deleted all text) to snap the peer's "typing…"
// indicator off instantly.
client.notifyTyping(id = sessionId)
client.stopTyping(id = sessionId)
```

### Retained chat continuity

The host owns lifecycle triggers; the SDK session manager owns restore. Run one
bounded passive restore on foreground/bootstrap. It attaches only directory rows
reported as `active` and never steals another installation's visitor stream:

```kotlin
val report = client.restoreActiveChats()
val elsewhere = report.filter { it.status == RestoreStatus.ACTIVE_ELSEWHERE }

// Explicit history navigation or a notification tap is user intent.
client.openChat(sessionId, takeover = true)
```

Do not add a second host restore loop or attach the same id independently. An
ended row remains view-only until the ordinary first-send reopen path runs.

Polling chat events:

```kotlin
while (true) {
    val event = client.pollEvent() ?: break
    when (event) {
        is ClientEvent.MessageAdded ->
            // Store event.message under (message.localId ?: message.id)
        is ClientEvent.MessageUpdated ->
            // Look up the row by event.id (matches the original lookup key)
        is ClientEvent.Typing ->
            // Show / hide "typing…" indicator
        else -> Unit
    }
}
```

### Attachments

Upload a file, then attach the returned `Attachment` to your next
message. **There is no `sessionId`** — attachments are scoped to the
widget the client was created for, so an attachment can be the first
thing a visitor sends, before any session exists. `uploadAttachment` is a
`suspend` function (call it from a coroutine) with overloads for a
filesystem path, a `content://` `Uri`, or in-memory `ByteArray`:

```kotlin
val attachment = client.uploadAttachment(
    uri = pickedUri,
    fileName = "photo.jpg",
) { progress ->
    // progress.percent is null when the total size is unknown
    updateProgressBar(progress.percent)
}

client.sendMessage(
    id = sessionId,
    payload = SendMessagePayload(attachments = listOf(attachment)),
)

// Cancel an in-flight upload (pass the uploadId) or delete a completed
// one (pass attachment.id) — the SDK works out which.
client.deleteAttachment(attachmentId = attachment.id)
```

Uploads are prechecked against the tenant's `attachmentPolicy` (type and
size); a disallowed file throws `SessionException` before any bytes are
sent.

### Push notifications

Register this device's FCM token so the backend can deliver push
notifications. The host app owns token acquisition — set up
[Firebase Cloud Messaging](https://firebase.google.com/docs/cloud-messaging/android/client)
(its `google-services.json`, the `com.google.gms.google-services` plugin,
and the `com.google.firebase:firebase-messaging` dependency), then
forward the token from your `FirebaseMessagingService`:

```kotlin
import ai.origon.sdk.OrigonPushNotifications
import com.google.firebase.messaging.FirebaseMessagingService

class AppMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        OrigonPushNotifications.onNewToken(token)
    }
}
```

In `onMessageReceived`, authorize all server-provided visible copy before
building a notification:

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    val payload = OrigonPushNotifications.currentPayload(this, message.data)
    if (payload == null) {
        // Missing/stale generation: neither title nor preview is exposed.
        // Suppress, or show only app-owned generic copy.
        return
    }
    showNotification(
        title = payload.title ?: "Origon",
        body = payload.preview ?: "New message",
        payload = payload,
    )
}
```

`title` is the optional server-normalized human agent name. An absent or blank
title is exposed as `null`. Never read `message.data["title"]` or
`message.data["preview"]` directly: `currentPayload` returns them only after an
exact `endpointGeneration` match, and returns `null` when the generation is
missing or stale.

Use a stable `PendingIntent` carrying `sessionId`; when the user taps, initialize
the client and call `OrigonPushNotifications.open(client, payload)`. A tap is
explicit takeover intent; background receipt is not. Keep tap extras to routing
and authorization fields—do not copy `title` or `preview` into them.

Declare the service in your `AndroidManifest.xml`:

```xml
<service
    android:name=".AppMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

`registerForPushNotifications(token)` is a **companion-object** method
and is safe to call **before** the client is initialized — the token is
buffered and sent automatically once `OrigonClient` is created. It is
also safe to call repeatedly (e.g. from `onNewToken`); the latest token
wins. The call returns immediately and runs the network request in the
background; failures are logged, not thrown. FCM has no sandbox/
production split, so no environment is sent.

The exact token and returned endpoint generation are stored under
`noBackupFilesDir`. Call registration on every FCM token refresh. On logout,
unregister before closing the client and cancel delivered notifications. An
uninstall cannot send logout; FCM invalid-token feedback and the server's
90-day endpoint TTL perform eventual cleanup.

```kotlin
// On logout:
client.unregisterForPushNotifications() // waits for exact unregister
client.close()
```

The companion `OrigonClient.unregisterForPushNotifications()` remains the
fire-and-forget convenience. Use the instance method above when logout will
immediately close the client.

Release builds pin NDK `27.2.12479018` so AGP can strip the prebuilt Rust
libraries instead of packaging them behind an “Unable to strip” warning. Before
publication, run `scripts/verify-release-aar.sh` on the local release AAR; it
requires all three ABIs, no `.symtab`, all four continuity JNI exports, and
0x4000 PT_LOAD alignment on both 64-bit libraries.

## API Reference

### OrigonClient

| Method | Description |
| --- | --- |
| `OrigonClient(config)` | Create a new client. Throws `SessionException` on connect failure. |
| `close()` | Release the native handle. |
| `pollEvent()` | Non-blocking poll. Returns `null` when idle. |
| `startSession(options)` | Open a session. Returns `(sessionId, url, token)`. |
| `restoreActiveChats()` | Passively attach retained active chats and return per-id outcomes. |
| `openChat(sessionId, takeover)` | Explicitly open one retained chat; takeover is user intent. |
| `joinSession(input)` | Attach to a previously-obtained `StartSessionResponse`. |
| `endSession(id)` / `endAllSessions()` | Close a single / every session. |
| `setMute(id, muted)` / `setMuteAll(muted)` | Voice — absolute mute. |
| `setAudioOutput(route)` | Voice — override the audio output route (`SPEAKER` / `AUTOMATIC` / `BLUETOOTH`). Process-global. |
| `sendMessage(id, payload)` | Chat — POST `<sessionUrl>/message`. Returns the server-issued `Message`. Fires `MessageAdded` then `MessageUpdated`. |
| `notifyTyping(id)` | Chat — register a keystroke; SDK debounces outbound `/typing` POSTs. |
| `stopTyping(id)` | Chat — force outbound typing state to "off" immediately. |
| `uploadAttachment(path \| uri \| bytes, fileName, …)` | `suspend`; upload a file (path / `Uri` / `ByteArray` overloads) against the client's widget and return the server-issued `Attachment`. No session required. Reports progress via `onProgress`. |
| `deleteAttachment(attachmentId)` | `suspend`; cancel an in-flight upload (pass the `uploadId`) or delete a completed attachment (pass `attachment.id`). No session required. |
| `activeSessions()` | Snapshot of every active session. |
| `getSessions()` | `GET /sessions` — list prior sessions for the configured `userId`. |
| `getSession(id)` | `GET /session/<id>` — transcript for one session. |
| `setAttributes(attributes)` | Replace session-level attributes injected as `data.attributes` on `startSession`. |
| `OrigonClient.registerForPushNotifications(token)` | Companion. Register an FCM token (buffered until init; latest wins). |
| `OrigonClient.unregisterForPushNotifications()` | Companion. Remove this device's push registration (e.g. on logout). |
| `OrigonPushNotifications.currentPayload(context, data)` | Validate the exact endpoint generation, then expose routing plus optional authorized `title` / `preview`; returns `null` for missing or stale authority. |
| `startMessage` / `isChatEnabled` / `isCallEnabled` / `multipleChannels` / `attachmentPolicy` / `serverConfig` | Cached `/config` getters. |
| `OrigonClient.initLogging(filter)` | Install Rust-side `tracing` subscriber. |

### Types

| Type | Description |
| --- | --- |
| `ClientConfig` | endpoint, optional `token`, optional `userId`, attributes (`JsonObject?`). The app is authenticated by its **package name** (`applicationId`), resolved automatically from `context.packageName` (passed to `OrigonClient`) and sent as `X-Bundle-Id` on every HTTPS call (register it first — see [Prerequisites](#prerequisites)). `token` is an optional auth token. `userId` defaults to the random no-backup app-install id when omitted. |
| `Channel` | `CHAT`, `VOICE`. |
| `SessionControl` | `AI`, `USER`. |
| `MessageRole` | `AI`, `EXTERNAL`, `USER`, `SYSTEM`. |
| `MessageStatus` | `SENDING`, `DELIVERED`, `FAILED`. |
| `MessageState` | `STREAMING`, `COMPLETED`. |
| `AudioOutputRoute` | `AUTOMATIC` (default route — earpiece / wired / Bluetooth), `SPEAKER` (loudspeaker), `BLUETOOTH`. Argument to `setAudioOutput(route)`. |
| `StartSessionOptions` | channel, optional sessionId, optional `data` (raw JSON). |
| `StartSessionResponse` | sessionId, url, token. |
| `JoinSessionInput` | channel, sessionId, url, token. |
| `ActiveSession` | sessionId, channel. |
| `OrigonNotificationPayload` | Authorized notification routing plus optional `title` and `preview`; blank title is `null`. |
| `AttachmentRule` / `AttachmentPolicy` | tenant policy for attachments. |
| `ServerConfig` | full `/config` snapshot (start message, capability flags, attachment policy). |
| `DisconnectReason` | sealed class of structured reasons. |
| `ClientEvent` | sealed class: `MessageAdded`, `MessageUpdated`, `Connected`, `Reconnecting`, `Reconnected`, `PeerAttached`, `PeerDetached`, `Disconnected`, `CallError`, `AudioRouteChanged`, `ControlUpdated`, `Typing`, `SessionUpdated`. Every variant carries `sessionId`. `AudioRouteChanged` carries the now-current `AudioOutputRoute` (drive a speaker toggle from `route == AudioOutputRoute.SPEAKER`); it fires on OS-driven route changes (headset plug/unplug) as well as your own `setAudioOutput`. |
| `Message` | typed transcript line. Carries `id`, `localId`, `role`, `text`, `html`, `userId`, `userName`, `timestamp`, `attachments`, `errorText`, `status`, `state`. |
| `Attachment` | uploaded-media descriptor: `id`, `name`, `contentType`, `url`, and an optional client-side `localUrl` preview (kept on the local `Message`, stripped from the wire). Returned by `uploadAttachment(...)`, carried on `Message.attachments`, and passed back into `SendMessagePayload.attachments`. |
| `UploadProgress` | `bytesUploaded`, optional `totalBytes`, optional `percent` (both `null` when the transport reports no content length). Passed to the `uploadAttachment` `onProgress` callback. |
| `Contact`, `SessionSummary`, `SessionHistory` | typed shapes returned by `getSessions()` / `getSession(id)`. |
| `SendMessagePayload` | `text`, `html`, `attachments` (input shape for `sendMessage(id, payload)`). |

## License

Proprietary. All rights reserved.
