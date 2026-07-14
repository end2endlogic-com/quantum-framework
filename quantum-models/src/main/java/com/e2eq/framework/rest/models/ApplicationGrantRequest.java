package com.e2eq.framework.rest.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Sets the application-scoped-auth grant on a realm membership: which applications
 * a user may authenticate for IN A REALM, and the default when several are authorized.
 * An empty/null {@link #authorizedApplications} clears the grant (reverts to legacy,
 * single-audience behavior). {@code "*"} is an admin-only, audited wildcard.
 */
@RegisterForReflection
@Data
@NoArgsConstructor
public class ApplicationGrantRequest {
    /** ApplicationDefinition refNames the user may authenticate for; may contain {@code "*"}. */
    @JsonProperty("authorizedApplications")
    protected List<String> authorizedApplications;

    /** The app to assume when several are authorized and none is passed at login; optional. */
    @JsonProperty("defaultApplication")
    protected String defaultApplication;
}
