package com.e2eq.framework.rest.filters;

import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RolesAugmentorDispatchTest {
    @Test
    void repositoryResolutionStartsOnlyInsideTheBlockingDispatch() {
        var augmentor = new RolesAugmentor();
        augmentor.credentialRepo = mock(CredentialRepo.class);
        augmentor.httpHeaders = mock(HttpHeaders.class);
        augmentor.envConfigUtils = new EnvConfigUtils();
        augmentor.envConfigUtils.setSystemRealm("system");
        when(augmentor.credentialRepo.findBySubject("user", "system", true)).thenReturn(Optional.empty());
        var context = mock(AuthenticationRequestContext.class);
        when(context.runBlocking(any())).thenAnswer(call -> {
            verifyNoInteractions(augmentor.credentialRepo);
            return Uni.createFrom().item(call.<Supplier<SecurityIdentity>>getArgument(0));
        });
        var identity = QuarkusSecurityIdentity.builder().setPrincipal(() -> "user").build();
        var pending = augmentor.augment(identity, context);
        verifyNoInteractions(augmentor.credentialRepo);
        assertTrue(pending.await().indefinitely().hasRole("user"));
        verify(context).runBlocking(any());
        verify(augmentor.credentialRepo).findBySubject("user", "system", true);
    }
}
