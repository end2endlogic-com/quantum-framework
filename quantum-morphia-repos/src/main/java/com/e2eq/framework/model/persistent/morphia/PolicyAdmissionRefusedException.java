package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.ApplicationDeclarationValidator;

import java.util.Objects;

/**
 * A policy save refused by policy admission ({@code quantum.security.policy-admission.enabled}),
 * with the {@link ApplicationDeclarationValidator.Code} identity-contribution admission uses for the
 * same fault. It stays an {@link IllegalArgumentException}, so callers that already map refusals
 * keep working; the message starts with the code.
 */
public class PolicyAdmissionRefusedException extends IllegalArgumentException {

   private final ApplicationDeclarationValidator.Code code;

   public PolicyAdmissionRefusedException(ApplicationDeclarationValidator.Code code, String message) {
      super(Objects.requireNonNull(code, "code") + ": " + message);
      this.code = code;
   }

   public ApplicationDeclarationValidator.Code code() {
      return code;
   }
}
