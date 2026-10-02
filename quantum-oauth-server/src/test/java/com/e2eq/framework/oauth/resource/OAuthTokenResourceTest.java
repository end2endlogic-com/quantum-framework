package com.e2eq.framework.oauth.resource;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.DomainContext;
import com.e2eq.framework.model.persistent.base.ActiveStatus;
import com.e2eq.framework.model.auth.provider.jwtToken.TokenUtils;
import com.nimbusds.jwt.SignedJWT;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class OAuthTokenResourceTest {
    private Response issue(CredentialUserIdPassword credential) throws Exception {
        var resource=new OAuthTokenResource(); resource.issuer="https://test.invalid"; resource.tokenDuration=60;
        var method=OAuthTokenResource.class.getDeclaredMethod("issueTokens",CredentialUserIdPassword.class);
        method.setAccessible(true); return (Response)method.invoke(resource,credential);
    }
    @Test void disabledCredentialsCannotMint() throws Exception {
        for (var status : new ActiveStatus[]{ActiveStatus.INACTIVE,ActiveStatus.DELETED}) {
            var credential=new CredentialUserIdPassword(); credential.setActiveStatus(status);
            assertEquals(400,issue(credential).getStatus());
        }
    }
    @Test void refreshCarriesRequiredRealmAndIdentity() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var field=TokenUtils.class.getDeclaredField("cachedPrivateKey"); field.setAccessible(true);
        Object previous=field.get(null); field.set(null,generator.generateKeyPair().getPrivate());
        try {
            var credential=new CredentialUserIdPassword(); credential.setSubject("subject"); credential.setUserId("user");
            credential.setDomainContext(DomainContext.builder().tenantId("tenant").orgRefName("org").accountId("account").defaultRealm("realm").build());
            Response response=issue(credential); assertEquals(200,response.getStatus());
            var claims=SignedJWT.parse((String)((Map<?,?>)response.getEntity()).get("refresh_token")).getJWTClaimsSet();
            assertEquals("realm",claims.getStringClaim("realm")); assertEquals("user",claims.getStringClaim("userId"));
        } finally { field.set(null,previous); }
    }
}
