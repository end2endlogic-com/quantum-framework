package com.e2eq.framework.rest.filters;

import io.quarkus.logging.Log;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Supplier;

@ApplicationScoped
public class RolesAugmentor implements SecurityIdentityAugmentor {

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
       // Log.debugf("RolesAugmenter: augmenting identity %s \n", identity);
        return Uni.createFrom().item(build(identity));
        // Do 'return context.runBlocking(build(identity));'
        // if a blocking call is required to customize the identity
    }

    private Supplier<SecurityIdentity> build(SecurityIdentity identity) {
        if(identity.isAnonymous()) {
            return () -> identity;
        } else {
            // create a new builder and copy principal, attributes, credentials and roles from the original identity
            QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);

            // add user role by default we already know at least we are not anonymous give the above else condition
            if (identity.getRoles().contains("user") == false)
                builder.addRole("user");

            return builder::build;
        }
    }
}
