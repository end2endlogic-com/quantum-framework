package com.e2eq.framework.oauth.resource;

import com.e2eq.framework.model.auth.provider.jwtToken.*;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.persistent.morphia.*;
import com.e2eq.framework.model.security.*;
import com.e2eq.framework.oauth.model.*;
import com.e2eq.framework.oauth.repo.*;
import com.e2eq.framework.util.*;
import io.smallrye.jwt.auth.principal.*;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the endpoint and real JWT mint/verify/refresh pipeline, with storage isolated. */
class OAuthTokenResourceTest {
    OAuthTokenResource resource;
    CustomTokenAuthProvider provider;
    CredentialUserIdPassword credential;
    UserRealmRoleRepo assignments;
    DefaultJWTParser parser;
    Object previousKey;

    static void inject(Object target, String name, Object value) throws Exception {
        for (Class<?> type=target.getClass(); type!=null; type=type.getSuperclass()) {
            try { var field=type.getDeclaredField(name); field.setAccessible(true); field.set(target,value); return; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    @BeforeEach void setup() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var keys=generator.generateKeyPair();
        var field=TokenUtils.class.getDeclaredField("cachedPrivateKey"); field.setAccessible(true);
        previousKey=field.get(null); field.set(null,keys.getPrivate());
        parser=new DefaultJWTParser(new JWTAuthContextInfo(keys.getPublic(),"https://issuer.invalid"));
        credential=new CredentialUserIdPassword();credential.setSubject("subject");credential.setUserId("user");
        credential.setAccountType(AccountType.SERVICE);credential.setApplicationRegEx("app");
        credential.setRoles(new String[]{"user"});
        credential.setDomainContext(DomainContext.builder().tenantId("tenant").orgRefName("org").accountId("account").defaultRealm("realm").build());
        var credentials=mock(CredentialRepo.class);
        when(credentials.findBySubject("subject","system",true)).thenAnswer(i->Optional.of(credential));
        when(credentials.findByUserId(anyString(),eq("system"),eq(true))).thenAnswer(i->Optional.of(credential));
        var env=mock(EnvConfigUtils.class);when(env.getSystemRealm()).thenReturn("system");
        assignments=mock(UserRealmRoleRepo.class);
        when(assignments.findActiveAssignmentForRealmWithIgnoreRules(anyString(),anyString(),anyString())).thenReturn(Optional.empty());
        provider=new CustomTokenAuthProvider();
        inject(provider,"credentialRepo",credentials);inject(provider,"envConfigUtils",env);
        inject(provider,"userRealmRoleRepo",assignments);inject(provider,"userProfileRepo",mock(UserProfileRepo.class));
        inject(provider,"userGroupRepo",mock(UserGroupRepo.class));inject(provider,"realmRepo",mock(RealmRepo.class));
        inject(provider,"securityUtils",new SecurityUtils());inject(provider,"jwtParser",parser);
        inject(provider,"issuer","https://issuer.invalid");inject(provider,"durationInSeconds",60L);
        resource=new OAuthTokenResource();resource.tokenProvider=provider;resource.credentialRepo=credentials;
        resource.envConfigUtils=env;resource.tokenDuration=60;
        resource.oauthClientRepo=mock(OAuthClientRepo.class);resource.authCodeRepo=mock(AuthorizationCodeRepo.class);
        for(String id:List.of("client-a","client-b")) {
            var client=new OAuthClient();client.setClientId(id);client.setPublicClient(true);client.setActive(true);
            client.setApplicationId("app");client.setRealm("realm");
            client.setAllowedGrantTypes(List.of("authorization_code","client_credentials","refresh_token"));
            when(resource.oauthClientRepo.findByClientId("system",id)).thenReturn(Optional.of(client));
        }
        var code=new AuthorizationCode();code.setClientId("client-a");code.setSubject("subject");code.setRealm("realm");
        when(resource.authCodeRepo.findValidCode("system","code")).thenReturn(Optional.of(code));
        when(resource.authCodeRepo.consumeCode("system","code")).thenReturn(true);
    }
    @AfterEach void restoreKey() throws Exception {
        var field=TokenUtils.class.getDeclaredField("cachedPrivateKey");field.setAccessible(true);field.set(null,previousKey);
    }
    Response exchange(String grant,String client,String refresh) {
        return resource.token(null,grant,"code",null,null,client,null,refresh,null);
    }
    String token(Response response,String field) { assertEquals(200,response.getStatus(),String.valueOf(response.getEntity()));return (String)((Map<?,?>)response.getEntity()).get(field); }
    @Test void authorizationCodeRefreshPreservesIdentityRealmApplicationAndClient() throws Exception {
        var initial=exchange("authorization_code","client-a",null);
        String refresh=token(initial,"refresh_token");
        var claims=parser.parse(refresh);assertEquals("client-a",claims.getClaim("oauth_client_id"));
        assertEquals("app",claims.getClaim("azp"));assertEquals("realm",claims.getClaim("realm"));
        var rotated=exchange("refresh_token","client-a",refresh);
        var access=parser.parse(token(rotated,"access_token"));assertEquals("subject",access.getSubject());
        assertEquals(Set.of("app"),access.getAudience());assertEquals("realm",access.getClaim("realm"));
        assertEquals("client-a",parser.parse(token(rotated,"refresh_token")).getClaim("oauth_client_id"));
        assertEquals(400,exchange("refresh_token","client-b",refresh).getStatus());
        assertThrows(SecurityException.class,()->provider.refreshTokens(refresh));
    }
    @Test void clientMappingChangesInvalidateItsExistingRefreshToken() {
        String refresh=token(exchange("authorization_code","client-a",null),"refresh_token");
        var client=resource.oauthClientRepo.findByClientId("system","client-a").orElseThrow();
        client.setApplicationId("other");
        assertEquals(400,exchange("refresh_token","client-a",refresh).getStatus());
        client.setApplicationId("app");client.setRealm("other-realm");
        assertEquals(400,exchange("refresh_token","client-a",refresh).getStatus());
    }
    @Test void passwordLoginStillRequiresThePassword() {
        credential.setPassword("local-review-password");
        assertFalse(provider.login("user","wrong-password","app","realm").authenticated());
        assertTrue(provider.login("user","local-review-password","app","realm").authenticated());
        assertFalse(provider.login("user",null,"app","realm").authenticated());
        credential.setPasswordHash(null);
        assertFalse(provider.login("user","local-review-password","app","realm").authenticated());
        credential.setPasswordHash("");
        assertFalse(provider.login("user","local-review-password","app","realm").authenticated());
    }
    @Test void unboundSessionCannotEnterOAuthExchange() {
        var login=provider.loginWithVerifiedSubject("subject","app","realm",null);
        assertTrue(login.authenticated());
        assertEquals(400,exchange("refresh_token","client-a",login.positiveResponse().refreshToken()).getStatus());
        assertTrue(provider.refreshTokens(login.positiveResponse().refreshToken()).authenticated());
    }
    @Test void revokedApplicationAndDisabledCredentialCannotRefresh() {
        String refresh=token(exchange("authorization_code","client-a",null),"refresh_token");
        credential.setApplicationRegEx("other");assertEquals(400,exchange("refresh_token","client-a",refresh).getStatus());
        credential.setApplicationRegEx("app");credential.setActiveStatus(ActiveStatus.INACTIVE);
        assertEquals(400,exchange("refresh_token","client-a",refresh).getStatus());
        assertEquals(400,exchange("client_credentials","client-a",null).getStatus());
        assertEquals(400,exchange("authorization_code","client-a",null).getStatus());
    }
    @Test void verifiedIdentityStillNeedsRealmAndApplicationAuthority() {
        credential.setApplicationRegEx(null);
        assertFalse(provider.loginWithVerifiedSubject("subject",null,"realm",null).authenticated());
        credential.setApplicationRegEx("app|other");
        assertFalse(provider.loginWithVerifiedSubject("subject",null,"realm",null).authenticated());
        assertTrue(provider.loginWithVerifiedSubject("subject","app","realm",null).authenticated());
        assertFalse(provider.loginWithVerifiedSubject("subject","app","unregistered",null).authenticated());
    }
}
