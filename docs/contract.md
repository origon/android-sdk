# Android SDK contract

This public repository wraps the native JNI ABI produced by
`/home/yl/workspace/apps/sdk/session`. Public examples explain usage; this file
registers cross-repository contracts that must change and validate together.

## Interactive chat prompts

- The wrapper mirrors apps/sdk's JSON surface for incoming
  `Message.buttons: [{label,value,type}]` and
  `Message.gallery: [{title,description,image?,buttons}]`; a gallery image is
  legitimately absent. `SendMessagePayload` mirrors top-level optional `value`
  and `galleryLabel`, while `text` carries the selected caption.
- Canonical wire production/consumption is registered in
  `/home/yl/workspace/platform/cx/CONTRACT.md` (Button / Gallery reply shape;
  consumers of `buttons` / `gallery`) and the native producer/consumer mirror
  in `/home/yl/workspace/apps/sdk/CONTRACT.md`. This wrapper adds no alternate
  envelope or persistence.

## Live-chat audience metadata

- Version 0.3.1 mirrors apps/sdk's two-level optional boundary as nullable
  `Message.metadata`, nullable `MessageMetadata.audience`, and nullable
  `SendMessagePayload.metadata`. Missing/null/empty values remain null,
  explicit lowercase `internal|all` values are preserved, and every other
  non-empty value fails without trimming. Encoding omits null while preserving
  an explicitly supplied empty metadata object as `{}`.
- Canonical wire production and validation are registered in
  `/home/yl/workspace/platform/cx/CONTRACT.md` and
  `/home/yl/workspace/apps/sdk/CONTRACT.md`. This wrapper only decodes the
  already-authorized projection and never chooses or rewrites the audience.

## Authoritative typing identity

- The 0.3.2 candidate hardcuts public `ClientEvent.Typing.isTyping` to
  `ClientEvent.Typing.state`; `TypingState.participants` preserves stable
  first-activation order and canonical participant, role, optional user
  identity, and audience fields.
- JNI class layout is unchanged: `SessionEvent.typing` remains the aggregate
  compatibility bit while the authoritative snapshot uses existing
  `messageJson`. The wrapper decodes it fail-closed and never persists or logs
  participant identity.
- The shipped Android app and repository example render the first active
  participant with one avatar plus the existing capsule and clear on empty or
  terminal/local lifecycle events.

## Mobile chat continuity and push

- The wrapper supplies JNI `initialize` with a canonical lowercase UUIDv4 generated
  by the platform CSPRNG and persisted as a confidential bearer capability under
  Android's no-backup storage. It never derives continuity identity from
  `Settings.Secure.ANDROID_ID` or other hardware identity. If `userId` is omitted,
  the same install-scoped value is used only as an anonymous opaque user id; it is
  not a person, verified identity, or proof of endpoint login. Neither
  `installationId` nor its anonymous `userId` alias may enter logs, URLs, or Debug
  output.
- The JNI ABI is consumed as one hardcut: `initialize(... installationId, cacheDir ...)`,
  required `SessionSummary.active`, `restoreActiveChats`, `openChat`,
  generation-returning `registerPush`, and generation-bound `unregisterPush`.
  The producer is `workspace/apps/sdk/session/src/jni_bridge.rs`; reciprocal
  registration is in `workspace/apps/sdk/CONTRACT.md` and
  `workspace/apps/sdk/session/docs/contract.md`.
- Passive foreground restore calls `restoreActiveChats()` and never takes over
  another installation. Explicit history navigation and a notification tap call
  `openChat(sessionId, intent = EXPLICIT_NAVIGATION/NOTIFICATION)`. The wrapper/core manager remains the
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
- Cache lives only under credential-encrypted
  `noBackupFilesDir/ai.origon.sdk/chat-cache-v1`. Finite session and directory
  loaders use a bounded two-item `Flow`, pull on `Dispatchers.IO`, emit cache
  before network, and cancel/join before freeing their native handle. Client
  close rejects new JNI leases and waits active calls, native loaders and cache
  writers. `clearAllChatCaches(context)` is handle-independent and returns only
  after the cache subtree has been synchronously quarantined.
  A derived device-protected caller context normally reroots through its global
  credential-protected `applicationContext`. If that global context itself is
  DPS (including `defaultToDeviceProtectedStorage`), caching fails closed to
  disabled because Android has no public inverse of
  `createDeviceProtectedStorageContext`; transcripts are never written into DPS.
  API 23 storage is credential-protected by definition.
  `ChatCachePolicy.DISABLED` passes no
  native cache root. Static clear creates only the fixed cache subtree first so
  clear-before-first-client and repeated clear remain idempotent.

## Release gate

The release AAR must contain arm64-v8a, armeabi-v7a, and x86_64 libraries. AGP
must resolve NDK 27.2.12479018 and strip release debug symbols; `.symtab` must be
absent while all current JNI exports remain in `.dynsym`, and 64-bit PT_LOAD
segments must remain 0x4000-aligned. Wrapper tests and the example compile must
pass against the local AAR. Do not publish to Maven Central during an
implementation spin.

For an owner-authorized exact-artifact release, Gradle properties for the
frozen AAR, sources JAR, javadoc JAR, their three SHA-256 values, and explicit
`sdkVersion` create a dedicated `exact` publication around only those files.
Its upload task is separate from the normal AGP-backed publication, so Gradle
module metadata and source generation cannot retain a compile/AAR/native
rebuild edge. All three exact files have no build dependency. The
`verifyExactAarPublication` task rechecks every SHA-256, coordinate
`ai.origon:sdk:<version>`, unique AAR binding, the classified sources/javadoc
bindings, and empty build dependencies before the exact publish/release tasks.
The generated exact POM must contain exactly Kotlin stdlib `2.1.0` at compile
scope plus serialization JSON `1.7.3` and coroutines core `1.8.1` at runtime
scope; clean consumers must retain the same dependency model as the normal
AGP-backed publication without pulling its build graph into the release.
The exact verifier is ordered before Central staging creation as well as upload
and repository release; invalid hashes or POM metadata must fail before any
remote staging mutation.
`scripts/verify-exact-release-aar.sh` additionally requires
the recorded hash of all three embedded libraries and runs the complete
stripped/JNI/retired-symbol/alignment verifier. The workspace release driver
must reject a Central dry-run containing any compile/assemble/AAR bundle/native
merge/strip task before an owner may authorize upload.
