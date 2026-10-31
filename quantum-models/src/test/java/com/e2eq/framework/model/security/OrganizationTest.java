package com.e2eq.framework.model.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrganizationTest {

    @Test
    void acceptsSafeDirectoryReference() {
        Organization organization = new Organization();

        organization.setRefName("basf-global-bulk-shipper");

        assertEquals("basf-global-bulk-shipper", organization.getRefName());
    }

    @Test
    void acceptsDnsStyleOrganizationReference() {
        Organization organization = new Organization();

        organization.setRefName("basf.com");

        assertEquals("basf.com", organization.getRefName());
    }

    @Test
    void rejectsUnsafeOrganizationReference() {
        Organization organization = new Organization();

        assertThrows(IllegalArgumentException.class, () -> organization.setRefName("basf/com"));
    }
}
