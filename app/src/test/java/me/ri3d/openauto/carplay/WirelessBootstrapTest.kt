package me.ri3d.openauto.carplay
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException

class WirelessBootstrapTest {
    @Test fun successfulAuthenticatedHandoffSurvivesTheIntentionalBluetoothClose() {
        val scope=AttemptScope();var ready=false;var closes=0
        val channel=Closeable{closes++};scope.own(channel)
        WirelessBootstrap.run(scope,channel,{ready}) {ready=true;throw IOException("closed during recv")}
        assertTrue(scope.isOpen);assertEquals(1,closes)
        scope.close();assertEquals(1,closes)
    }
    @Test fun transportFailureBeforeHandoffPropagatesAndStillCloses() {
        val scope=AttemptScope();var closes=0
        val channel=Closeable{closes++};scope.own(channel)
        try {WirelessBootstrap.run(scope,channel,{false}){throw IOException("peer vanished")};fail("connection failure swallowed")}
        catch(expected:IOException){assertEquals("peer vanished",expected.message)}
        assertEquals(1,closes)
    }
    @Test fun successfulHandoffSurvivesAnIOExceptionFromClosingBluetooth() {
        val scope=AttemptScope();var closes=0
        val channel=Closeable{closes++;throw IOException("already disconnected")};scope.own(channel)
        WirelessBootstrap.run(scope,channel,{true}){}
        assertTrue(scope.isOpen);assertEquals(1,closes)
        scope.close();assertEquals(1,closes)
    }
    @Test fun cancellationSurvivesAnIOExceptionFromClosingBluetooth() {
        val scope=AttemptScope();scope.close()
        WirelessBootstrap.run(scope,Closeable{throw IOException("closed")},{false}){}
    }
    @Test fun cleanupFailureDoesNotReplaceTheOriginalTransportFailure() {
        val scope=AttemptScope()
        try {
            WirelessBootstrap.run(scope,Closeable{throw IOException("cleanup failure")},{false}) {
                throw IOException("original transport failure")
            }
            fail("connection failure swallowed")
        }catch(expected:IOException){assertEquals("original transport failure",expected.message)}
    }
    @Test fun cleanupFailureBeforeHandoffPropagates() {
        try {
            WirelessBootstrap.run(AttemptScope(),Closeable{throw IOException("cleanup failure")},{false}){}
            fail("cleanup failure swallowed before handoff")
        }catch(expected:IOException){assertEquals("cleanup failure",expected.message)}
    }
}
