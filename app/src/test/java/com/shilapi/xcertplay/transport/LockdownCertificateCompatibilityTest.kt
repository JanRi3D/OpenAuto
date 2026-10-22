package com.shilapi.xcertplay.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.bouncycastle.util.Properties
import org.junit.Assert.*
import org.junit.Test

class LockdownCertificateCompatibilityTest {
    @Test fun restoresPriorEffectiveTrueFalseAndNestedPolicies() {
        val previous = Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY)
        try {
            for (before in listOf(false, true)) {
                Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, before)
                LockdownCertificateCompatibility.withPinnedProfile {
                    assertTrue(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
                    LockdownCertificateCompatibility.withPinnedProfile {
                        assertTrue(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
                    }
                    assertTrue(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
                }
                assertEquals(before, Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
            }
        } finally { Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, previous) }
    }

    @Test fun exceptionsRestoreEffectiveStrictParsing() {
        val previous = Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, false)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                LockdownCertificateCompatibility.withPinnedProfile { throw IllegalArgumentException("synthetic failure") }
            }
            assertFalse(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
        } finally { Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, previous) }
    }

    @Test fun anotherThreadKeepsItsStrictPolicyDuringLockdownParsing() {
        val previous = Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, false)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, false)
            try {
                LockdownCertificateCompatibility.withPinnedProfile {
                    assertTrue(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
                    entered.countDown()
                    assertTrue(finish.await(2, TimeUnit.SECONDS))
                }
                assertFalse(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
            } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }
        worker.isDaemon = true
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(Properties.isOverrideSet(LockdownCertificateCompatibility.PROPERTY))
            finish.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Lockdown thread policy regression", it) }
        } finally {
            finish.countDown()
            worker.join(1_000)
            Properties.setThreadOverride(LockdownCertificateCompatibility.PROPERTY, previous)
        }
    }
}
