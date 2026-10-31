package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@RegisterForReflection
@Data
@EqualsAndHashCode
@NoArgsConstructor
public class AuthRequest  {
   @JsonProperty(required = true)
   protected @NotNull String userId;
   protected String email;
   @JsonProperty(required = true)
   protected @NotNull String password;
   protected String tenantId;
   protected String accountId;
   protected String realm;
   /**
    * The application the user is signing into (application-scoped auth). Optional:
    * when omitted and exactly one app is authorized it is assumed; when multiple are
    * authorized the default is used, or a 400 lists the choices for the login UI.
    * Named to match the OAuth {@code client_id}/{@code audience} concept.
    */
   protected String applicationId;
   protected boolean rememberme;

   public AuthRequest(String userId, String password) {
      this.userId = userId;
      this.password = password;
   }
}
