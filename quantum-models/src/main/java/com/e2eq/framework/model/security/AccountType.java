package com.e2eq.framework.model.security;

/**
 * The NATURE of the account behind a credential — orthogonal to
 * {@link CredentialType}, which is the authentication MECHANISM (password,
 * service token, OAuth…). A password credential can belong to a human or to a
 * bootstrap principal; this classification is what invariants hang on:
 *
 * <ul>
 *   <li>{@link #USER} — a human user. A directory {@code UserProfile} is
 *       REQUIRED: login is refused for a USER credential without one, and admin
 *       surfaces list users from the profile directory. This closes the
 *       "half-created account" gap (can log in, invisible everywhere).</li>
 *   <li>{@link #SERVICE} — machine identity (service tokens, integrations).
 *       No profile expected; excluded from people-facing directory views.</li>
 *   <li>{@link #SYSTEM} — platform principals (bootstrap, system accounts).
 *       Profile optional; shown to operators but distinguishable from people.</li>
 * </ul>
 *
 * A null accountType is a LEGACY credential created before this field existed:
 * enforcement logs the inconsistency but does not lock the account out;
 * admin surfaces flag it as unclassified.
 */
public enum AccountType {
    USER,
    SERVICE,
    SYSTEM
}
