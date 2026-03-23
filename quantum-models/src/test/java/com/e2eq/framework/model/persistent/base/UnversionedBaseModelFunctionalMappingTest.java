package com.e2eq.framework.model.persistent.base;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UnversionedBaseModelFunctionalMappingTest {

    @FunctionalMapping(area = "TEST_AREA", domain = "TEST_DOMAIN")
    static class AnnotatedModel extends UnversionedBaseModel {}

    static class UnannotatedModel extends UnversionedBaseModel {}

    static class OverriddenModel extends UnversionedBaseModel {
        @Override
        public String bmFunctionalArea() {
            return "OVERRIDDEN_AREA";
        }

        @Override
        public String bmFunctionalDomain() {
            return "OVERRIDDEN_DOMAIN";
        }
    }

    @BeforeEach
    void setUp() {
        DataDomain dd = DataDomain.builder()
                .orgRefName("testOrg")
                .accountNum("testAccount")
                .tenantId("testTenant")
                .ownerId("testOwner")
                .build();
        PrincipalContext pc = new PrincipalContext.Builder()
                .withUserId("testUser")
                .withDataDomain(dd)
                .withDefaultRealm("testRealm")
                .withScope("testScope")
                .build();
        SecurityContext.setPrincipalContext(pc);
    }

    @AfterEach
    void tearDown() {
        SecurityContext.clear();
    }

    @Test
    void testAnnotatedModel() {
        AnnotatedModel model = new AnnotatedModel();
        assertEquals("TEST_AREA", model.bmFunctionalArea());
        assertEquals("TEST_DOMAIN", model.bmFunctionalDomain());
    }

    @Test
    void testUnannotatedModel() {
        UnannotatedModel model = new UnannotatedModel();
        assertNull(model.bmFunctionalArea());
        assertNull(model.bmFunctionalDomain());
    }

    @Test
    void testOverriddenModel() {
        OverriddenModel model = new OverriddenModel();
        assertEquals("OVERRIDDEN_AREA", model.bmFunctionalArea());
        assertEquals("OVERRIDDEN_DOMAIN", model.bmFunctionalDomain());
    }
}
