package com.e2eq.framework.system;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.persistent.BaseRepoTest;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Phase A exit test for the control-plane split
 * (docs/design/CONTROL_PLANE_SPLIT_DESIGN.md §9).
 *
 * Verifies the SystemDirectory indirection is in place and that, in local
 * mode, it resolves against the *configured* system realm rather than a
 * hard-coded constant. Flipping quantum.realmConfig.systemRealm (or running
 * an app with a per-app system realm such as system-psa-com) must be fully
 * reflected through this interface.
 */
@QuarkusTest
public class TestSystemDirectory extends BaseRepoTest {

    @Inject
    SystemDirectory systemDirectory;

    @Inject
    EnvConfigUtils envConfigUtils;

    @Test
    public void systemDirectoryInjectsInLocalModeByDefault() {
        Assertions.assertNotNull(systemDirectory, "SystemDirectory must be injectable (local mode default)");
    }

    @Test
    public void systemRealmIdFollowsConfiguredProperty() {
        // The pointer must come from configuration (quantum.realmConfig.systemRealm),
        // not a constant baked into call sites.
        Assertions.assertEquals(envConfigUtils.getSystemRealm(), systemDirectory.systemRealmId());
    }

    @Test
    public void realmCatalogLookupsResolveAgainstSystemRealm() {
        // The system user's credential is seeded into the system realm by the
        // baseline identity startup; the directory must find it without the
        // caller passing a realm constant.
        Optional<CredentialUserIdPassword> credential =
            systemDirectory.findCredentialByUserId(envConfigUtils.getSystemUserId());
        Assertions.assertTrue(
            credential.isPresent(),
            "System user credential should resolve via SystemDirectory in realm " + systemDirectory.systemRealmId());
    }

    @Test
    public void unknownRealmLookupIsEmptyNotError() {
        Optional<Realm> realm = systemDirectory.findRealmByRefName("no-such-realm-xyz");
        Assertions.assertTrue(realm.isEmpty());
    }
}
