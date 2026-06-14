package com.e2eq.framework.secrets;

import com.e2eq.framework.secrets.model.ManagedSecret;
import com.e2eq.framework.secrets.model.ManagedSecretRepo;
import com.e2eq.framework.secrets.service.SecretResolver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

/** WP1-B5: realm-scoped ${secret.vault://...} resolution contract. */
public class TestSecretResolver {

    static SecretResolver resolver(Map<String, String> realmSecrets) {
        SecretResolver resolver = new SecretResolver();
        injectRepo(resolver, new ManagedSecretRepo() {
            @Override
            public Optional<ManagedSecret> findByRefName(String realmId, String refName) {
                String value = realmSecrets.get(realmId + "/" + refName);
                if (value == null) {
                    return Optional.empty();
                }
                ManagedSecret secret = new ManagedSecret();
                secret.setValueEncrypted(value);
                return Optional.of(secret);
            }
        });
        return resolver;
    }

    static void injectRepo(SecretResolver resolver, ManagedSecretRepo repo) {
        try {
            var field = SecretResolver.class.getDeclaredField("managedSecretRepo");
            field.setAccessible(true);
            field.set(resolver, repo);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void resolvesReferencesAgainstTheStatedRealmOnly() {
        SecretResolver resolver = resolver(Map.of(
            "acme-com/edi.password", "s3cret-A",
            "other-com/edi.password", "s3cret-B"));

        String resolved = resolver.resolveAll("acme-com",
            "user=x;password=${secret.vault://edi.password};port=22");
        Assertions.assertEquals("user=x;password=s3cret-A;port=22", resolved);
    }

    @Test
    public void unknownReferenceFailsLoudWithNoCrossRealmFallback() {
        SecretResolver resolver = resolver(Map.of("other-com/edi.password", "s3cret-B"));
        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
            () -> resolver.resolveAll("acme-com", "${secret.vault://edi.password}"));
        Assertions.assertTrue(failure.getMessage().contains("no cross-realm fallback"));
    }

    @Test
    public void realmIsMandatory() {
        SecretResolver resolver = resolver(Map.of());
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> resolver.resolveAll(" ", "${secret.vault://x}"));
        Assertions.assertThrows(IllegalArgumentException.class,
            () -> resolver.resolve(null, "x"));
    }

    @Test
    public void detectionAndNonSecretValuesPassThrough() {
        Assertions.assertTrue(SecretResolver.containsSecretReference("${secret.vault://a/b-c.d}"));
        Assertions.assertFalse(SecretResolver.containsSecretReference("plain-value"));
        Assertions.assertEquals("plain-value", resolver(Map.of()).resolveAll("acme-com", "plain-value"));
    }
}
