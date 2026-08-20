package origon.example.android

import android.Manifest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import origon.example.android.services.CallForegroundService
import origon.example.android.services.CallHostCommand
import origon.example.android.services.CallHostGateResult
import origon.example.android.services.PromotedCallHost
import origon.example.android.services.awaitPromotedCallHost
import origon.example.android.services.callHostCommand
import origon.example.android.services.callHostBegan
import origon.example.android.services.callHostTerminal
import origon.example.android.services.callPermissionAllowsHost
import origon.example.android.services.CallService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CallHostGateTest {
    @Test
    fun `promotion acknowledgement precedes native-start authority`() = runBlocking {
        val order = mutableListOf<String>()
        val result = awaitPromotedCallHost(
            timeoutMillis = 100,
            connect = {
                order += "promoted"
                object : PromotedCallHost {
                    override fun beginCall() { order += "focus" }
                }
            },
            unwind = { order += "unwind" },
        )
        order += "native"

        assertEquals(CallHostGateResult.Ready, result)
        assertEquals(listOf("promoted", "focus", "native"), order)
    }

    @Test
    fun `promotion refusal and bind failure unwind without authority`() = runBlocking {
        var unwinds = 0
        val refused = awaitPromotedCallHost(100, connect = { null }, unwind = { unwinds++ })
        val bindFailure = awaitPromotedCallHost(
            100,
            connect = { error("bind refused") },
            unwind = { unwinds++ },
        )

        assertIs<CallHostGateResult.Failed>(refused)
        assertIs<CallHostGateResult.Failed>(bindFailure)
        assertEquals(2, unwinds)
    }

    @Test
    fun `five second policy deadline unwinds an unacknowledged host`() = runBlocking {
        var unwound = false
        val result = awaitPromotedCallHost(
            timeoutMillis = 10,
            connect = { delay(100); null },
            unwind = { unwound = true },
        )

        assertIs<CallHostGateResult.Failed>(result)
        assertEquals(true, unwound)
        assertEquals(5_000L, CallForegroundService.GATE_TIMEOUT_MS)
    }

    @Test
    fun `focus failure unwinds and repeated begin is host-idempotent`() = runBlocking {
        var unwinds = 0
        val failed = awaitPromotedCallHost(
            100,
            connect = {
                object : PromotedCallHost {
                    override fun beginCall() = error("focus failed")
                }
            },
            unwind = { unwinds++ },
        )
        var begun = false
        var starts = 0
        val host = object : PromotedCallHost {
            override fun beginCall() {
                if (!begun) starts++
                begun = true
            }
        }
        repeat(2) { assertEquals(CallHostGateResult.Ready, awaitPromotedCallHost(100, { host }) {}) }

        assertIs<CallHostGateResult.Failed>(failed)
        assertEquals(1, unwinds)
        assertEquals(1, starts)
    }

    @Test
    fun `hang-up and process recreation cannot resurrect a call host`() {
        assertEquals(CallHostCommand.HangUp, callHostCommand(CallForegroundService.ACTION_HANG_UP))
        assertEquals(CallHostCommand.Orphan, callHostCommand(null))
        assertEquals(CallHostCommand.Start, callHostCommand(CallForegroundService.ACTION_START))
        assertEquals(CallHostCommand.Orphan, callHostCommand("unexpected"))
    }

    @Test
    fun `notification and Bluetooth denial do not override microphone authority`() {
        val grants = mapOf(
            Manifest.permission.RECORD_AUDIO to true,
            Manifest.permission.POST_NOTIFICATIONS to false,
            Manifest.permission.BLUETOOTH_CONNECT to false,
        )
        assertEquals(true, callPermissionAllowsHost(grants, microphoneCurrentlyGranted = false))
        assertEquals(
            false,
            callPermissionAllowsHost(
                mapOf(Manifest.permission.RECORD_AUDIO to false),
                microphoneCurrentlyGranted = true,
            ),
        )
    }

    @Test
    fun `remote local and teardown phases all stop one begun host`() {
        assertEquals(true, callHostBegan(CallService.Phase.Connecting))
        assertEquals(true, callHostBegan(CallService.Phase.Connected))
        assertEquals(true, callHostBegan(CallService.Phase.Reconnecting))
        assertEquals(true, callHostTerminal(CallService.Phase.Ended("remote disconnect")))
        assertEquals(true, callHostTerminal(CallService.Phase.Ended(null)))
        assertEquals(true, callHostTerminal(CallService.Phase.Idle))
    }
}
