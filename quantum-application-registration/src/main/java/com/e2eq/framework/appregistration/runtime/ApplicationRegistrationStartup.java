package com.e2eq.framework.appregistration.runtime;

import com.e2eq.framework.appregistration.model.RegistrationContext;
import com.e2eq.framework.appregistration.model.RegistrationOutcome;
import com.e2eq.framework.appregistration.spi.ApplicationRegistrar;
import com.e2eq.framework.appregistration.spi.RegistrationException;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs application registration once, at startup, when it is configured.
 *
 * <p>Disabled by default. An application that does not opt in never touches this
 * path, which is what keeps the framework usable with no control plane at all.</p>
 */
@ApplicationScoped
public class ApplicationRegistrationStartup {

    @Inject
    ApplicationRegistrarFactory registrarFactory;

    @ConfigProperty(name = "quantum.application-registration.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "quantum.application-registration.provider")
    Optional<String> provider;

    @ConfigProperty(name = "quantum.application-registration.application-id")
    Optional<String> applicationId;

    @ConfigProperty(name = "quantum.application-registration.environment")
    Optional<String> environmentRef;

    @ConfigProperty(name = "quantum.application-registration.realms")
    Optional<String> realmsCsv;

    @ConfigProperty(name = "quantum.application-registration.application-version")
    Optional<String> applicationVersion;

    /**
     * Whether a failed registration stops the service.
     *
     * <p>Defaults to true. An application whose environment does not know it is
     * an application whose realms may be unmigrated and whose seeds may be
     * absent; starting anyway converts one clear startup error into a stream of
     * unrelated-looking failures later.</p>
     */
    @ConfigProperty(name = "quantum.application-registration.fail-on-error", defaultValue = "true")
    boolean failOnError;

    private volatile RegistrationOutcome lastOutcome;

    /** What registration concluded, for readiness checks to project. */
    public Optional<RegistrationOutcome> getLastOutcome() {
        return Optional.ofNullable(lastOutcome);
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            Log.debug("Application registration disabled (quantum.application-registration.enabled=false)");
            return;
        }

        String selector = provider.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        if (selector == null) {
            fail("quantum.application-registration.enabled=true but no "
                    + "quantum.application-registration.provider was set", null);
            return;
        }
        if (applicationId.isEmpty() || environmentRef.isEmpty()) {
            fail("Application registration requires quantum.application-registration.application-id "
                    + "and quantum.application-registration.environment; neither is inferred, because "
                    + "a service that guesses its environment can register into the wrong one", null);
            return;
        }

        ApplicationRegistrar registrar;
        try {
            registrar = registrarFactory.require(selector);
        } catch (RuntimeException e) {
            fail(e.getMessage(), e);
            return;
        }

        RegistrationContext context = RegistrationContext.builder()
                .applicationId(applicationId.get().trim())
                .applicationVersion(applicationVersion.map(String::trim).orElse("unspecified"))
                .environmentRef(environmentRef.get().trim())
                .requiredRealms(splitCsv(realmsCsv))
                .build();

        // Registration runs AFTER startup completes, not inside it.
        //
        // Registering can cause the environment to call back into this very service
        // -- install provisions a realm by asking the application's own tenant plane
        // to do it. StartupEvent observers run before the HTTP server accepts
        // connections, so doing this inline deadlocks a service against itself: it
        // waits for a callback it cannot yet answer. The service therefore becomes
        // live first and registers immediately after; readiness, not liveness, is
        // what registration belongs in front of.
        Thread worker = new Thread(() -> runRegistration(registrar, context),
                "application-registration");
        worker.setDaemon(true);
        worker.start();
    }

    private void runRegistration(ApplicationRegistrar registrar, RegistrationContext context) {
        try {
            // The cheap path first, on every boot: one local lookup, no round trip.
            if (registrar.isRegistered(context)) {
                lastOutcome = RegistrationOutcome.alreadyRegistered(
                        "fingerprint unchanged: " + context.getFingerprint());
                Log.infof("Application registration: %s already registered in environment '%s'",
                        context.getApplicationId(), context.getEnvironmentRef());
                return;
            }

            RegistrationOutcome outcome = registrar.register(context);
            lastOutcome = outcome;
            switch (outcome.getStatus()) {
                case REGISTERED, ALREADY_REGISTERED -> Log.infof(
                        "Application registration: %s in environment '%s' -- %s",
                        context.getApplicationId(), context.getEnvironmentRef(), outcome.getDetail());
                case NOT_READY -> fail("Environment '" + context.getEnvironmentRef()
                        + "' is not ready for " + context.getApplicationId() + ": " + outcome.getDetail()
                        + remaining(outcome), null);
                case REFUSED -> fail("Environment '" + context.getEnvironmentRef()
                        + "' refused registration of " + context.getApplicationId() + ": "
                        + outcome.getDetail(), null);
            }
        } catch (RegistrationException e) {
            fail("Application registration failed for " + context.getApplicationId()
                    + " in environment '" + context.getEnvironmentRef() + "': " + e.getMessage(), e);
        }
    }

    private static String remaining(RegistrationOutcome outcome) {
        List<String> actions = outcome.getRemainingActions();
        if (actions.isEmpty()) {
            return "";
        }
        return System.lineSeparator() + "  Remaining:" + System.lineSeparator()
                + actions.stream().map(a -> "    - " + a)
                        .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * Report a registration failure, and stop the service when configured to.
     *
     * <p>Throwing would not stop anything: registration runs off the startup thread,
     * so an exception here would be swallowed by a daemon worker and the service
     * would serve traffic against an environment that does not know it. Shutting
     * down is what "fail-on-error" has to mean once registration is asynchronous.</p>
     */
    private void fail(String message, Throwable cause) {
        if (failOnError) {
            Log.errorf(cause, "%s -- shutting down "
                    + "(set quantum.application-registration.fail-on-error=false to start anyway)",
                    message);
            Quarkus.asyncExit(1);
            return;
        }
        Log.warnf(cause, "%s (continuing: quantum.application-registration.fail-on-error=false)", message);
    }

    private static List<String> splitCsv(Optional<String> csv) {
        return csv.map(s -> Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(v -> !v.isEmpty())
                        .toList())
                .orElseGet(List::of);
    }
}
