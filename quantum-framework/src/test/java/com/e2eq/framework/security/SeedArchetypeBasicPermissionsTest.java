package com.e2eq.framework.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.seeds.ArchetypeSeeder;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.control.ActivateRequestContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
public class SeedArchetypeBasicPermissionsTest extends BaseRepoTest {

    @Test
    @ActivateRequestContext
    @TestSecurity(user = "system@end2endlogic.com", roles = {"admin"})
    public void parseSeedAndSummarize() {
        String realm = testUtils.getTestRealm();
        DataDomain dd = new DataDomain("end2endlogic", "0000000001", "tenant-seed-1", 0, "system@end2endlogic.com");

        ArchetypeSeeder.ImportResult res = ArchetypeSeeder.importArchetype("basic-permissions-v1-test", realm, dd);
        assertNotNull(res, "ImportResult should not be null");
        assertTrue(res.total > 0, "Seed should contain at least one record");
        assertTrue(res.functionalDomainCount > 0, "Seed should define at least one functional domain");
        assertTrue(res.policyCount > 0, "Seed should define at least one starter policy");
        assertTrue(res.userCount >= 3, "Seed should define at least three users (user, power_user, tenant_admin)");
        assertEquals("basic-permissions-v1-test", res.archetypeKey);
        assertEquals(realm, res.realm);
    }
}
