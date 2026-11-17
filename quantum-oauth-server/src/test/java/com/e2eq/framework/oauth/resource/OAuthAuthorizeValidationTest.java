package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.oauth.model.OAuthClient;
import com.e2eq.framework.oauth.repo.OAuthClientRepo;
import com.e2eq.framework.model.auth.AuthProviderFactory;
import com.e2eq.framework.util.EnvConfigUtils;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OAuthAuthorizeValidationTest {
    @Test void getAndPostBothRejectMissingPkceBeforeAuthentication() {
        var resource=new OAuthAuthorizeResource();
        resource.envConfigUtils=mock(EnvConfigUtils.class);
        when(resource.envConfigUtils.getSystemRealm()).thenReturn("system");
        resource.oauthClientRepo=mock(OAuthClientRepo.class);
        resource.authProviderFactory=mock(AuthProviderFactory.class);
        var client=new OAuthClient();client.setActive(true);client.setPublicClient(true);
        client.setAllowedGrantTypes(List.of("authorization_code"));
        client.setRedirectUris(List.of("https://client.invalid/callback"));
        when(resource.oauthClientRepo.findByClientId("system","client")).thenReturn(Optional.of(client));
        var get=resource.authorize("code","client","https://client.invalid/callback","openid","state",null,null);
        var post=resource.login("user","password","client","https://client.invalid/callback","openid","state",null,null);
        assertEquals(303,get.getStatus());assertEquals(303,post.getStatus());
        assertTrue(get.getLocation().getQuery().contains("error=invalid_request"));
        assertEquals(get.getLocation(),post.getLocation());
        verifyNoInteractions(resource.authProviderFactory);
        assertEquals(200,resource.authorize("code","client","https://client.invalid/callback",null,null,"a".repeat(43),"S256").getStatus());
        assertEquals(400,resource.authorize("code","client","https://evil.invalid",null,null,null,null).getStatus());
    }

    @Test void confidentialClientsKeepTheExistingNonPkceFlow() {
        var client=new OAuthClient();client.setActive(true);client.setPublicClient(false);
        client.setAllowedGrantTypes(List.of("authorization_code"));
        assertNull(OAuthRequestValidation.authorizationError(client,null,null));
        assertNull(OAuthRequestValidation.authorizationError(client,"",""));
        assertNotNull(OAuthRequestValidation.authorizationError(client,"a".repeat(43),"plain"));
        client.setActive(false);
        assertNotNull(OAuthRequestValidation.authorizationError(client,null,null));
    }
}
