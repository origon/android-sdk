package ai.origon.sdk.bridge

/**
 * Event from the session crate's bounded event channel, populated by
 * `SessionBridge.pollEvent`.
 *
 * **ABI-locked layout.** The Rust JNI bridge populates fields by name
 * via JNI field IDs. Field names + types here must match
 * `client-sdk/session/src/jni_bridge.rs::build_event_object`. Mutable
 * `@JvmField` is used so the Rust side can populate without going
 * through Kotlin getters/setters.
 *
 * Variant fields are only meaningful for matching `kind` values:
 *
 * | `kind`                           | populated fields                                                       |
 * | -------------------------------- | ---------------------------------------------------------------------- |
 * | `EVENT_MESSAGE_ADDED`            | `sessionId`, `messageJson` (Message serialized as JSON)                |
 * | `EVENT_MESSAGE_UPDATED`          | `sessionId`, `messageJson`, `updateId` (provisional localId or message.id) |
 * | `EVENT_SESSION_UPDATED`          | `sessionId`, `newSessionId`                                             |
 * | `EVENT_CONTROL_UPDATED`          | `sessionId`, `control`                                                  |
 * | `EVENT_TYPING`                   | `sessionId`, `typing`                                                   |
 * | `EVENT_CONNECTED`                | `sessionId`                                                             |
 * | `EVENT_RECONNECTING`             | `sessionId`, `reconnectAttempt`, `disconnectReasonKind` (+ server* if SERVER_CLOSED) |
 * | `EVENT_RECONNECTED`              | `sessionId`                                                             |
 * | `EVENT_PEER_ATTACHED`            | `sessionId`, `peerEndpointId`, `peerAlias`                              |
 * | `EVENT_PEER_DETACHED`            | `sessionId`, `peerEndpointId`, `peerAlias`                              |
 * | `EVENT_DISCONNECTED`             | `sessionId`, `disconnectReasonKind` (+ server* if SERVER_CLOSED)        |
 * | `EVENT_CALL_ERROR`               | `sessionId`, `callErrorPresent`, `callErrorMessage`                     |
 * | `EVENT_AUDIO_ROUTE_CHANGED`      | `sessionId`, `audioRoute`                                              |
 * | `EVENT_CHAT_SESSION_ENDED`       | `sessionId`, `messageJson` (`{reason, acw?}` serialized as JSON)        |
 */
internal class SessionEvent {
    @JvmField var kind: Int = 0
    @JvmField var sessionId: String? = null

    /** EVENT_SESSION_UPDATED — the new id after rotation. */
    @JvmField var newSessionId: String? = null

    /** EVENT_CONTROL_UPDATED — `SessionBridge.CONTROL_AI` or `CONTROL_USER`. */
    @JvmField var control: Int = 0

    /** EVENT_TYPING — true when remote started typing, false when stopped. */
    @JvmField var typing: Boolean = false

    /** EVENT_RECONNECTING — 1-indexed attempt counter. */
    @JvmField var reconnectAttempt: Int = 0

    /** EVENT_RECONNECTING / EVENT_DISCONNECTED — `SessionBridge.DISCONNECT_REASON_*`. */
    @JvmField var disconnectReasonKind: Int = 0

    /** EVENT_RECONNECTING / EVENT_DISCONNECTED + DISCONNECT_REASON_SERVER_CLOSED — wire code. */
    @JvmField var disconnectReasonServerCode: Long = 0

    /** EVENT_RECONNECTING / EVENT_DISCONNECTED + DISCONNECT_REASON_SERVER_CLOSED — detail string. */
    @JvmField var disconnectReasonServerDetail: String? = null

    /** EVENT_PEER_ATTACHED / EVENT_PEER_DETACHED — stable peer endpoint id. */
    @JvmField var peerEndpointId: String? = null

    /** EVENT_PEER_ATTACHED / EVENT_PEER_DETACHED — track alias. */
    @JvmField var peerAlias: Long = 0

    /** EVENT_CALL_ERROR — error message text. Null when error cleared (paired with `callErrorPresent == false`). */
    @JvmField var callErrorMessage: String? = null

    /** EVENT_CALL_ERROR — true = error present, false = error cleared. */
    @JvmField var callErrorPresent: Boolean = false

    /** EVENT_MESSAGE_ADDED / EVENT_MESSAGE_UPDATED — `Message` payload as JSON. */
    @JvmField var messageJson: String? = null

    /** EVENT_MESSAGE_UPDATED — row lookup id (= provisional `localId` for outbound ack/failure, or `message.id` for server-driven updates). */
    @JvmField var updateId: String? = null

    /** EVENT_AUDIO_ROUTE_CHANGED — now-current route (`AUDIO_OUTPUT_*`: 0 = default, 1 = speaker, 2 = bluetooth). */
    @JvmField var audioRoute: Int = 0
}
