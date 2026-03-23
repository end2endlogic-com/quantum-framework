package com.e2eq.framework.oauth.model;

import com.e2eq.framework.model.persistent.base.BaseModel;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.IndexOptions;
import dev.morphia.annotations.Indexed;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Represents an OAuth 2.0 client registered with the Quantum OAuth server.
 * Each client belongs to a realm and defines which grant types, redirect URIs,
 * and scopes it is allowed to use.
 */
@Entity(value = "oauth_clients", useDiscriminator = false)
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@RegisterForReflection
public class OAuthClient extends BaseModel {

    /** The OAuth client_id. Must be unique within the system realm. */
    @Indexed(options = @IndexOptions(unique = true))
    @NotNull
    @NotEmpty
    private String clientId;

    /** Bcrypt hash of the client secret. Never stored in plaintext. */
    private String clientSecretHash;

    /** Whether this is a public client (no secret required, e.g., PKCE-only SPAs). */
    private boolean publicClient;

    /** Allowed redirect URIs for the authorization code flow. */
    @NotNull
    private List<String> redirectUris;

    /** Allowed OAuth grant types (e.g., authorization_code, client_credentials, refresh_token). */
    @NotNull
    private List<String> allowedGrantTypes;

    /** Allowed OAuth scopes (e.g., openid, profile, email). */
    private List<String> allowedScopes;

    /** Optional human-readable description of this client. */
    private String clientDescription;

    /** The realm this client is associated with for data scoping. */
    private String realm;

    /** Whether this client is active. Inactive clients cannot obtain tokens. */
    private boolean active = true;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "OAUTH_CLIENT";
    }
}
