package ai.origon.sdk.bridge

/**
 * Per-attachment-type rule.
 *
 * **ABI-locked constructor signature** — `(ZI)V`. The Rust JNI bridge
 * instantiates via reflection.
 */
internal data class AttachmentRule(
    val enabled: Boolean,
    /** Maximum allowed size in megabytes. */
    val maxSize: Int,
)

/**
 * Returned by `SessionBridge.getAttachmentPolicy`. Mirrors the TOP-LEVEL
 * `attachmentPolicy` block in the `/config` body — NOT nested under `chat`.
 * (Producer: `platform/connect` CONTRACT.md §3. The native layer reads the
 * top-level key in `apps/sdk` `session_manager.rs`; this comment claimed the
 * nested path until 2026-07-28.)
 *
 * **ABI-locked constructor signature** — four `AttachmentRule`s in
 * the order shown.
 */
internal data class AttachmentPolicy(
    val images: AttachmentRule,
    val documents: AttachmentRule,
    val videos: AttachmentRule,
    val audio: AttachmentRule,
)
