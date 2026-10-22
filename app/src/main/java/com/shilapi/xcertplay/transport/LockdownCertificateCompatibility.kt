package com.shilapi.xcertplay.transport

import org.bouncycastle.util.Properties

/**
 * Lockdown certificates use the pinned accessory profile's empty issuer DN, rather than PKIX
 * naming. Permit only that parsing concession on the caller's thread; peer public-key pinning
 * remains mandatory in LockdownTlsEngineFactory. No system or security property is changed.
 *
 * BC's public API reports the previous effective boolean, not the presence of a hidden map
 * entry. Restoring that effective policy can leave an explicit false where no override existed.
 * This application never changes the global policy, so its effective strictness is preserved.
 */
internal object LockdownCertificateCompatibility {
    internal const val PROPERTY = Properties.X509_ALLOW_EMPTY_ISSUER_CERT

    fun <T> withPinnedProfile(block: () -> T): T {
        val previous = Properties.setThreadOverride(PROPERTY, true)
        try {
            check(Properties.isOverrideSet(PROPERTY)) { "Thread-local Lockdown certificate profile is prohibited by provider policy" }
            return block()
        } finally {
            Properties.setThreadOverride(PROPERTY, previous)
        }
    }
}
