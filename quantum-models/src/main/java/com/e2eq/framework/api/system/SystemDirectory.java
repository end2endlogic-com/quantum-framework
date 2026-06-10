package com.e2eq.framework.api.system;

import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;

import java.util.Optional;

/**
 * Directory of cross-realm concerns: the realm catalog and the credential
 * store that today live in the configured "system realm".
 *
 * This is the central indirection of the control-plane / data-plane split
 * (docs/design/CONTROL_PLANE_SPLIT_DESIGN.md, Phase A). Call sites must use
 * this interface instead of resolving {@code EnvConfigUtils.getSystemRealm()}
 * and passing the realm constant into repositories. Where cross-realm data
 * lives — a local system-realm database today, a remote control-plane service
 * later — is this interface's concern alone.
 *
 * Selection is config-driven:
 * <pre>
 *   quantum.system.directory.mode=local   # default — today's behavior
 *   quantum.system.directory.mode=remote  # Phase C — control-plane HTTP client
 *   quantum.system.directory.url=...      # remote mode only
 * </pre>
 *
 * Phase D note: the credential accessors intentionally expose the current
 * persistence type ({@link CredentialUserIdPassword}). When identity collapses
 * into the unified {@code Principal} model (design §6), these methods are the
 * single seam where that generalization lands.
 */
public interface SystemDirectory {

    /**
     * The realm reference under which cross-realm data is stored when this
     * directory is locally backed. Exposed for incremental migration of call
     * sites that still need the realm string (e.g. building an
     * {@code EntityReference} or addressing the system datastore). New code
     * should prefer the typed operations below.
     */
    String systemRealmId();

    // --- realm catalog ---

    Optional<Realm> findRealmByEmailDomain(String emailDomain);

    Optional<Realm> findRealmByRefName(String refName);

    /** Create or update the catalog entry for a realm. */
    Realm registerRealm(Realm realm);

    // --- identity (current persistence type; see Phase D note above) ---

    Optional<CredentialUserIdPassword> findCredentialBySubject(String subject);

    Optional<CredentialUserIdPassword> findCredentialByUserId(String userId);
}
