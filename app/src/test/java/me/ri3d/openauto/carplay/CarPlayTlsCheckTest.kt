package me.ri3d.openauto.carplay

import java.security.Security
import org.junit.Assert.*
import org.junit.Test

class CarPlayTlsCheckTest {
    @Test(timeout = 40_000) fun productionTlsCarriesBidirectionalSyntheticDataWithoutInstallingProviders() {
        val providers = Security.getProviders().map { it.name }
        val result = CarPlayTlsCheck.run()
        assertTrue(result.startsWith("LOCAL SYNTHETIC ONLY:"))
        assertTrue(result.contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
        assertTrue(result.contains("application bytes both ways"))
        assertTrue(result.contains("direct close passed"))
        assertEquals(providers, Security.getProviders().map { it.name })
    }
}
