package me.ri3d.openauto.carplay
import org.junit.Assert.*
import org.junit.Test

class PrimarySessionRegistryTest {
    data class Session(var token:Any?=null,var closed:Boolean=false)
    @Test fun audioRecordCannotReplaceThePrimaryScreenInputOwner() {
        val registry=PrimarySessionRegistry<Session>({it.token},{it.closed})
        val screen=Session(Any());val audio=Session()
        registry.add(screen);registry.add(audio)
        assertSame(screen,registry.primary())
        registry.remove(audio)
        assertSame(screen,registry.primary())
    }
    @Test fun tokenAppearingAfterRecordIsDiscoveredAndOldCloseCannotEraseSuccessor() {
        val registry=PrimarySessionRegistry<Session>({it.token},{it.closed})
        val old=Session();registry.add(old);assertNull(registry.primary())
        old.token=Any();assertSame(old,registry.primary())
        old.closed=true;val next=Session(Any());registry.add(next)
        registry.remove(old);assertSame(next,registry.primary())
    }
    @Test fun closedSessionsAreReleasedFromRegistry() {
        val registry=PrimarySessionRegistry<Session>({it.token},{it.closed})
        repeat(1000){val s=Session(Any());registry.add(s);registry.remove(s)}
        assertEquals(0,registry.size())
    }
}
