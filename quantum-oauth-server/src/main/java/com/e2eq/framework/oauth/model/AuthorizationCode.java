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

import java.util.Date;
import java.util.List;

/**
 * Short-lived authorization code issued during the OAuth authorization code flow.
 * Documents expire automatically via MongoDB TTL index after 5 minutes.
 */
@Entity(value = "oauth_authorization_codes", useDiscriminator = false)
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@RegisterForReflection
public class AuthorizationCode extends BaseModel {

    /** The authorization code value (opaque random string). */
    @Indexed(options = @IndexOptions(unique = true))
    private String code;

    /** The client_id that requested this code. */
    private String clientId;

    /** The authenticated user's subject (credential subject). */
    private String subject;

    /** The authenticated user's userId. */
    private String userId;

    /** The redirect_uri that was used in the authorization request. */
    private String redirectUri;

    /** PKCE code challenge (S256 hash of the code_verifier). */
    private String codeChallenge;

    /** PKCE code challenge method (always S256). */
    private String codeChallengeMethod;

    /** Scopes granted for this authorization. */
    private List<String> scopes;

    /** The realm context for this authorization. */
    private String realm;

    /** Whether this code has been consumed (exchanged for tokens). */
    private boolean consumed;

    /** Expiration time. MongoDB TTL index auto-deletes expired documents. */
    @Indexed(options = @IndexOptions(expireAfterSeconds = 300))
    private Date expiresAt;

    @Override
    public String bmFunctionalArea() {
        return "SECURITY";
    }

    @Override
    public String bmFunctionalDomain() {
        return "OAUTH_AUTHORIZATION_CODE";
    }
}
