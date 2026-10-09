package com.tg.dbisland

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSecurityTest {
    @Test
    fun unknownBroadcastSenderIsRejected() {
        assertFalse(BridgeSecurity.allowBroadcast(null, null))
    }

    @Test
    fun relayRejectsUnknownAndUntrustedSenders() {
        for (uid in listOf(-1, -2, Int.MIN_VALUE, 1000, 12345)) {
            assertFalse("sender uid=$uid", BridgeSecurity.allowEventRelaySender(null, uid))
        }
        assertTrue(BridgeSecurity.allowEventRelaySender(null, 0))
        assertTrue(BridgeSecurity.allowEventRelaySender(null, 2000))
    }

    @Test
    fun externalEventsCannotInvokeLocalSimulationActions() {
        for (type in listOf("sim.reply", "sim.action", "sim.overlay", "sim.type",
            "sim.expand", "sim.collapse", "sim.future_action", "", " ")) {
            assertFalse(type, BridgeSecurity.isIncomingEventAllowed(type))
        }
    }

    @Test
    fun ordinaryEventsAndReceiptsRemainAllowed() {
        for (type in listOf("chat.start", "chat.delta", "chat.end", "chat.reply",
            "chat.conv", "send.result", "reply.stalled", "keepalive", "ping", "silent.ping")) {
            assertTrue(type, BridgeSecurity.isIncomingEventAllowed(type))
        }
    }

    @Test
    fun permissionProtectedCommandsKeepTheirIndependentPolicy() {
        // 该通道在注册时已有系统强制的签名权限，不能与公开事件通道混用。
        assertTrue(BridgeSecurity.allowCommandSender(null, -1))
        assertFalse(BridgeSecurity.allowCommandSender(null, 12345))
    }
}
