package com.e2eq.framework.rest.filters;

import com.e2eq.framework.rest.usage.UsageGovernanceFilter;
import jakarta.annotation.Priority;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecurityUsageFilterOrderingTest {

    @Test
    void governanceRunsAfterTrustedIdentitySetupAndBeforeThreadLocalCleanup() {
        Priority securityPriority = SecurityFilter.class.getAnnotation(Priority.class);
        Priority governancePriority = UsageGovernanceFilter.class.getAnnotation(Priority.class);

        assertEquals(SecurityFilter.SECURITY_FILTER_PRIORITY, securityPriority.value());
        assertEquals(UsageGovernanceFilter.USAGE_GOVERNANCE_PRIORITY, governancePriority.value());

        List<Class<?>> filters = List.of(UsageGovernanceFilter.class, SecurityFilter.class);
        Comparator<Class<?>> byPriority = Comparator.comparingInt(
                type -> type.getAnnotation(Priority.class).value());

        // JAX-RS request filters are ascending: SecurityFilter establishes the
        // trusted context before governance resolves and stores identity.
        assertEquals(List.of(SecurityFilter.class, UsageGovernanceFilter.class),
                filters.stream().sorted(byPriority).toList());

        // JAX-RS response filters are descending: governance emits its response
        // observation before SecurityFilter clears ThreadLocal security context.
        assertEquals(List.of(UsageGovernanceFilter.class, SecurityFilter.class),
                filters.stream().sorted(byPriority.reversed()).toList());
    }
}
