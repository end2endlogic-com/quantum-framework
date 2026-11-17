package com.e2eq.framework.service.seed;

import com.e2eq.framework.model.security.ApplicationDeclarationValidator.Diagnostic;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An application identity pack whose declaration (roles, tenancy, vocabulary, policies) the
 * {@link ApplicationDeclarationGate} refused. Nothing of the apply was written.
 */
public class ApplicationDeclarationRefusedException extends SeedLoadingException {
    private static final long serialVersionUID = 1L;

    public static final String CODE = "IDENTITY_PACK_DECLARATION_REFUSED";

    private final String seedPack;
    private final String applicationId;
    private final transient List<Diagnostic> diagnostics;

    public ApplicationDeclarationRefusedException(String seedPack, String applicationId, List<Diagnostic> diagnostics) {
        super(CODE + ": identity pack " + seedPack + " (application " + applicationId + ") was refused; nothing was written: "
                + diagnostics.stream().map(d -> d.code() + ": " + d.message()).collect(Collectors.joining("; ")));
        this.seedPack = seedPack;
        this.applicationId = applicationId;
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** The refused pack, as {@code name@version}. */
    public String getSeedPack() {
        return seedPack;
    }

    public String getApplicationId() {
        return applicationId;
    }

    /** The diagnostics that refused the pack (severity ERROR). */
    public List<Diagnostic> getDiagnostics() {
        return diagnostics;
    }
}
