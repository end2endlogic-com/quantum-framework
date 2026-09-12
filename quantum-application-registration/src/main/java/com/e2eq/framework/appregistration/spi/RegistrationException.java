package com.e2eq.framework.appregistration.spi;

/**
 * Registration could not be completed.
 *
 * <p>Whether this stops the service is the deployment's call, not the
 * registrar's: see {@code quantum.application-registration.fail-on-error}.
 * A developer laptop with no control plane running and a production service
 * whose environment does not know it want opposite answers, and neither is a
 * property of the code that failed.</p>
 */
public class RegistrationException extends Exception {

    public RegistrationException(String message) {
        super(message);
    }

    public RegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
