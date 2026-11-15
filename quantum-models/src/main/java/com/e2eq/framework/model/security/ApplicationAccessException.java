package com.e2eq.framework.model.security;

/**
 * A typed refusal of an application binding ({@link UserAppRole}) or install
 * ({@link ApplicationInstall}) write. The message carries the detail; {@link Code#httpStatus()}
 * is what a REST caller receives.
 */
public class ApplicationAccessException extends IllegalArgumentException {

   public enum Code {
      /** A binding names neither a user nor a group, or both. */
      BINDING_SUBJECT_INVALID(400),
      /** Application, realm, status or source missing or not a known value. */
      BINDING_INVALID(400),
      /** The application is not registered. */
      APPLICATION_UNKNOWN(400),
      /** A role the application does not declare. */
      ROLE_NOT_DECLARED(400),
      /** A binding names no tenant. */
      TENANTS_REQUIRED(400),
      /** Several tenants and no default among them. */
      TENANT_DEFAULT_REQUIRED(400),
      /** The application is not installed for one of the binding's tenants. */
      APPLICATION_NOT_INSTALLED(409),
      /** The caller may not grant one of the roles, or may not act for one of the tenants. */
      ROLE_NOT_GRANTABLE(403),
      /** The caller may not install or uninstall in this realm. */
      INSTALL_NOT_AUTHORIZED(403),
      /** The write names a realm other than the caller's. */
      REALM_MISMATCH(403),
      /** An install misses its id, tenant, application, realm or status. */
      INSTALL_INVALID(400),
      /** No such binding or install. */
      NOT_FOUND(404),
      /**
       * Bindings and installs are held by the remote directory ({@code quantum.directory.mode=remote});
       * this service neither writes nor reads them.
       */
      DIRECTORY_REMOTE(409),
      /**
       * The request names no application while application-scoped policy evaluation is required
       * ({@code quantum.security.policy-scope.require-application=true}).
       */
      APPLICATION_SCOPE_REQUIRED(400),
      /** The request's principal and resource name different applications. */
      APPLICATION_SCOPE_MISMATCH(400);

      private final int httpStatus;

      Code(int httpStatus) {
         this.httpStatus = httpStatus;
      }

      public int httpStatus() {
         return httpStatus;
      }
   }

   private final Code code;

   public ApplicationAccessException(Code code, String message) {
      super(code.name() + ": " + message);
      this.code = code;
   }

   public Code code() {
      return code;
   }

   /**
    * The message a REST caller may see. The application-scope refusals name server-selected
    * scopes in their detail, so callers get a fixed sentence and the detail stays in the log.
    */
   public String publicMessage() {
      return switch (code) {
         case APPLICATION_SCOPE_REQUIRED -> "The request names no application, and this deployment requires one.";
         case APPLICATION_SCOPE_MISMATCH -> "The request's identity and resource name different applications.";
         default -> getMessage();
      };
   }
}
