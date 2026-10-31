package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.security.Policy;
import com.e2eq.framework.rest.models.Collection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class PolicyResourceListMergeTest {

    @Test
    void mergesDefaultPoliciesWhenDatabaseCollectionIsEmpty() {
        Policy systemDefault = new Policy();
        systemDefault.setRefName("system-default-user");
        systemDefault.setDisplayName("System Default: user");
        Collection<Policy> database = new Collection<>(List.of(), 0, 250, null, 0L);

        Collection<Policy> merged = PolicyResource.mergePolicyCollections(
                List.of(systemDefault),
                database,
                null,
                0,
                250,
                "helixor-code-P1",
                "quantum-auth");

        assertEquals(1, merged.getRows().size());
        assertSame(systemDefault, merged.getRows().get(0));
        assertEquals(1L, merged.getTotalCount());
        assertEquals("helixor-code-P1", merged.getRealm());
    }

    @Test
    void keepsDatabasePoliciesAfterDefaults() {
        Policy systemDefault = new Policy();
        systemDefault.setRefName("system-default-admin");
        Policy stored = new Policy();
        stored.setRefName("b2bi-exchange-admin");
        Collection<Policy> database = new Collection<>(List.of(stored), 0, 50, null, 1L);

        Collection<Policy> merged = PolicyResource.mergePolicyCollections(
                List.of(systemDefault),
                database,
                null,
                0,
                50,
                null,
                "quantum-auth");

        assertEquals(List.of("system-default-admin", "b2bi-exchange-admin"),
                merged.getRows().stream().map(Policy::getRefName).toList());
        assertEquals(2L, merged.getTotalCount());
        assertEquals("quantum-auth", merged.getRealm());
    }
}
