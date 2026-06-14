package com.e2eq.framework.secrets.service;

import com.e2eq.framework.secrets.model.ManagedSecret;
import com.e2eq.framework.secrets.model.ManagedSecretRepo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WP1-B5: the realm-scoped secret resolution contract the .hxpack design
 * requires.
 *
 * Reference syntax inside configuration values:
 * <pre>
 *   ${secret.vault://&lt;secretRefName&gt;}
 * </pre>
 *
 * Resolution is ALWAYS realm-explicit: callers state which realm a value is
 * being resolved for (e.g. the realm a Camel route is tagged with), and the
 * secret is read from THAT realm's datastore only. A shared container can
 * therefore never resolve another realm's secret — there is no realm-less
 * lookup and no cross-realm fallback. Unknown references fail loud.
 *
 * Secrets are stored realm-scoped today (ManagedSecret rows in the realm's
 * database, DataDomain-stamped); this resolver is the read contract over
 * them. Provider indirection (an actual external vault behind
 * ManagedSecret.providerType) plugs in behind this same contract.
 */
@ApplicationScoped
public class SecretResolver {

    /** Matches ${secret.vault://refName} occurrences inside a config value. */
    public static final Pattern SECRET_REFERENCE =
        Pattern.compile("\\$\\{secret\\.vault://([A-Za-z0-9._\\-/]+)}");

    @Inject
    ManagedSecretRepo managedSecretRepo;

    /** True when the value contains at least one secret reference. */
    public static boolean containsSecretReference(String value) {
        return value != null && SECRET_REFERENCE.matcher(value).find();
    }

    /**
     * Resolve every {@code ${secret.vault://...}} reference in {@code value}
     * against the given realm. Fail-loud on any unknown reference.
     */
    public String resolveAll(String realmId, String value) {
        if (value == null || realmId == null || realmId.isBlank()) {
            throw new IllegalArgumentException("Secret resolution requires an explicit realm and a value.");
        }
        Matcher matcher = SECRET_REFERENCE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String refName = matcher.group(1);
            String resolved = resolve(realmId, refName).orElseThrow(() ->
                new IllegalStateException("Secret '" + refName + "' is not defined in realm '"
                    + realmId + "' — failing loud (no cross-realm fallback by design)."));
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Resolve a single secret refName within a realm. */
    public Optional<String> resolve(String realmId, String secretRefName) {
        if (realmId == null || realmId.isBlank()) {
            throw new IllegalArgumentException("Secret resolution requires an explicit realm.");
        }
        return managedSecretRepo.findByRefName(realmId, secretRefName)
            .map(ManagedSecret::getValueEncrypted);
    }
}
