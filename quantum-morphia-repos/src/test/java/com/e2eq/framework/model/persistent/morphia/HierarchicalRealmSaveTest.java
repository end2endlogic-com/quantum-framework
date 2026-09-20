package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.base.HierarchicalModel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HierarchicalRealmSaveTest {
    @SuppressWarnings("rawtypes")
    static class Probe extends HierarchicalRepo {
        boolean invoked;
        @Override public String getSecurityContextRealmId() { return "admitted-realm"; }
        @Override public HierarchicalModel save(HierarchicalModel value) { invoked = true; return value; }
    }
    @Test void explicitRealmSaveDispatchesToHierarchyOverride() {
        Probe probe = new Probe();
        probe.save("admitted-realm", null);
        assertTrue(probe.invoked);
    }
    @Test void mismatchedRealmDoesNotWrite() {
        Probe probe = new Probe();
        assertThrows(SecurityException.class, () -> probe.save("other-realm", null));
        assertFalse(probe.invoked);
    }
}
