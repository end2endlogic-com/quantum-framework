package com.e2eq.framework.system.membership;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.UserRealmRoleRepo;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.Realm;
import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.system.config.QuantumModeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
class RealmMembershipServiceTest {

    @Test
    void controlPlaneUpdatePreservesExistingTenantDataDomainWhenOmitted() {
        RealmMembershipService service = new RealmMembershipService();
        service.quantumModeConfig = QuantumModeConfig.of("embedded", Optional.empty());
        service.systemDirectory = new SystemDirectory() {
            @Override public String systemRealmId() { return "quantum-system"; }
            @Override public Optional<Realm> findRealmByEmailDomain(String value) { return Optional.empty(); }
            @Override public Optional<Realm> findRealmByRefName(String value) { return Optional.empty(); }
            @Override public Realm registerRealm(Realm value) { return value; }
            @Override public Optional<CredentialUserIdPassword> findCredentialBySubject(String value) { return Optional.empty(); }
            @Override public Optional<CredentialUserIdPassword> findCredentialByUserId(String value) { return Optional.empty(); }
        };

        DataDomain tenantScope = DataDomain.builder()
            .tenantId("development")
            .orgRefName("HelixorAI")
            .accountNum("0000000001")
            .ownerId("system")
            .build();
        UserRealmRole existing = new UserRealmRole();
        existing.setUserId("mingardia@helixor.ai");
        existing.setRealmRefName("helixor-code-D1");
        existing.setDataDomain(tenantScope);
        UserRealmRole update = new UserRealmRole();
        update.setUserId(existing.getUserId());
        update.setRealmRefName(existing.getRealmRefName());
        update.setRoles(List.of("system", "admin", "user"));

        service.userRealmRoleRepo = new UserRealmRoleRepo() {
            @Override
            public Optional<UserRealmRole> findAssignmentForRealmWithIgnoreRules(
                    String userId, String realmRefName, String systemRealmId) {
                return Optional.of(existing);
            }

            @Override
            public UserRealmRole save(String realmId, UserRealmRole value) {
                return value;
            }
        };

        UserRealmRole saved = service.upsertUserRealmRole(update);

        assertEquals(tenantScope, saved.getDataDomain());
        assertEquals(update.getRoles(), saved.getRoles());
    }
}
