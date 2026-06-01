package com.e2eq.framework.rest.models;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RealmInfoTest {

    @Test
    void recordFieldsAreAccessible() {
        RealmInfo info = new RealmInfo("acme-realm", "tenant-42");
        assertEquals("acme-realm", info.refName());
        assertEquals("tenant-42", info.tenantId());
    }

    @Test
    void nullTenantIdIsPermitted() {
        RealmInfo info = new RealmInfo("some-realm", null);
        assertEquals("some-realm", info.refName());
        assertNull(info.tenantId());
    }

    @Test
    void equalityIsValueBased() {
        RealmInfo a = new RealmInfo("r1", "t1");
        RealmInfo b = new RealmInfo("r1", "t1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentInstancesAreNotEqual() {
        RealmInfo a = new RealmInfo("r1", "t1");
        RealmInfo b = new RealmInfo("r1", "t2");
        assertNotEquals(a, b);
    }
}
