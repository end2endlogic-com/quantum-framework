package com.e2eq.framework.actionenablement.runtime;

import com.e2eq.framework.actionenablement.model.DependencyCheckRef;
import com.e2eq.framework.actionenablement.model.EnablementBlocker;
import com.e2eq.framework.actionenablement.model.EnablementImpact;
import com.e2eq.framework.actionenablement.model.ScopedActionEnablementRequest;
import com.e2eq.framework.actionenablement.model.ScopedActionEnablementStatus;
import com.e2eq.framework.actionenablement.model.ScopedActionRef;
import com.e2eq.framework.actionenablement.model.ScopedActionRequirement;
import com.e2eq.framework.actionenablement.spi.ActionDependencyResolver;
import com.e2eq.framework.actionenablement.spi.ScopedActionRequirementRegistry;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.util.SecurityUtils;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedActionEnablementServiceTest {

    @AfterEach
    void clearSecurityContext() {
        com.e2eq.framework.model.securityrules.SecurityContext.clear();
    }

    @Test
    void evaluateAggregatesAllowedEnabledAndReadyBlockers() {
        ScopedActionRef action = ScopedActionRef.builder()
                .area("system")
                .functionalDomain("action-enablement")
                .action("check")
                .build();

        ScopedActionRequirement requirement = ScopedActionRequirement.builder()
                .scopedAction(action)
                .displayName("Check")
                .dependencies(List.of(
                        DependencyCheckRef.builder().type("permission").build(),
                        DependencyCheckRef.builder().type("feature-flag").refName("enablement-ui").build(),
                        DependencyCheckRef.builder().type("setting-present").refName("quantum.enablement.ready").build()
                ))
                .build();

        ScopedActionEnablementService service = new ScopedActionEnablementService();
        service.requirementRegistry = new FixedRequirementRegistry(requirement);
        service.resolvers = new FixedInstance<>(List.of(
                resolver("permission", blocker(EnablementImpact.ALLOWED, "permission-denied")),
                resolver("feature-flag", blocker(EnablementImpact.ENABLED, "feature-flag-disabled")),
                resolver("setting-present", blocker(EnablementImpact.READY, "setting-missing"))
        ));
        service.securityUtils = testSecurityUtils();

        ScopedActionEnablementStatus status = service.evaluate(ScopedActionEnablementRequest.builder()
                        .identity("tester@example.com")
                        .realm("demo-realm")
                        .actions(List.of(action))
                        .build())
                .get(0);

        assertFalse(status.isAllowed());
        assertFalse(status.isEnabled());
        assertFalse(status.isReady());
        assertFalse(status.isUsable());
        assertEquals(List.of("permission-denied", "feature-flag-disabled", "setting-missing"),
                status.getBlockers().stream().map(EnablementBlocker::getCode).toList());
    }

    @Test
    void evaluateUsesRegisteredManifestWithoutAddingSyntheticBlocker() {
        ScopedActionRef action = ScopedActionRef.builder()
                .area("system")
                .functionalDomain("action-enablement")
                .action("view")
                .build();

        ScopedActionRequirement requirement = ScopedActionRequirement.builder()
                .scopedAction(action)
                .displayName("View")
                .dependencies(List.of(
                        DependencyCheckRef.builder().type("permission").build()
                ))
                .build();

        ScopedActionEnablementService service = new ScopedActionEnablementService();
        service.requirementRegistry = new FixedRequirementRegistry(requirement);
        service.resolvers = new FixedInstance<>(List.of(
                resolver("permission", null)
        ));
        service.securityUtils = testSecurityUtils();

        ScopedActionEnablementStatus status = service.evaluate(ScopedActionEnablementRequest.builder()
                        .identity("tester@example.com")
                        .realm("demo-realm")
                        .actions(List.of(action))
                        .build())
                .get(0);

        assertTrue(status.isAllowed());
        assertTrue(status.isEnabled());
        assertTrue(status.isReady());
        assertTrue(status.isUsable());
        assertTrue(status.getBlockers().isEmpty());
    }

    private static ActionDependencyResolver resolver(String type, EnablementBlocker blocker) {
        return new ActionDependencyResolver() {
            @Override
            public String supportsType() {
                return type;
            }

            @Override
            public DependencyResolutionResult evaluate(DependencyCheckRef dependency, EnablementEvaluationContext context) {
                return blocker == null ? DependencyResolutionResult.satisfied() : DependencyResolutionResult.blocked(blocker);
            }
        };
    }

    private static EnablementBlocker blocker(EnablementImpact impact, String code) {
        return EnablementBlocker.builder()
                .impact(impact)
                .type("test")
                .code(code)
                .message(code)
                .severity("error")
                .build();
    }

    private static SecurityUtils testSecurityUtils() {
        return new SecurityUtils() {
            @Override
            public DataDomain getDefaultDataDomain() {
                return new DataDomain("demo-org", "1234567890", "demo-tenant", 0, "default-owner");
            }

            @Override
            public DomainContext getDefaultDomainContext() {
                return DomainContext.builder()
                        .tenantId("demo-tenant")
                        .defaultRealm("demo-realm")
                        .orgRefName("demo-org")
                        .accountId("1234567890")
                        .dataSegment(0)
                        .build();
            }
        };
    }

    private static final class FixedRequirementRegistry implements ScopedActionRequirementRegistry {
        private final ScopedActionRequirement requirement;

        private FixedRequirementRegistry(ScopedActionRequirement requirement) {
            this.requirement = requirement;
        }

        @Override
        public Optional<ScopedActionRequirement> find(ScopedActionRef ref) {
            if (requirement.getScopedAction().toUriString().equals(ref.toUriString())) {
                return Optional.of(requirement);
            }
            return Optional.empty();
        }

        @Override
        public List<ScopedActionRequirement> list() {
            return List.of(requirement);
        }
    }

    private static final class FixedInstance<T> implements Instance<T> {
        private final List<T> values;

        private FixedInstance(List<T> values) {
            this.values = values;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return values.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return values.size() > 1;
        }

        @Override
        public void destroy(T instance) {
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            return List.of();
        }

        @Override
        public Iterator<T> iterator() {
            return values.iterator();
        }

        @Override
        public T get() {
            if (values.isEmpty()) {
                throw new IllegalStateException("No values available");
            }
            return values.get(0);
        }
    }
}
