package com.e2eq.ontology.runtime;

import com.e2eq.ontology.core.OntologyRegistry;
import com.e2eq.ontology.core.OntologyRegistry.ClassDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyChainDef;
import com.e2eq.ontology.core.OntologyRegistry.PropertyDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 2 of the ontology-federation fix: an OPT-IN publisher that pushes a realm's
 * locally-built (annotation/Morphia/YAML-derived) TBox to the central
 * quantum-ontology-service admission API, so annotation-defined ontology is federated
 * to the shared control plane instead of staying service-local.
 *
 * <p>It promotes the already-proven manual pattern (the tax demo's
 * {@code POST /v1/ontology/{realm}/tbox} then {@code :activate}, idempotent by
 * canonical hash) into a first-class, config-gated step. Behaviour is unchanged unless
 * {@code quantum.ontology.publish-to-service=true}.</p>
 *
 * <p><b>OSS independence:</b> targets a <em>generic, configurable</em> ontology REST
 * endpoint ({@code quantum.ontology.service.base-url}) via the JDK HTTP client — no
 * Helixor imports, no hardcoded hosts. <b>Boot resilience:</b> every failure is caught
 * and logged; the publish never blocks or breaks module startup. <b>Idempotency:</b>
 * the central {@code submit()} short-circuits on identical canonical hash, so
 * re-publishing an unchanged TBox is a no-op.</p>
 */
@ApplicationScoped
public class OntologyTBoxPublisher {

    @Inject
    TenantOntologyRegistryProvider registryProvider;

    /** Master switch. Default OFF — modules stay local unless a deployment opts in. */
    @ConfigProperty(name = "quantum.ontology.publish-to-service", defaultValue = "false")
    boolean publishEnabled;

    /** Base URL of the central ontology-service, e.g. http://127.0.0.1:8181 (no trailing slash needed). */
    @ConfigProperty(name = "quantum.ontology.service.base-url")
    Optional<String> serviceBaseUrl;

    /** Static service token (Bearer) for the admin-gated admission API. */
    @ConfigProperty(name = "quantum.ontology.service.token")
    Optional<String> serviceToken;

    /** Realm to publish. Defaults to the configured default realm. */
    @ConfigProperty(name = "quantum.ontology.service.realm")
    Optional<String> publishRealm;

    @ConfigProperty(name = "quantum.realmConfig.defaultRealm")
    String defaultRealm;

    /** Submit + activate (true) vs submit-as-candidate only, leaving activation to governance (false). */
    @ConfigProperty(name = "quantum.ontology.service.auto-activate", defaultValue = "true")
    boolean autoActivate;

    /** Pack id/name recorded on the submitted TBox (traceability in the control plane). */
    @ConfigProperty(name = "quantum.ontology.service.pack-id", defaultValue = "annotation-derived")
    String packId;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public boolean isEnabled() {
        return publishEnabled;
    }

    /**
     * Publish the configured realm's TBox if enabled. Best-effort and fully guarded:
     * returns quietly (logging) on any misconfiguration or transport failure.
     */
    public void publishIfEnabled() {
        if (!publishEnabled) {
            return;
        }
        String realm = publishRealm.filter(s -> !s.isBlank()).orElse(defaultRealm);
        if (serviceBaseUrl.isEmpty() || serviceBaseUrl.get().isBlank()) {
            Log.warn("quantum.ontology.publish-to-service is enabled but quantum.ontology.service.base-url is not set; skipping TBox publish");
            return;
        }
        try {
            OntologyRegistry registry = registryProvider.getRegistryForRealm(realm);
            publish(realm, registry, serviceBaseUrl.get());
        }
        catch (Exception e) {
            Log.warnf("Ontology TBox publish for realm %s failed (control plane unreachable?): %s", realm, e.getMessage());
        }
    }

    /**
     * Submit (and optionally activate) the given registry's TBox to the central service.
     * Package-visible for testing.
     */
    void publish(String realm, OntologyRegistry registry, String baseUrl) throws Exception {
        String base = baseUrl.replaceAll("/+$", "") + "/v1/ontology/" + realm;
        String payload = objectMapper.writeValueAsString(buildSubmitBody(registry));

        HttpResponse<String> submit = send(HttpRequest.newBuilder()
                .uri(URI.create(base + "/tbox"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
        if (submit.statusCode() / 100 != 2) {
            Log.warnf("TBox submit to %s returned HTTP %d: %s", base, submit.statusCode(), truncate(submit.body()));
            return;
        }

        String hash = extractHash(submit.body());
        Log.infof("Published TBox for realm %s to %s (hash=%s, classes=%d, properties=%d)",
                realm, baseUrl, hash, registry.classes().size(), registry.properties().size());

        if (autoActivate && hash != null && !hash.isBlank()) {
            HttpResponse<String> activate = send(HttpRequest.newBuilder()
                    .uri(URI.create(base + "/tbox/" + hash + ":activate"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")));
            if (activate.statusCode() / 100 != 2) {
                Log.warnf("TBox activate (hash %s) returned HTTP %d: %s", hash, activate.statusCode(), truncate(activate.body()));
            }
            else {
                Log.infof("Activated TBox %s for realm %s in the central ontology-service", hash, realm);
            }
        }
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        serviceToken.filter(t -> !t.isBlank())
                .ifPresent(t -> builder.header("Authorization", "Bearer " + t));
        return httpClient.send(builder.timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Build a TBoxSubmitRequest-shaped body from the registry (classes/properties/chains). */
    Map<String, Object> buildSubmitBody(OntologyRegistry registry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 1);
        body.put("id", packId);
        body.put("name", packId);
        body.put("openness", "open");
        body.put("source", "quantum-ontology-mongo:OntologyTBoxPublisher");

        List<Map<String, Object>> classes = new ArrayList<>();
        for (ClassDef c : registry.classes().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("parents", new ArrayList<>(c.parents()));
            m.put("disjointWith", new ArrayList<>(c.disjointWith()));
            m.put("sameAs", new ArrayList<>(c.sameAs()));
            m.put("label", c.label());
            classes.add(m);
        }
        body.put("classes", classes);

        List<Map<String, Object>> properties = new ArrayList<>();
        for (PropertyDef p : registry.properties().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name());
            m.put("domain", p.domain().orElse(null));
            m.put("range", p.range().orElse(null));
            m.put("inverse", p.inverse());
            m.put("inverseOf", p.inverseOf().orElse(null));
            m.put("transitive", p.transitive());
            m.put("symmetric", p.symmetric());
            m.put("functional", p.functional());
            m.put("subPropertyOf", new ArrayList<>(p.subPropertyOf()));
            m.put("label", p.label());
            properties.add(m);
        }
        body.put("properties", properties);

        List<Map<String, Object>> chains = new ArrayList<>();
        for (PropertyChainDef ch : registry.propertyChains()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chain", new ArrayList<>(ch.chain()));
            m.put("implies", ch.implies());
            chains.add(m);
        }
        body.put("chains", chains);
        return body;
    }

    /** Tolerantly read the canonical hash from the admission response (hash|tboxHash|id). */
    private String extractHash(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            for (String field : new String[] {"hash", "tboxHash", "id"}) {
                JsonNode v = node.get(field);
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    return v.asText();
                }
            }
        }
        catch (Exception ignored) {
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) : s;
    }
}
