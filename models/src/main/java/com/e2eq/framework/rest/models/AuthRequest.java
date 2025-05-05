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
@SuperBuilder
public class AuthRequest  {
   @JsonProperty(required = true)
   protected @NotNull String userId;
   protected String email;
   @JsonProperty(required = true)
   protected @NotNull String password;
   protected String tenantId;
   protected String accountId;
   protected String realm;
   protected boolean rememberme;

   public AuthRequest(String userId, String password) {
      this.userId = userId;
      this.password = password;
   }
}
