package com.e2eq.framework.seeds;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Test-scope archetype seeder for importing simple starter policies, users, and credentials.
 *
 * Phase A: skeleton importer that parses JSONL and logs intended upserts. No DB writes here yet.
 */
public final class ArchetypeSeeder {

    private static final String DEFAULT_RESOURCE = "/seeds/basic-permissions-v1.jsonl";

    private ArchetypeSeeder() {}

    public static class ImportResult {
        public final String archetypeKey;
        public final String realm;
        public final String resourcePath;
        public int total;
        public int functionalDomainCount;
        public int policyCount;
        public int userCount;
        public int credentialCount;
        public int userProfileCount;
        public int roleAssignmentCount;
        public boolean performedWrites;

        public ImportResult(String archetypeKey, String realm, String resourcePath) {
            this.archetypeKey = archetypeKey;
            this.realm = realm;
            this.resourcePath = resourcePath;
            this.performedWrites = false;
        }
    }

    /**
     * Import the specified archetype for a given realm and data domain.
     * When write==true and a TestSeederService bean is available, performs idempotent upserts.
     * Otherwise, parses and summarizes only (dry run).
     */
    public static ImportResult importArchetype(String archetypeKey, String realm, DataDomain domain) {
        return importArchetype(archetypeKey, realm, domain, false);
    }

    public static ImportResult importArchetype(String archetypeKey, String realm, DataDomain domain, boolean write) {
        Objects.requireNonNull(archetypeKey, "archetypeKey");
        Objects.requireNonNull(realm, "realm");
        Objects.requireNonNull(domain, "dataDomain");

        String resource = DEFAULT_RESOURCE;
        if (!"basic-permissions-v1-test".equalsIgnoreCase(archetypeKey)) {
            Log.warnf("Archetype '%s' not recognized in test seeder, defaulting to %s", archetypeKey, resource);
        }

        ImportResult result = new ImportResult(archetypeKey, realm, resource);
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ArchetypeSeeder.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Seed resource not found: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                java.util.List<JsonNode> nodes = new java.util.ArrayList<>();
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    JsonNode node = mapper.readTree(line);
                    nodes.add(node);
                    String type = safe(node.path("type").asText());
                    if (type.isEmpty()) continue;
                    result.total++;
                    switch (type) {
                        case "functionalDomain" -> {
                            result.functionalDomainCount++;
                            Log.debugf("[SEED] functionalDomain area=%s domain=%s actions=%s",
                                    node.path("area").asText(),
                                    node.path("domain").asText(),
                                    node.path("actions").toString());
                        }
                        case "policy" -> {
                            result.policyCount++;
                            Log.debugf("[SEED] policy refName=%s principalId=%s rules=%d",
                                    node.path("refName").asText(),
                                    node.path("principalId").asText(),
                                    node.path("rules").isArray() ? node.path("rules").size() : 0);
                        }
                        case "user" -> {
                            result.userCount++;
                            Log.debugf("[SEED] user userId=%s tenantId=%s",
                                    node.path("userId").asText(),
                                    node.path("dataDomain").path("tenantId").asText());
                        }
                        case "credential" -> {
                            result.credentialCount++;
                            Log.debugf("[SEED] credential userId=%s realm=%s",
                                    node.path("userId").asText(),
                                    node.path("realm").asText());
                        }
                        case "userProfile" -> {
                            result.userProfileCount++;
                            Log.debugf("[SEED] userProfile userId=%s ownerId=%s",
                                    node.path("userId").asText(),
                                    node.path("ownerId").asText());
                        }
                        case "roleAssignment" -> {
                            result.roleAssignmentCount++;
                            Log.debugf("[SEED] roleAssignment userId=%s role=%s",
                                    node.path("userId").asText(),
                                    node.path("role").asText());
                        }
                        default -> Log.warnf("[SEED] Unknown type: %s", type);
                    }
                }

                // If requested, attempt to perform writes using a CDI test bean (TestSeederService)
                if (write) {
                    try {
                        io.quarkus.arc.ArcContainer container = io.quarkus.arc.Arc.container();
                        if (container != null && container.isRunning()) {
                            TestSeederService svc = container.instance(TestSeederService.class).get();
                            if (svc != null) {
                                int writes = svc.importNodes(resource, nodes, realm, domain);
                                Log.infof("[SEED] Wrote %d entities for archetype '%s' in realm '%s'", writes, archetypeKey, realm);
                                result.performedWrites = true;
                            } else {
                                Log.warn("[SEED] TestSeederService not available; performed dry-run only.");
                            }
                        } else {
                            Log.warn("[SEED] CDI container not running; performed dry-run only.");
                        }
                    } catch (Throwable t) {
                        Log.warnf(t, "[SEED] Unable to perform write import; falling back to dry-run.");
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read seed resource: " + resource, e);
        }

        Log.infof("Archetype '%s' parsed. total=%d, fd=%d, policies=%d, users=%d, creds=%d, profiles=%d, roles=%d",
                archetypeKey,
                result.total, result.functionalDomainCount, result.policyCount,
                result.userCount, result.credentialCount, result.userProfileCount, result.roleAssignmentCount);
        return result;
    }

    private static String safe(String s) { return (s == null) ? "" : s.trim(); }
}
