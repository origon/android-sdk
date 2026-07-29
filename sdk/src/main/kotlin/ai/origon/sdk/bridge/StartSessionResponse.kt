package ai.origon.sdk.bridge

/**
 * Response from `SessionBridge.startCall` / `SessionBridge.startChat`.
 *
 * **ABI-locked constructor signature.** The Rust JNI bridge
 * (`client-sdk/session/src/jni_bridge.rs::build_start_response_object`)
 * instantiates this via reflection looking up
 * `(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V`. Don't
 * change the parameter order or types.
 *
 * Mirrors the Rust `StartSessionResponse` shape (wire keys
 * `sessionId`, `url`, `token`).
 */
internal data class StartSessionResponse(
    /** Identifier for the session, used by subsequent calls. Wire key: `sessionId`. */
    val sessionId: String,
    /** Transport URL — MOQ for Voice, HTTPS SSE for Chat. */
    val url: String,
    /** Per-session auth token, scoped to this session only. Wire key: `token`. */
    val token: String,
)
