package com.e2eq.framework.secrets.rest;

import com.e2eq.framework.secrets.crypto.SecretsMigrationService;
import com.e2eq.framework.secrets.crypto.SecretsMigrationService.RealmMigrationResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only endpoints for secrets encryption migration and KEK management.
 *
 * <h3>Migration workflow:</h3>
 * <ol>
 *   <li>Generate a KEK: {@code POST /settings/secrets/admin/generate-kek}</li>
 *   <li>Configure it in {@code application.properties}: {@code quantum.secrets.kek.v1=<base64-key>}</li>
 *   <li>Restart the application</li>
 *   <li>Run migration: {@code POST /settings/secrets/admin/migrate} (all realms)
 *       or {@code POST /settings/secrets/admin/migrate/{realmId}} (single realm)</li>
 * </ol>
 */
@Path("/settings/secrets/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"platformAdmin", "admin", "system"})
public class SecretsMigrationResource {

    private static final Logger LOG = Logger.getLogger(SecretsMigrationResource.class);

    private final SecretsMigrationService migrationService;

    public SecretsMigrationResource(SecretsMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /**
     * Generates a new 256-bit AES key (Base64-encoded) suitable for use as a KEK.
     * The key is NOT automatically stored — it must be manually added to configuration.
     *
     * @return the generated key
     */
    @POST
    @Path("generate-kek")
    public Response generateKek() {
        String kek = SecretsMigrationService.generateKek();
        Map<String, String> result = new LinkedHashMap<>();
        result.put("key", kek);
        result.put("algorithm", "AES-256");
        result.put("encoding", "Base64");
        result.put("instruction", "Add to application.properties as quantum.secrets.kek.v<N>=<key>");
        LOG.info("Generated new KEK — must be configured in application.properties before use");
        return Response.ok(result).build();
    }

    /**
     * Migrates plaintext secrets to encrypted form across ALL realms.
     * Secrets that are already encrypted (iv is non-null) are skipped.
     *
     * @return per-realm migration results
     */
    @POST
    @Path("migrate")
    public Response migrateAllRealms() {
        LOG.info("Starting plaintext-to-encrypted migration for all realms");
        Map<String, RealmMigrationResult> results = migrationService.migrateAllRealms();

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        int totalMigrated = 0;
        int totalSkipped = 0;
        int totalFailed = 0;

        Map<String, Object> realmDetails = new LinkedHashMap<>();
        for (Map.Entry<String, RealmMigrationResult> entry : results.entrySet()) {
            RealmMigrationResult r = entry.getValue();
            totalMigrated += r.migrated;
            totalSkipped += r.skipped;
            totalFailed += r.failed;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("migrated", r.migrated);
            detail.put("skipped", r.skipped);
            detail.put("failed", r.failed);
            if (r.error != null) {
                detail.put("error", r.error);
            }
            realmDetails.put(entry.getKey(), detail);
        }

        response.put("totalMigrated", totalMigrated);
        response.put("totalSkipped", totalSkipped);
        response.put("totalFailed", totalFailed);
        response.put("realmCount", results.size());
        response.put("realms", realmDetails);

        return Response.ok(response).build();
    }

    /**
     * Migrates plaintext secrets to encrypted form for a single realm.
     *
     * @param realmId the realm/database to migrate
     * @return migration results
     */
    @POST
    @Path("migrate/{realmId}")
    public Response migrateRealm(@PathParam("realmId") String realmId) {
        LOG.infof("Starting plaintext-to-encrypted migration for realm '%s'", realmId);
        RealmMigrationResult result = migrationService.migrateRealm(realmId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("realm", realmId);
        response.put("migrated", result.migrated);
        response.put("skipped", result.skipped);
        response.put("failed", result.failed);
        if (result.error != null) {
            response.put("error", result.error);
        }

        return Response.ok(response).build();
    }
}
