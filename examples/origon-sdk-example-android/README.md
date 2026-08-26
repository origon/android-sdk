# OrigonSDK Android Example

A minimal Android app demonstrating how to integrate **OrigonSDK** (Kotlin)
for chat and voice calls. Two screens:

1. **Endpoint** — user enters an endpoint URL; the app calls
   `SDKManager.initialize(endpoint)` and persists the URL for next launch.
2. **Home** — a chat surface with a navigation drawer listing past sessions
   (fed by the SDK's cache-first directory Flow), a "New session" button, and a voice button that
   starts a call (`CallService.startCall()`).

This mirrors the iOS example (`apple-sdk/examples/origon-sdk-example-ios`),
built with Jetpack Compose + Kotlin coroutines.

## Requirements

- Android Studio (Koala or newer) **or** the command-line path below
- JDK 17–21
- Android SDK Platform 37 + build-tools 37
- Android NDK 27.2.12479018
- A device or emulator on **API 23+** (Android 6.0).

## Getting started

### Android Studio

Open `android-sdk/examples/origon-sdk-example-android` as a project, let it
sync, pick a device, and Run.

### Command line (no Android Studio)

The example defaults to released SDK `0.3.2`. To validate a sibling SDK build,
install the required toolchain, publish it under a unique local version, and
select the same version explicitly:

```bash
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "build-tools;37.0.0" "platforms;android-37" \
  "ndk;27.2.12479018"

cd android-sdk
./gradlew :sdk:publishToMavenLocal -PsdkVersion=0.4.0-LOCAL-LCM
cd examples/origon-sdk-example-android
./gradlew :app:dependencyInsight \
  --dependency ai.origon:sdk \
  --configuration debugRuntimeClasspath \
  -PorigonSdkVersion=0.4.0-LOCAL-LCM
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint \
  -PorigonSdkVersion=0.4.0-LOCAL-LCM
# With an emulator or device connected:
./gradlew :app:connectedDebugAndroidTest -PorigonSdkVersion=0.4.0-LOCAL-LCM
```

`run.sh` auto-detects `ANDROID_HOME` and pins a compatible JDK, builds the
APK, installs it on the connected ADB device, launches it, and streams
logcat. Flags: `--apk-only`, `--logcat`.

On first launch the app shows the Endpoint screen. Enter your Origon endpoint
URL and continue — the app stays connected to that endpoint across relaunches.
To switch endpoints, open the sidebar (history icon) → **Change endpoint**.

## Where to look in the code

To wire OrigonSDK into your own app, start with these files:

| File | Role |
| --- | --- |
| `services/SDKManager.kt` | Single entry point. Owns `OrigonClient`, drains the SDK event queue on a 50 ms loop into a `SharedFlow`, exposes `CallService` / `ChatService`. |
| `services/ChatService.kt` | Chat state — `openSession`, `sendMessage`, attachment upload, typing, multi-session bookkeeping, all as `StateFlow`s. |
| `services/CallService.kt` | Voice-call state machine — `startCall`, `setMute`, `endCall`, phase transitions. |
| `services/CallForegroundService.kt`, `CallHostGate.kt` | Private microphone FGS, post-promotion five-second acknowledgement, audio focus, notification hang-up, and idempotent cleanup. |
| `ui/endpoint/EndpointScreen.kt` | Calls `sdk.initialize(endpoint)`. |
| `ui/chat/RootChatScreen.kt` | Boots the SDK, hosts the drawer, transcript, composer, and the call + attachment overlays. |
| `ui/chat/Sidebar.kt` | Past sessions, grouped by day. |
| `ui/call/CallView.kt` | Active-call surface (gradient, mute, speaker, end). |
| `ui/components/MessageBubble.kt` | One transcript row. **Note the divider rule:** a lifecycle row is discriminated by the presence of `Message.action`, *not* by `role == SYSTEM` — a `role: SYSTEM` message with no action is a flow-bot prompt and stays a bubble. |
| `ui/components/AttachmentsPreview.kt` | Full-screen image / video / audio / PDF preview with download. |

## SDK dependency

The checked-in default consumes the released SDK. `origonSdkVersion` is the
single switch used by the local-artifact gate:

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
mavenLocal()

// app/build.gradle.kts
val origonSdkVersion = providers.gradleProperty("origonSdkVersion")
    .getOrElse("0.3.2")
    .trim()
implementation("ai.origon:sdk:$origonSdkVersion")
```

Never validate a local SDK under the released coordinate: Maven Central could
satisfy it silently. Publish and select a unique local version, then inspect
`dependencyInsight` as shown above.

Two extra notes for SDK consumers (both are worked around in this example):

- `ClientConfig` exposes a `kotlinx.serialization.json.JsonObject` in its
  public API, so you must add
  `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:…")`
  to your app.
- The SDK's error-kind constants (`SessionBridge.ERROR_*`) are `internal`;
  `SessionException.kind` is a public `Int`. This example mirrors the
  discriminants in `util/SdkErrorKinds.kt`.

The example pins jsoup 1.23.1 and commonmark-java 0.30.0 for the bounded native
rich-message renderer. Its API 23 floor requires Google's NIO desugar runtime
2.1.5. Exact third-party license texts are retained under
`../../THIRD_PARTY_NOTICES/`; parser or desugar upgrades require renewed dependency
and hostile-input review.

For an FCM data-only integration, pass `RemoteMessage.data` through
`OrigonPushNotifications.currentPayload` before displaying any server copy.
Only an exact endpoint-generation match exposes `payload.title` and
`payload.preview`; use app-owned generic copy when it returns `null`, and fall
back from a null title to your app name. Do not read the raw `title` or `preview`
keys directly, or retain those visible-copy fields in tap-time navigation extras.

## Cache and named chat access

The example paints finite cache-first directory/transcript Flows and reconciles
the one authoritative result without losing live/provisional rows. Selecting a
history row calls `openChat(sessionId, EXPLICIT_NAVIGATION)` before sending, so
cached display never acquires takeover authority. Its protected `NEW MESSAGES`
checkpoint lives under `noBackupFilesDir` and remains app-owned.

This sample deliberately does **not** call `restoreActiveChats()` and does not
auto-return to a recent chat. Those are host-product lifecycle choices. Apps
that want passive retained-chat restore can follow the main README while
keeping explicit row and notification navigation on their named intents.

## Permissions

- **Microphone** (`RECORD_AUDIO`) — voice calls. Requested at call time; the
  SDK only *declares* it, since it has no Activity to drive the dialog.
- **Nearby devices** (`BLUETOOTH_CONNECT`, API 31+) — requested only when a
  Bluetooth headset is actually connected, so most users never see the prompt.
- **Notifications** (`POST_NOTIFICATIONS`, API 33+) — requested with the call
  permissions but does not gate a successfully promoted microphone service.
- **Foreground service** — `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_MICROPHONE`; the private call host promotes before audio
  focus or native capture, then survives background/screen-off.
- **Camera** (`CAMERA`) — declared for completeness
- **Media/storage reads** — attachment picking

Every call begins from the visible call action after microphone permission.
Promotion/binding is acknowledged within five seconds before audio focus and
`CallService.startCall`; every refusal/timeout stops the host with no SDK call.
Remote/local end, notification hang-up, repeated start, SDK failure, and process
recreation converge on the same idempotent cleanup.

The example intentionally contains no `FirebaseMessagingService` or other push
runtime. Use the main README for complete data-only FCM setup, token/generation
authority, generic fallback, tap revalidation, logout/unregister, force-stopped
and uninstall behavior, and secret-safe logging.

## Scope notes

The example authenticates **by endpoint only** — it never signs a person in,
so there is no login, profile, or account screen. JVM tests own its pure copied
policy; instrumentation tests own Android storage/service integration.

Interactive chat prompts (`Message.buttons` / `Message.gallery`) render as
option pills and a card carousel — see `ui/components/MessageButtons.kt` and
`ui/components/MessageGallery.kt`. A `"url"` option opens the link **and** posts
the reply: the flow still has to walk that edge, or the conversation strands on
a waiter that never resolves.

There are no screenshots, deliberately. A screenshot in a repo goes stale the
moment the UI moves, and this app is meant to be read and run.
