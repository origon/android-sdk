# Public SDK surface
-keep class ai.origon.sdk.OrigonClient { *; }
-keep class ai.origon.sdk.OrigonClient$* { *; }
-keep class ai.origon.sdk.SessionException { *; }

# Public model classes (data classes / enums / sealed classes)
-keep class ai.origon.sdk.Channel { *; }
-keep class ai.origon.sdk.Channel$* { *; }
-keep class ai.origon.sdk.SessionControl { *; }
-keep class ai.origon.sdk.SessionControl$* { *; }
-keep class ai.origon.sdk.MessageRole { *; }
-keep class ai.origon.sdk.MessageRole$* { *; }
-keep class ai.origon.sdk.MessageStatus { *; }
-keep class ai.origon.sdk.MessageStatus$* { *; }
-keep class ai.origon.sdk.MessageState { *; }
-keep class ai.origon.sdk.MessageState$* { *; }
-keep class ai.origon.sdk.Attachment { *; }
-keep class ai.origon.sdk.SendMessagePayload { *; }
-keep class ai.origon.sdk.Platform { *; }
-keep class ai.origon.sdk.Platform$* { *; }
-keep class ai.origon.sdk.ClientConfig { *; }
-keep class ai.origon.sdk.StartCallOptions { *; }
-keep class ai.origon.sdk.StartChatOptions { *; }
-keep class ai.origon.sdk.StartSessionResponse { *; }
-keep class ai.origon.sdk.JoinInput { *; }
-keep class ai.origon.sdk.ActiveSession { *; }
-keep class ai.origon.sdk.AttachmentRule { *; }
-keep class ai.origon.sdk.AttachmentPolicy { *; }
-keep class ai.origon.sdk.AttachmentPolicy$* { *; }
-keep class ai.origon.sdk.ServerConfig { *; }
-keep class ai.origon.sdk.EndpointAudioLevel { *; }
-keep class ai.origon.sdk.SessionAudioLevels { *; }
-keep class ai.origon.sdk.AudioLevelObservation { *; }
-keep class ai.origon.sdk.DisconnectReason { *; }
-keep class ai.origon.sdk.DisconnectReason$* { *; }
-keep class ai.origon.sdk.ClientEvent { *; }
-keep class ai.origon.sdk.ClientEvent$* { *; }

# JNI bridge — Rust side resolves these classes / fields by name, so
# they must not be renamed or have members stripped.
-keep class ai.origon.sdk.SessionBridge { *; }
-keep class ai.origon.sdk.bridge.** { *; }
-keepclassmembers class ai.origon.sdk.bridge.** { *; }

# Audio device-change helpers — Rust resolves them by name, reads their
# `mNativePtr` field, binds their native methods, and the framework calls
# their AudioDeviceCallback / OnCommunicationDeviceChangedListener / onReceive
# overrides.
-keep class ai.origon.sdk.RustAudioDeviceCallback { *; }
-keep class ai.origon.sdk.RustCommDeviceListener { *; }
-keep class ai.origon.sdk.RustScoStateReceiver { *; }

# Keep all native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}
