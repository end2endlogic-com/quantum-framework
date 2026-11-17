package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.security.UserRealmRole;
import com.e2eq.framework.security.runtime.ExternalMembershipAuthorization;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalMembershipPermissionTest {
    @Test
    void conversionNeedsPermissionButAnOrdinaryUpdateDoesNot() {
        var repo = new UserRealmRoleRepo();
        repo.externalMembershipAuthorization = mock(ExternalMembershipAuthorization.class);
        var member = UserRealmRole.builder().userId("person").realmRefName("realm")
                .authorizedTenantIds(List.of("tenant")).build();
        var outsider = UserRealmRole.builder().userId("person").realmRefName("realm")
                .authorizedTenantIds(List.of("tenant")).externalTenantIds(List.of("tenant")).build();
        doThrow(new jakarta.ws.rs.ForbiddenException()).when(repo.externalMembershipAuthorization)
                .require(null, "realm", "tenant");
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> repo.requireKindChangePermission(member, outsider));
        assertThrows(jakarta.ws.rs.ForbiddenException.class, () -> repo.requireKindChangePermission(outsider, member));
        assertDoesNotThrow(() -> repo.requireKindChangePermission(member, member));
        verify(repo.externalMembershipAuthorization, times(2)).require(null, "realm", "tenant");
    }

    @Test
    void aMembershipThatMarksAnOutsiderIsNotDeleted() {
        var repo = spy(new UserRealmRoleRepo());
        var session = mock(dev.morphia.transactions.MorphiaSession.class);
        var outsider = UserRealmRole.builder().userId("person").realmRefName("realm")
                .authorizedTenantIds(List.of("tenant")).externalTenantIds(List.of("tenant")).build();
        outsider.setId(new org.bson.types.ObjectId());
        doReturn(java.util.Optional.of(outsider)).when(repo).findById(session, outsider.getId());
        var refused = assertThrows(IllegalArgumentException.class, () -> repo.delete(session, outsider));
        assertTrue(refused.getMessage().startsWith("EXTERNAL_MEMBERSHIP_DELETE_REFUSED"));
    }
}
