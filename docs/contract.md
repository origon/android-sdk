# Android SDK contract

This public repository wraps the native JNI ABI produced by
`/home/yl/workspace/apps/sdk/session`. Public examples explain usage; this file
registers cross-repository contracts that must change and validate together.

## Mobile chat continuity and push

- The wrapper supplies JNI `initialize` with a canonical lowercase UUIDv4 generated
  by the platform CSPRNG and persisted as a confidential bearer capability under
  Android's no-backup storage. It never derives continuity identity from
  `Settings.Secure.ANDROID_ID` or other hardware identity. If `userId` is omitted,
  the same install-scoped value is used only as an anonymous opaque user id; it is
  not a person, verified identity, or proof of endpoint login. Neither
  `installationId` nor its anonymous `userId` alias may enter logs, URLs, or Debug
  output.
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
- Notification enablement governs push registration/delivery only; it never disables
  list/history/start or same-install restore. Anonymous continuity and push are scoped
  to the exact trusted app + installation, while only a verified external subject may
  authorize cross-install takeover or multi-install push fan-out. UUID disclosure can
  impersonate that installation, redirect/suppress delivery, and disclose preview text.
  Remediation is backend endpoint revocation, ending rooms bound to the old UUID, and
  app-data reset/reinstall to mint a new UUID; the server TTL is not revocation.
  Canonical producers: `/home/yl/workspace/platform/gw/CONTRACT.md`
  native-admission row, `/home/yl/workspace/platform/cx/CONTRACT.md` §3/§7,
  and `/home/yl/workspace/apps/sdk/CONTRACT.md` plus
  `/home/yl/workspace/apps/sdk/session/docs/contract.md`.
- FCM token refresh is repeatable and the latest successful registration wins.
  The opaque endpoint generation and exact token are persisted under no-backup
  storage. Logout unregisters the exact token/provider/generation tuple before
  clearing local notification authority.
- Logout clears local generation authority even when the generation-bound
  backend unregister fails or no initialized client exists. A delayed provider
  delivery therefore cannot promote its preview after local identity removal.
  Clearing authority also suspends later FCM callbacks and already-queued
  registrations for that client epoch; only attaching a newly initialized
  client resumes registration.
- The inbound FCM payload is the reciprocal consumer of
  `/home/yl/workspace/platform/cx/CONTRACT.md` §7. First-party destinations
  `ai.origon.android.beta` and `ai.origon.android` are data-only; third-party
  destinations retain cx's generic top-level notification fallback alongside
  the custom data. The custom-data string fields are `type`, `sessionId`,
  `clientId`, `messageId`, `endpointGeneration`, `preview`, and optional `title`;
  cx omits a blank normalized title rather than sending `null`. Regardless of
  provider fallback, the wrapper exposes custom-data `title` and `preview` only
  after `endpointGeneration` exactly matches the locally persisted generation.
  Missing or stale generation exposes neither copy field (the whole payload
  fails closed), and an absent or blank title becomes `null`. `title` is an
  authorized body property rather than part of the data class's constructor,
  component identity, equality, hash, or `copy`; those retain their legacy
  four-field JVM ABI. A tap opens the named chat with takeover. Provider
  invalid-token cleanup and the server's 90-day endpoint TTL are the uninstall
  cleanup path.

## Release gate

The release AAR must contain arm64-v8a, armeabi-v7a, and x86_64 libraries. AGP
must resolve NDK 27.2.12479018 and strip release debug symbols; `.symtab` must be
absent while all current JNI exports remain in `.dynsym`, and 64-bit PT_LOAD
segments must remain 0x4000-aligned. Wrapper tests and the example compile must
pass against the local AAR. Do not publish to Maven Central during an
implementation spin.
