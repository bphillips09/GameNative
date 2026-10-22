package com.winlator.core.envvars

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EnvVarRedactionTest {

    @Test
    fun redactsXboxTokenAndBridgeKey() {
        val envVars = EnvVars().apply {
            put("WINEDEBUG", "-all")
            put("XODUS_XSTS_PLAYFAB", "XBL3.0 x=1;token")
            put("XODUS_BRIDGE_PORT", "39769")
            put("XODUS_BRIDGE_KEY", "457e4b5e")
        }

        val logged = EnvVarRedaction.redact(envVars)

        assertEquals(
            "WINEDEBUG=-all XODUS_XSTS_PLAYFAB=<redacted> XODUS_BRIDGE_PORT=39769 XODUS_BRIDGE_KEY=<redacted>",
            logged,
        )
        assertFalse(logged.contains("token"))
    }

    @Test
    fun redactsXboxTokenInExecEnvironment() {
        // ProcessHelper logs the raw envp, where the token's space and ';' are not escaped.
        val envp = arrayOf("DISPLAY=:0", "XODUS_XSTS_PLAYFAB=XBL3.0 x=1;token", "XODUS_BRIDGE_KEY=457e4b5e")

        assertEquals(
            "[DISPLAY=:0, XODUS_XSTS_PLAYFAB=<redacted>, XODUS_BRIDGE_KEY=<redacted>]",
            EnvVarRedaction.redact(envp),
        )
    }
}
