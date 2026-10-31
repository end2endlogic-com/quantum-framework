package com.e2eq.framework.service.provisioning;

import com.e2eq.framework.api.tenant.TenantDeploymentTopology;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.service.TenantProvisioningService.ProvisioningContext;
import com.e2eq.framework.service.seed.SeedContext;
import com.e2eq.framework.service.seed.SeedLoaderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@ApplicationScoped
public class TenantInitializationPlanner {
    @Inject MigrationService migrations;
    @Inject SeedLoaderService seeds;
    @Inject ObjectMapper mapper;
    /** Supplied by the release/deployment contract; identifies the complete executable runtime. */
    @ConfigProperty(name = "quantum.provisioning.runtime-artifact-sha256")
    Optional<String> runtimeArtifactSha256;

    @ConfigProperty(name = "quantum.tenant.provisioning.initialization-owner", defaultValue = "AUTHORITY")
    TenantInitializationPlan.InitializationOwner initializationOwner = TenantInitializationPlan.InitializationOwner.AUTHORITY;

    public TenantInitializationPlan plan(ProvisioningContext context) {
        String runtime = runtimeIdentity();
        if (initializationOwner == TenantInitializationPlan.InitializationOwner.APPLICATION_RUNTIME) {
            if (!context.getArchetypes().isEmpty()) throw new IllegalStateException("PROVISIONING_PLAN_APPLICATION_BOOTSTRAP_FORBIDDEN");
            return new TenantInitializationPlan(1,runtime,context.getApplicationId(),context.getRealmId(),context.getTenantId(),
                context.getDeploymentTopology().name(),null,null,List.of(),initializationOwner);
        }
        if (context.getDeploymentTopology() == TenantDeploymentTopology.POOLED_REALM) {
            if (!context.getArchetypes().isEmpty())
                throw new IllegalStateException("PROVISIONING_PLAN_POOL_BOOTSTRAP_FORBIDDEN");
            return new TenantInitializationPlan(1, runtime, context.getApplicationId(), context.getRealmId(),
                context.getTenantId(), context.getDeploymentTopology().name(), null, null, List.of());
        }
        SeedContext seedContext = seedContext(context);
        var migrationPlan = migrations.planRealm(context.getRealmId());
        var basePlan = seeds.planApplicable(seedContext);
        List<TenantInitializationPlan.Archetype> archetypes = new ArrayList<>();
        for (String archetype : context.getArchetypes())
            archetypes.add(new TenantInitializationPlan.Archetype(archetype, seeds.planArchetype(seedContext, archetype)));
        return new TenantInitializationPlan(1, runtime, context.getApplicationId(), context.getRealmId(),
            context.getTenantId(), context.getDeploymentTopology().name(), migrationPlan, basePlan, archetypes);
    }

    public void validate(TenantInitializationPlan plan, ProvisioningContext context) {
        if (plan == null) throw new IllegalStateException("PROVISIONING_PLAN_REQUIRED");
        if (!plan.runtimeArtifactSha256().equals(runtimeIdentity()))
            throw new IllegalStateException("PROVISIONING_PLAN_RUNTIME_DRIFT: resume requires the pinned runtime artifact");
        if (!Objects.equals(plan.applicationId(), context.getApplicationId())
                || !plan.realmId().equals(context.getRealmId()) || !plan.tenantId().equals(context.getTenantId())
                || !plan.deploymentTopology().equals(context.getDeploymentTopology().name())
                || !plan.archetypes().stream().map(TenantInitializationPlan.Archetype::name).toList().equals(context.getArchetypes()))
            throw new IllegalStateException("PROVISIONING_PLAN_SCOPE_DRIFT");
        if (plan.initializationOwner() != initializationOwner)
            throw new IllegalStateException("PROVISIONING_PLAN_OWNER_DRIFT");
        if (plan.migrations() != null) {
            migrations.validatePlan(plan.migrations());
            seeds.validatePlan(seedContext(context), plan.baseSeeds());
            for (var archetype : plan.archetypes()) seeds.validatePlan(seedContext(context), archetype.seeds());
        }
    }

    public String encode(TenantInitializationPlan plan) {
        try { return mapper.writeValueAsString(plan); }
        catch (IOException e) { throw new IllegalStateException("PROVISIONING_PLAN_ENCODING_FAILED", e); }
    }

    public TenantInitializationPlan decode(String json, String sha256) {
        if (json == null || sha256 == null) throw new IllegalStateException("PROVISIONING_PLAN_UNPINNED: legacy runs require reviewed recovery");
        if (!digest(json).equals(sha256)) throw new IllegalStateException("PROVISIONING_PLAN_STORAGE_CORRUPT");
        try { return mapper.readerFor(TenantInitializationPlan.class)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json); }
        catch (IOException e) { throw new IllegalStateException("PROVISIONING_PLAN_DECODING_FAILED", e); }
    }

    public static String digest(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("PROVISIONING_PLAN_HASH_FAILED", e); }
    }

    private String runtimeIdentity() {
        String value = runtimeArtifactSha256 == null ? null : runtimeArtifactSha256.orElse(null);
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("PROVISIONING_RUNTIME_IDENTITY_REQUIRED: configure quantum.provisioning.runtime-artifact-sha256 with the deployed artifact digest");
        return value;
    }

    public static SeedContext seedContext(ProvisioningContext context) {
        return SeedContext.builder(context.getRealmId())
            .tenantId(context.getDomainContext().getTenantId()).orgRefName(context.getDomainContext().getOrgRefName())
            .accountId(context.getDomainContext().getAccountId()).ownerId(context.getCommand().getAdminUserId())
            .variable("adminUserId", context.getCommand().getAdminUserId())
            .variable("baselineAdminUserId", context.getBaselineAdminUserId())
            .variable("demoUserId", context.getDemoUserId()).build();
    }
}
