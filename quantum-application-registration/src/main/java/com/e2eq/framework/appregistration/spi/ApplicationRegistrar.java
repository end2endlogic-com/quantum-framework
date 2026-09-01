package com.e2eq.framework.appregistration.spi;

import com.e2eq.framework.appregistration.model.RegistrationContext;
import com.e2eq.framework.appregistration.model.RegistrationOutcome;

/**
 * How an application announces itself to the environment it is starting in, so
 * the environment can install, seed and migrate it on first arrival.
 *
 * <p>Implement this, put the implementation on the classpath as a CDI bean, and
 * name it in {@code application.properties}:</p>
 *
 * <pre>
 * quantum.application-registration.enabled=true
 * quantum.application-registration.provider=system-manager
 * quantum.application-registration.application-id=my-app
 * quantum.application-registration.environment=production
 * </pre>
 *
 * <p>{@code provider} matches either {@link #getName()} or the implementation's
 * fully-qualified class name. With registration disabled -- the default -- or no
 * implementation on the classpath, nothing here runs and the framework starts
 * exactly as it does without this module. That is the point: this package
 * defines the contract and never the control plane, so an application that
 * registers with a hosted environment directory and one that registers with
 * nothing at all are the same application.</p>
 *
 * <h2>Cost</h2>
 *
 * <p>Registration happens on every start but must only be <em>paid for</em>
 * once. {@link #isRegistered(RegistrationContext)} is the cheap path and is
 * called first on every boot; {@link #register(RegistrationContext)} runs only
 * when it answers false. Implementations that get this backwards turn a
 * one-time install into a per-restart round trip against the control plane, and
 * a crash-looping service into a load generator against it.</p>
 */
public interface ApplicationRegistrar {

    /**
     * Selector matched against {@code quantum.application-registration.provider}.
     * Stable across releases: it is configuration, not a display name.
     */
    String getName();

    /**
     * Has this exact application, build and environment already registered?
     *
     * <p><strong>Must be cheap and must not perform a remote call.</strong> It runs
     * on every startup, on the path that decides whether the expensive work is
     * needed at all. Answer it from a locally persisted receipt keyed by
     * {@link RegistrationContext#getFingerprint()} -- a single indexed local read.</p>
     *
     * <p>Return false when unsure. A redundant registration is idempotent and
     * cheap to absorb; a skipped one leaves an application running against an
     * environment that was never prepared for it.</p>
     */
    boolean isRegistered(RegistrationContext context);

    /**
     * Announce this application to its environment and return what the
     * environment says back.
     *
     * <p>Called only when {@link #isRegistered(RegistrationContext)} returns
     * false. May be expensive: this is where install, seeding and realm
     * migration happen for a first arrival. Must be idempotent -- two services
     * of the same application starting at once must not install twice.</p>
     *
     * <p>Record the receipt that {@link #isRegistered(RegistrationContext)} will
     * read on the next boot; without it every restart pays full price.</p>
     *
     * @throws RegistrationException when registration cannot be completed and the
     *         caller must decide whether that is fatal
     */
    RegistrationOutcome register(RegistrationContext context) throws RegistrationException;
}
