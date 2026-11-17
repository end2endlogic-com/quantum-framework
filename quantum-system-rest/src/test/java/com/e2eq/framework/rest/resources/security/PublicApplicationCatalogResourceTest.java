package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.model.persistent.morphia.ApplicationRepo;
import com.e2eq.framework.model.security.Application;
import com.e2eq.framework.model.security.ApplicationCatalogEntry;
import com.e2eq.framework.model.security.ApplicationLifecycleState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The public catalog lists ACTIVE and DEPRECATED applications and caches its answer for a minute. */
class PublicApplicationCatalogResourceTest {

    @jakarta.enterprise.inject.Vetoed
    static final class Registry extends ApplicationRepo {
        final List<Application> rows = new ArrayList<>();
        final AtomicInteger reads = new AtomicInteger();

        @Override
        public List<Application> findCataloguedWithIgnoreRules() {
            reads.incrementAndGet();
            return List.copyOf(rows);
        }
    }

    @Test
    void theCatalogIsReadAtMostOncePerMinute() {
        Registry registry = new Registry();
        registry.rows.add(application("planner", ApplicationLifecycleState.ACTIVE));
        AtomicLong now = new AtomicLong(0);
        PublicApplicationCatalogResource resource = new PublicApplicationCatalogResource();
        resource.applicationRepo = registry;
        resource.clock = now::get;

        assertEquals(List.of("planner"), names(resource.catalog()));
        registry.rows.add(application("reports", ApplicationLifecycleState.ACTIVE));
        now.addAndGet(PublicApplicationCatalogResource.CACHE_TTL.toNanos() - 1);
        assertEquals(List.of("planner"), names(resource.catalog()), "served from the cache");
        assertEquals(1, registry.reads.get());

        now.addAndGet(1);
        assertEquals(List.of("planner", "reports"), names(resource.catalog()), "read again after the TTL");
        assertEquals(2, registry.reads.get());
    }

    @Test
    void previewAndOtherStatesAreNotListedEvenIfTheRegistryReturnsThem() {
        Registry registry = new Registry();
        for (ApplicationLifecycleState state : ApplicationLifecycleState.values()) {
            registry.rows.add(application("app-" + state.name().toLowerCase(), state));
        }
        PublicApplicationCatalogResource resource = new PublicApplicationCatalogResource();
        resource.applicationRepo = registry;
        assertEquals(List.of("app-active", "app-deprecated"), names(resource.catalog()));
    }

    private static Application application(String refName, ApplicationLifecycleState state) {
        Application application = new Application();
        application.setRefName(refName);
        application.setLifecycleState(state);
        return application;
    }

    private static List<String> names(List<ApplicationCatalogEntry> entries) {
        return entries.stream().map(ApplicationCatalogEntry::getRefName).toList();
    }
}
