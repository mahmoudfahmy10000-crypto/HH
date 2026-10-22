package com.bluewave.remote

import org.junit.Assert.*
import org.junit.Test

class AccessPolicyTest {
    @Test
    fun localWifiAndEthernetDoNotRequireInternetValidation() {
        assertNull(AccessPolicy.error(false, true, NetworkState(available = true, wifi = true)))
        assertNull(
            AccessPolicy.error(false, false, NetworkState(available = true, ethernet = true))
        )
    }

    @Test
    fun cellularAndOtherNetworksRequireRemoteVpnProfile() {
        assertNotNull(
            AccessPolicy.error(false, true, NetworkState(available = true, cellular = true))
        )
        assertNotNull(AccessPolicy.error(false, true, NetworkState(available = true)))
        assertNotNull(AccessPolicy.error(true, true, NetworkState(available = true, wifi = true)))
    }

    @Test
    fun remoteNeedsBothVpnAndTls() {
        assertNull(
            AccessPolicy.error(
                true,
                true,
                NetworkState(available = true, vpn = true, cellular = true),
            )
        )
        assertNotNull(AccessPolicy.error(true, false, NetworkState(available = true, vpn = true)))
        assertNotNull(
            AccessPolicy.error(false, true, NetworkState(available = true, vpn = true, wifi = true))
        )
    }

    @Test
    fun vpnLossAndOfflineAreBlocked() {
        assertNotNull(
            AccessPolicy.error(true, true, NetworkState(available = true, cellular = true))
        )
        assertNotNull(AccessPolicy.error(true, true, NetworkState()))
        assertNotNull(AccessPolicy.error(false, true, NetworkState()))
    }

    @Test
    fun transportLabelNeverClaimsHomeOrRouteVerification() {
        assertTrue(NetworkState(available = true, wifi = true).summary.contains("not verified"))
        assertTrue(NetworkState(available = true, vpn = true).summary.contains("not verified"))
    }

    @Test
    fun reconnectDelaysAreBoundedAndRejectFurtherAttempts() {
        assertEquals(
            listOf(2000L, 4000L, 8000L),
            (1..ReconnectPolicy.MAX_ATTEMPTS).map(ReconnectPolicy::delayMillis),
        )
        try {
            ReconnectPolicy.delayMillis(4)
            fail("Unbounded retry allowed")
        } catch (_: IllegalArgumentException) {}
    }
}
