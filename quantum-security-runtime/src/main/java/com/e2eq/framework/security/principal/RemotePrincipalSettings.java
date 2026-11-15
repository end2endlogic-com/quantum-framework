package com.e2eq.framework.security.principal;

import com.e2eq.framework.model.persistent.morphia.ReceivingApplication;
import com.e2eq.framework.security.principal.PrincipalSourceConfigurationException.Code;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code quantum.auth.principal-source}: unset keeps today's principal construction unchanged;
 * {@code remote} resolves every verified bearer through the {@link RemotePrincipalSource}
 * ({@code IDENTITY_ONLY_TOKENS_DESIGN.md} section 3). The receiving application id is
 * {@code quantum.security.session.application-id}, else
 * {@code quantum.application-registration.application-id}.
 *
 * <p>Startup fails with a {@link PrincipalSourceConfigurationException} naming its {@link Code}:
 * <ul>
 *   <li>a value other than {@code remote}; remote without exactly one source bean or without an
 *       application id; remote with an application-session realm selection
 *       ({@code quantum.security.session.realm} or {@code .selection-policy}), which remote mode
 *       does not apply (Auth selects the realm from {@code X-Realm} or the identity's home realm);</li>
 *   <li>remote with {@code quantum.auth.trust-token-claims=true}, with audience admission
 *       ({@code quantum.auth.expected-audience} or {@code mp.jwt.verify.audiences}: Auth's
 *       {@code APPLICATION_NOT_GRANTED} is the application gate, and identity-only tokens carry no
 *       {@code aud}), or with {@code quarkus.http.auth.proactive=false};</li>
 *   <li>in a deployment (launch mode not dev or test), any configuration source declaring
 *       {@code remote} for the active profile while the effective value is not {@code remote}: an
 *       environment variable or system property outranks {@code application.properties}, so a
 *       stray {@code QUANTUM_AUTH_PRINCIPAL_SOURCE} must not quietly bring back token claims.</li>
 * </ul>
 */
@ApplicationScoped
public class RemotePrincipalSettings {

    public static final String PROPERTY = "quantum.auth.principal-source";
    public static final String REMOTE = "remote";
    static final String TRUST_TOKEN_CLAIMS = "quantum.auth.trust-token-claims";
    static final String EXPECTED_AUDIENCE = "quantum.auth.expected-audience";
    static final String VERIFY_AUDIENCES = "mp.jwt.verify.audiences";
    static final String PROACTIVE = "quarkus.http.auth.proactive";

    @ConfigProperty(name = PROPERTY)
    Optional<String> principalSource = Optional.empty();

    @ConfigProperty(name = ReceivingApplication.SESSION_APPLICATION_ID)
    Optional<String> sessionApplicationId = Optional.empty();

    @ConfigProperty(name = ReceivingApplication.REGISTRATION_APPLICATION_ID)
    Optional<String> registrationApplicationId = Optional.empty();

    @ConfigProperty(name = "quantum.security.session.realm")
    Optional<String> sessionRealm = Optional.empty();

    @ConfigProperty(name = "quantum.security.session.selection-policy")
    Optional<String> sessionSelectionPolicy = Optional.empty();

    @ConfigProperty(name = TRUST_TOKEN_CLAIMS, defaultValue = "false")
    boolean trustTokenClaims;

    @ConfigProperty(name = EXPECTED_AUDIENCE)
    Optional<String> expectedAudience = Optional.empty();

    @ConfigProperty(name = VERIFY_AUDIENCES)
    Optional<String> verifyAudiences = Optional.empty();

    @ConfigProperty(name = PROACTIVE, defaultValue = "true")
    boolean proactiveAuthentication = true;

    @Inject
    Instance<RemotePrincipalSource> sources;

    void onStart(@Observes StartupEvent event) {
        validate();
    }

    /** Fails with the reason when the configuration cannot run; does nothing when unset and not declared. */
    public void validate() {
        check(LaunchMode.current(), declaresRemote(ConfigProvider.getConfig()));
    }

    /**
     * {@code launchMode}: the running launch mode; {@code declaredRemote}: whether any
     * configuration source declares {@code remote} for the active profile
     * ({@link #declaresRemote(Config)}).
     */
    void check(LaunchMode launchMode, boolean declaredRemote) {
        String mode = configured(principalSource);
        if (mode == null) {
            if (declaredRemote && launchMode != null && !launchMode.isDevOrTest()) {
                throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_NOT_REMOTE,
                        "this deployment's configuration declares " + PROPERTY + "=remote, but a higher-priority "
                                + "source (an environment variable such as QUANTUM_AUTH_PRINCIPAL_SOURCE, or a system "
                                + "property) unsets it; remove the override so Auth resolves the principal");
            }
            return;
        }
        if (!REMOTE.equals(mode)) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_INVALID,
                    PROPERTY + " must be '" + REMOTE + "' or unset, was '" + mode + "'");
        }
        if (sources == null || sources.isUnsatisfied()) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_UNAVAILABLE,
                    PROPERTY + "=remote needs a RemotePrincipalSource bean "
                            + "(quantum-auth-client-provider with auth.provider=quantum-auth-service); none is deployed");
        }
        if (sources.isAmbiguous()) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_UNAVAILABLE,
                    PROPERTY + "=remote found more than one RemotePrincipalSource bean");
        }
        if (applicationId() == null) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_APPLICATION_MISSING,
                    PROPERTY + "=remote needs this application's id: set "
                            + ReceivingApplication.SESSION_APPLICATION_ID + " or "
                            + ReceivingApplication.REGISTRATION_APPLICATION_ID);
        }
        if (configured(sessionRealm) != null || configured(sessionSelectionPolicy) != null) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_SESSION_REALM,
                    PROPERTY + "=remote does not apply quantum.security.session.realm or "
                            + "quantum.security.session.selection-policy: Auth selects the realm; remove them");
        }
        if (trustTokenClaims) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_TRUSTS_TOKEN_CLAIMS,
                    PROPERTY + "=remote does not trust token claims: remove " + TRUST_TOKEN_CLAIMS
                            + " (QUANTUM_AUTH_TRUST_TOKEN_CLAIMS)");
        }
        if (configured(expectedAudience) != null || configured(verifyAudiences) != null) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_AUDIENCE_CONFIGURED,
                    PROPERTY + "=remote admits the application through Auth (APPLICATION_NOT_GRANTED), not the "
                            + "token's aud/azp: remove " + EXPECTED_AUDIENCE + " and " + VERIFY_AUDIENCES
                            + " from this profile (keep them only in profiles that build the principal locally)");
        }
        if (!proactiveAuthentication) {
            throw new PrincipalSourceConfigurationException(Code.PRINCIPAL_SOURCE_LAZY_AUTHENTICATION,
                    PROPERTY + "=remote needs " + PROACTIVE + "=true (a build-time property): the refusal "
                            + "filter reads the identity before the resource is matched");
        }
    }

    /**
     * Whether any configuration source sets {@link #PROPERTY} to {@code remote} for the active
     * profile: the plain key, or a {@code %profile.} / {@code %a,b.} key naming an active profile.
     */
    static boolean declaresRemote(Config config) {
        if (config == null) {
            return false;
        }
        Set<String> profiles = activeProfiles(config);
        for (ConfigSource source : config.getConfigSources()) {
            Set<String> names;
            try {
                names = source.getPropertyNames();
            } catch (RuntimeException unlisted) {
                names = Set.of(PROPERTY);
            }
            if (names == null) {
                continue;
            }
            for (String name : names) {
                if (appliesToProfile(name, profiles) && REMOTE.equals(trimmed(expand(config, source.getValue(name))))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The declared value with {@code ${name}} / {@code ${name:default}} expressions expanded,
     * nested ones included, against the effective configuration (a blank or missing name takes
     * its default), so {@code ${MODE:${DEFAULT_MODE:remote}}} declares remote. A declaration that
     * cannot be analysed (unbalanced braces) counts as declaring remote: fail closed.
     */
    static String expand(Config config, String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        try {
            return expandFrom(config, value, 0);
        } catch (IllegalArgumentException unbalanced) {
            return REMOTE;
        }
    }

    private static String expandFrom(Config config, String value, int depth) {
        if (depth > 16) {
            throw new IllegalArgumentException("expression nested too deeply");
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < value.length()) {
            int start = value.indexOf("${", i);
            if (start < 0) {
                out.append(value, i, value.length());
                break;
            }
            out.append(value, i, start);
            int end = matchingBrace(value, start + 2);
            String body = value.substring(start + 2, end);
            int colon = topLevelColon(body);
            String name = expandFrom(config, colon < 0 ? body : body.substring(0, colon), depth + 1).trim();
            String resolved;
            try {
                resolved = name.isEmpty() ? null : config.getConfigValue(name).getValue();
            } catch (RuntimeException unresolved) {
                resolved = null;
            }
            if (resolved == null || resolved.isBlank()) {
                resolved = colon < 0 ? "" : expandFrom(config, body.substring(colon + 1), depth + 1);
            }
            out.append(resolved);
            i = end + 1;
        }
        return out.toString();
    }

    private static int matchingBrace(String value, int from) {
        int depth = 1;
        for (int i = from; i < value.length(); i++) {
            if (value.startsWith("${", i)) {
                depth++;
                i++;
            } else if (value.charAt(i) == '}' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("unbalanced expression");
    }

    private static int topLevelColon(String body) {
        int depth = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.startsWith("${", i)) {
                depth++;
                i++;
            } else if (body.charAt(i) == '}') {
                depth--;
            } else if (body.charAt(i) == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static Set<String> activeProfiles(Config config) {
        try {
            List<String> profiles = config.unwrap(SmallRyeConfig.class).getProfiles();
            return profiles == null ? Set.of() : Set.copyOf(profiles);
        } catch (IllegalArgumentException notSmallRye) {
            return Set.of();
        }
    }

    static boolean appliesToProfile(String name, Set<String> activeProfiles) {
        if (PROPERTY.equals(name)) {
            return true;
        }
        String suffix = "." + PROPERTY;
        if (name == null || !name.startsWith("%") || !name.endsWith(suffix)) {
            return false;
        }
        String profiles = name.substring(1, name.length() - suffix.length());
        return Arrays.stream(profiles.split(",")).map(String::trim).anyMatch(activeProfiles::contains);
    }

    /** True when {@code quantum.auth.principal-source=remote}. */
    public boolean remote() {
        return REMOTE.equals(configured(principalSource));
    }

    /** The receiving application id sent to Auth, or null when none is configured. */
    public String applicationId() {
        return ReceivingApplication.of(sessionApplicationId, registrationApplicationId);
    }

    public RemotePrincipalSource source() {
        return sources.get();
    }

    private static String configured(Optional<String> value) {
        return value == null ? null : value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
