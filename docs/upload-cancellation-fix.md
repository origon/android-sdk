# Upload cancellation wrapper correction

## Publication follow-up — 0.3.7

Version 0.3.7 is published on Maven Central. Its AAR SHA256 exactly matches the
candidate below. Re-ran the complete AAR verifier against the published artifact:
all three architectures pass. The example and installation instructions now use
this released coordinate. The pending-publication notes below describe the
original candidate validation, and are superseded by this follow-up.

## Problem and change

Published 0.3.6 exposes Kotlin deleteAttachment, but its native library exports
cancelUpload instead. Calling the obsolete method fails JNI linkage. End Chat's
binding is present and unaffected.

OrigonClient.cancelUpload(String): Boolean and the exact JNI bridge
(Long, String): Boolean now match workspace/apps/sdk/session/src/jni_bridge.rs.
The obsolete API is removed. This is local cancellation only: no server DELETE,
no configuration-authority requirement, and false for an absent/settled upload.
The example cancels uploading tiles by uploadId and removes completed tiles only
from local draft state. Errors are surfaced through its existing UI error flow.

The planned consumer version is 0.3.7, pending owner publication. The workspace
Android app remains pinned to published 0.3.6 until that release exists; it does
not call the obsolete binding. Its separate upload-cancellation wiring TODO stays
open. No backend, Apple, or web SDK changes are necessary.

## Validation — 2026-09-29

Source base: android-sdk 1d87716. Built on the authorized Mac in a disposable
directory with Java 21 and Android NDK 27.2.12479018. Native .so inputs came from
the published 0.3.6 AAR; only the Kotlin wrapper and example changed. No release
was published and no shared Maven coordinates were overwritten.

- :sdk:testDebugUnitTest :sdk:assembleRelease: 30 tests pass, release AAR builds.
- scripts/verify-release-aar.sh: passes all declared bridge exports for arm64-v8a,
  armeabi-v7a and x86_64, stripping and applicable 16 KiB alignment checks.
- Added reflection tests requiring Boolean cancellation return values and absence
  of deleteAttachment. AAR verification also requires the corrected public API
  and native bridge declaration, and rejects the retired method/export.
- Exact candidate AAR SHA256:
  4f7df239061bc56e4929ae0dda025f8df65cfec5a39afe2d949322762f26d679.
- Example :app:testDebugUnitTest :app:assembleDebug: 36 tests pass; APK builds
  against that exact AAR under 0.0.0-cancel-validation in a disposable local Maven
  repository. Default 0.3.7 resolution awaits owner publication.
- bash -n scripts/verify-release-aar.sh and git diff --check pass.
- No live upload cancellation or physical-device runtime acceptance is claimed.

Task-created Mac build directories and local scratch scripts are removed after
recording the results. The release operator must rebuild and verify the final
published AAR; this candidate does not prove a later artifact's bytes.
