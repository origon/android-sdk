# OrigonSDK Android Example

A minimal Android app demonstrating how to integrate **OrigonSDK** (Kotlin)
for chat and voice calls. Two screens:

1. **Endpoint** — user enters an endpoint URL; the app calls
   `SDKManager.initialize(endpoint)` and persists the URL for next launch.
2. **Home** — a chat surface with a navigation drawer listing past sessions
   (`sdk.getSessions()`), a "New session" button, and a voice button that
   starts a call (`CallService.startCall()`).

This mirrors the iOS example (`apple-sdk/examples/origon-sdk-example-ios`),
ported to Android Views + Kotlin coroutines.

## Requirements

- Android Studio (Koala or newer) **or** the command-line path below
- JDK 17–21 (AGP 8.7 does not support JDK 22+)
- Android SDK Platform 35 + build-tools 35
- A device or emulator on **API 23+** (Android 6.0).

## Getting started

### Android Studio

Open `android-sdk/examples/origon-sdk-example-android` as a project, let it
sync, pick a device, and Run.

### Command line (no Android Studio)

```bash
brew install openjdk@21
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "build-tools;35.0.0" "platforms;android-35"

cd android-sdk/examples/origon-sdk-example-android
./run.sh
```

`run.sh` auto-detects `ANDROID_HOME` and pins a compatible JDK, builds the
APK, installs it on the connected ADB device, launches it, and streams
logcat. Flags: `--apk-only`, `--logcat`.

On first launch the app shows the Endpoint screen. Enter your Origon endpoint
URL and continue — the app stays connected to that endpoint across relaunches.
To switch endpoints, open the sidebar (history icon) → the **⋯** options
button → **Change Endpoint**.

## Where to look in the code

To wire OrigonSDK into your own app, start with these files:

| File | Role |
| --- | --- |
| `services/SDKManager.kt` | Single entry point. Owns `OrigonClient`, drains the SDK event queue on a 50 ms loop into a `SharedFlow`, exposes `CallService` / `ChatService`. |
| `services/ChatService.kt` | Chat state — `openSession`, `sendMessage`, attachment upload, typing, multi-session bookkeeping, all as `StateFlow`s. |
| `services/CallService.kt` | Voice-call state machine — `startCall`, `setMute`, `endCall`, phase transitions. |
| `ui/endpoint/EndpointFragment.kt` | Calls `sdk.initialize(endpoint)`. |
| `ui/chat/RootChatFragment.kt` | Boots the SDK, lists sessions, hosts the chat UI + call overlay. |
| `ui/call/CallFragment.kt` | Active-call surface (gradient, mute, end). |

## SDK dependency

The SDK is consumed from Maven Central with no authentication:

```kotlin
// settings.gradle.kts → dependencyResolutionManagement.repositories
mavenCentral()

// app/build.gradle.kts
implementation("ai.origon:sdk:0.2.0")
```

Bump the version string in `app/build.gradle.kts` to test a newer release.

Two extra notes for SDK consumers (both are worked around in this example):

- `ClientConfig` exposes a `kotlinx.serialization.json.JsonObject` in its
  public API, so you must add
  `implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:…")`
  to your app.
- The SDK's error-kind constants (`SessionBridge.ERROR_*`) are `internal`;
  `SessionException.kind` is a public `Int`. This example mirrors the
  discriminants in `util/SdkErrorKinds.kt`.

## Permissions

- **Microphone** (`RECORD_AUDIO`) — voice calls
- **Camera** (`CAMERA`) — declared for completeness
- **Media/storage reads** — attachment picking

## Scope notes

To keep the example focused on SDK integration, a few iOS niceties are
simplified: inbound message attachments open via an `ACTION_VIEW` intent
rather than an in-app full-screen pager, and the sidebar lists sessions as a
flat list rather than grouped by day.
