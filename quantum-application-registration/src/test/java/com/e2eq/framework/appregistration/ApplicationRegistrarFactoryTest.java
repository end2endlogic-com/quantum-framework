package com.e2eq.framework.appregistration;

import com.e2eq.framework.appregistration.model.RegistrationContext;
import com.e2eq.framework.appregistration.model.RegistrationOutcome;
import com.e2eq.framework.appregistration.runtime.ApplicationRegistrarFactory;
import com.e2eq.framework.appregistration.spi.ApplicationRegistrar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationRegistrarFactoryTest {

    static final class StubRegistrar implements ApplicationRegistrar {
        int registerCalls = 0;
        boolean registered = false;

        @Override public String getName() { return "stub"; }

        @Override public boolean isRegistered(RegistrationContext context) { return registered; }

        @Override public RegistrationOutcome register(RegistrationContext context) {
            registerCalls++;
            registered = true;
            return RegistrationOutcome.registered("stub registered " + context.getApplicationId());
        }
    }

    private static ApplicationRegistrarFactory factoryWith(ApplicationRegistrar... registrars) {
        return new ApplicationRegistrarFactory(java.util.List.of(registrars));
    }

    private static RegistrationContext context() {
        return RegistrationContext.builder()
                .applicationId("app").applicationVersion("1.0.0").environmentRef("local")
                .requiredRealm("r1").build();
    }

    @Test
    void resolvesByName() {
        StubRegistrar stub = new StubRegistrar();
        assertTrue(factoryWith(stub).find("stub").isPresent());
    }

    @Test
    void resolvesByFullyQualifiedClassName() {
        StubRegistrar stub = new StubRegistrar();
        assertTrue(factoryWith(stub).find(StubRegistrar.class.getName()).isPresent());
    }

    @Test
    void unknownSelectorFailsLoudlyAndNamesWhatIsAvailable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> factoryWith(new StubRegistrar()).require("nope"));
        assertTrue(e.getMessage().contains("nope"), e.getMessage());
        assertTrue(e.getMessage().contains("stub"), "the error should name what IS available");
    }

    @Test
    void noRegistrarOnTheClasspathIsNotAnError_untilOneIsRequired() {
        // The OSS default: nothing configured, nothing discovered, nothing happens.
        assertTrue(factoryWith().find("anything").isEmpty());
        assertTrue(factoryWith().getDiscoveredRegistrars().isEmpty());
    }

    @Test
    void fingerprintIsStableForTheSameBuildAndEnvironment() {
        assertEquals(context().getFingerprint(), context().getFingerprint());
    }

    @Test
    void fingerprintChangesWithVersionAndEnvironmentAndRealms() {
        String base = context().getFingerprint();
        assertFalse(base.equals(RegistrationContext.builder()
                .applicationId("app").applicationVersion("2.0.0").environmentRef("local")
                .requiredRealm("r1").build().getFingerprint()), "a new build must re-register");
        assertFalse(base.equals(RegistrationContext.builder()
                .applicationId("app").applicationVersion("1.0.0").environmentRef("prod")
                .requiredRealm("r1").build().getFingerprint()), "a new environment must re-register");
        assertFalse(base.equals(RegistrationContext.builder()
                .applicationId("app").applicationVersion("1.0.0").environmentRef("local")
                .requiredRealm("r1").requiredRealm("r2").build().getFingerprint()),
                "a newly required realm must re-register");
    }

    @Test
    void cheapCheckShortCircuitsTheSecondStartup() {
        StubRegistrar stub = new StubRegistrar();
        assertFalse(stub.isRegistered(context()));
        stub.register(context());
        assertTrue(stub.isRegistered(context()), "startup must be a one-time hit");
        assertEquals(1, stub.registerCalls);
    }
}
