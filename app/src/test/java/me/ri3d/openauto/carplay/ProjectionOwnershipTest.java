package me.ri3d.openauto.carplay;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProjectionOwnershipTest {
    @Test public void protocolsCannotBothAcquireTheDeviceEndpoints() {
        ProjectionOwnership.release("carplay"); ProjectionOwnership.release("androidauto");
        assertTrue(ProjectionOwnership.acquire("carplay"));
        assertFalse(ProjectionOwnership.acquire("androidauto"));
        ProjectionOwnership.release("androidauto");
        assertFalse(ProjectionOwnership.acquire("androidauto"));
        ProjectionOwnership.release("carplay");
        assertTrue(ProjectionOwnership.acquire("androidauto"));
        ProjectionOwnership.release("androidauto");
    }
}
