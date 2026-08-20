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

The example defaults to the released `ai.origon:sdk:0.3.0`. For source
validation, publish a uniquely versioned sibling SDK to Maven Local and select
that exact version with `-PorigonSdkVersion`; this prevents a stale generic
local artifact from satisfying the gate.

```bash
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "build-tools;37.0.0" "platforms;android-37" \
  "ndk;27.2.12479018"

cd android-sdk/examples/origon-sdk-example-android
./gradlew :app:dependencyInsight --dependency ai.origon:sdk \
  --configuration debugRuntimeClasspath \
  -PorigonSdkVersion=0.4.0-LOCAL-LCM
./gradlew :app:assembleDebug :app:lint \
  -PorigonSdkVersion=0.4.0-LOCAL-LCM
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
| `ui/endpoint/EndpointScreen.kt` | Calls `sdk.initialize(endpoint)`. |
| `ui/chat/RootChatScreen.kt` | Boots the SDK, hosts the drawer, transcript, composer, and the call + attachment overlays. |
| `ui/chat/Sidebar.kt` | Past sessions, grouped by day. |
| `ui/call/CallView.kt` | Active-call surface (gradient, mute, speaker, end). |
| `ui/components/MessageBubble.kt` | One transcript row. **Note the divider rule:** a lifecycle row is discriminated by the presence of `Message.action`, *not* by `role == SYSTEM` — a `role: SYSTEM` message with no action is a flow-bot prompt and stays a bubble. |
| `ui/components/AttachmentsPreview.kt` | Full-screen image / video / audio / PDF preview with download. |

## SDK dependency

The example's released default stays pinned in source, while the explicit
property selects an exact candidate already staged in Maven Local:

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
mavenLocal()

// app/build.gradle.kts
val origonSdkVersion = providers.gradleProperty("origonSdkVersion")
    .getOrElse("0.3.0")
implementation("ai.origon:sdk:$origonSdkVersion")
```

Run `./gradlew :sdk:publishToMavenLocal -PsdkVersion=<unique-version>` from the
android-sdk root before selecting the same value with
`-PorigonSdkVersion=<unique-version>`. Omitting the property continues to use
the tracked Maven Central release pin.

Two extra notes for SDK consumers (both are worked around in this example):

- `ClientConfig` exposes a `kotlinx.serialization.json.JsonObject` in its
  public API, so you must add
  `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:…")`
  to your app.
- The SDK's error-kind constants (`SessionBridge.ERROR_*`) are `internal`;
  `SessionException.kind` is a public `Int`. This example mirrors the
  discriminants in `util/SdkErrorKinds.kt`.

For an FCM data-only integration, pass `RemoteMessage.data` through
`OrigonPushNotifications.currentPayload` before displaying any server copy.
Only an exact endpoint-generation match exposes `payload.title` and
`payload.preview`; use app-owned generic copy when it returns `null`, and fall
back from a null title to your app name. Do not read the raw `title` or `preview`
keys directly, or retain those visible-copy fields in tap-time navigation extras.

## Permissions

- **Microphone** (`RECORD_AUDIO`) — voice calls. Requested at call time; the
  SDK only *declares* it, since it has no Activity to drive the dialog.
- **Nearby devices** (`BLUETOOTH_CONNECT`, API 31+) — requested only when a
  Bluetooth headset is actually connected, so most users never see the prompt.
- **Camera** (`CAMERA`) — declared for completeness
- **Media/storage reads** — attachment picking

## Scope notes

The example authenticates **by endpoint only** — it never signs a person in,
so there is no login, profile, or account screen. It carries no test target;
the shipped Origon apps hold the regression coverage.

Interactive chat prompts (`Message.buttons` / `Message.gallery`) render as
option pills and a card carousel — see `ui/components/MessageButtons.kt` and
`ui/components/MessageGallery.kt`. A `"url"` option opens the link **and** posts
the reply: the flow still has to walk that edge, or the conversation strands on
a waiter that never resolves.

There are no screenshots, deliberately. A screenshot in a repo goes stale the
moment the UI moves, and this app is meant to be read and run.
