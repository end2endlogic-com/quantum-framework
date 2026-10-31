package com.e2eq.framework.service.application;

/**
 * Thrown by an {@link ApplicationRegistryValidator} when the application
 * registry cannot be consulted. Grant writes must fail closed (503) on this —
 * an unreachable registry is never a reason to accept an unvalidated grant.
 */
public class ApplicationRegistryUnavailableException extends RuntimeException {
   public ApplicationRegistryUnavailableException(String message) {
      super(message);
   }

   public ApplicationRegistryUnavailableException(String message, Throwable cause) {
      super(message, cause);
   }
}
