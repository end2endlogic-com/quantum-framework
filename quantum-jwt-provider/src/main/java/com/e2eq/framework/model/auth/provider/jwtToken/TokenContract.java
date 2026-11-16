package com.e2eq.framework.model.auth.provider.jwtToken;

import java.util.Locale;

/**
 * Which claims an issued user access token carries ({@code quantum.auth.token-contract}).
 *
 * <ul>
 *   <li>{@link #V1} (default): the claims-bearing token receivers on the claims path still read
 *       ({@code groups}, {@code realm}, {@code tenantId}, {@code orgRefName}, {@code accountNum},
 *       {@code aud}, {@code azp}, {@code realmRegEx}).</li>
 *   <li>{@link #V2}: identity only ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 1, rollout
 *       step 4). Turn it on only once every receiver of this issuer's tokens resolves the principal
 *       from the credential (see the step 4 flip checklist in that document): a receiver still on
 *       the claims path refuses a v2 token.</li>
 * </ul>
 *
 * Refresh tokens are read only by the issuer and are unchanged by either contract.
 */
public enum TokenContract {
    V1,
    V2;

    public static final String PROPERTY = "quantum.auth.token-contract";

    /**
     * Parses the configured value; unset or blank is {@link #V1}. Anything else than {@code v1} or
     * {@code v2} (any case) is refused, so a typo never silently keeps or changes the contract.
     */
    public static TokenContract parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return V1;
        }
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "v1" -> V1;
            case "v2" -> V2;
            default -> throw new IllegalStateException("TOKEN_CONTRACT_INVALID: " + PROPERTY
                    + " must be v1 or v2, was '" + configured.trim() + "'");
        };
    }
}
