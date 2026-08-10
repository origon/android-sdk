# Android SDK contract

This public repository wraps the native JNI ABI produced by
`/home/yl/workspace/apps/sdk/session`. Public examples explain usage; this file
registers cross-repository contracts that must change and validate together.

## Mobile chat continuity and push

- The wrapper supplies JNI `initialize` with a random app-install UUID persisted
  under Android's no-backup storage. It never derives continuity identity from
  `Settings.Secure.ANDROID_ID` or other hardware identity. If `userId` is omitted,
  the same install-scoped value is used only as an anonymous opaque user id.
- The JNI ABI is consumed as one hardcut: `initialize(... installationId ...)`,
  required `SessionSummary.active`, `restoreActiveChats`, `openChat`,
  generation-returning `registerPush`, and generation-bound `unregisterPush`.
  The producer is `workspace/apps/sdk/session/src/jni_bridge.rs`; reciprocal
  registration is in `workspace/apps/sdk/CONTRACT.md` and
  `workspace/apps/sdk/session/docs/contract.md`.
- Passive foreground restore calls `restoreActiveChats()` and never takes over
  another installation. Explicit history navigation and a notification tap call
  `openChat(sessionId, takeover = true)`. The wrapper/core manager remains the
  sole per-session operation owner.
- FCM token refresh is repeatable and the latest successful registration wins.
  The opaque endpoint generation and exact token are persisted under no-backup
  storage. Logout unregisters the exact token/provider/generation tuple before
  clearing local notification authority.
- Notification data is trusted for previews only when `endpointGeneration`
  matches the local generation. A mismatch must render generic content or be
  suppressed. A tap opens the named chat with takeover. Provider invalid-token
  cleanup and the server's 90-day endpoint TTL are the uninstall cleanup path.

## Release gate

The release AAR must contain arm64-v8a, armeabi-v7a, and x86_64 libraries. AGP
must resolve NDK 27.2.12479018 and strip release debug symbols; `.symtab` must be
absent while all current JNI exports remain in `.dynsym`, and 64-bit PT_LOAD
segments must remain 0x4000-aligned. Wrapper tests and the example compile must
pass against the local AAR. Do not publish to Maven Central during an
implementation spin.
