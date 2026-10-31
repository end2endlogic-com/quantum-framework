package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.*;
import com.e2eq.framework.bootstrap.spi.BootstrapPackStepHandler;
import com.e2eq.framework.model.persistent.migration.base.MigrationPlan;
import com.e2eq.framework.model.persistent.migration.base.MigrationService;
import com.e2eq.framework.service.seed.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.*;

/** Shared exact-plan adapter. All discovery occurs during read-only preparation. */
@io.quarkus.arc.properties.IfBuildProperty(name="quantum.tenant-initialization.enabled", stringValue="true")
@ApplicationScoped
public class PinnedDataInitializationHandler implements BootstrapPackStepHandler {
    @Inject SeedLoaderService seeds;
    @Inject MigrationService migrations;
    @Inject ObjectMapper mapper;
    @Override public int priority() { return 200; }
    @Override public boolean supports(BootstrapStepKind kind) {
        return kind == BootstrapStepKind.SEED_PACK || kind == BootstrapStepKind.MIGRATIONS;
    }
    @Override public Map<String,Object> prepare(BootstrapStepRequest request) {
        if (request.kind() == BootstrapStepKind.MIGRATIONS) {
            if (!request.config().isEmpty()) throw new IllegalArgumentException("INITIALIZATION_MIGRATION_CONFIG_INVALID");
            var plan = migrations.planRealm(scope(request,"realmRef"));
            migrations.validatePlan(plan);
            return Map.of("resolvedMigrationPlan", encode(plan));
        }
        if (!request.config().keySet().equals(Set.of("seedPacks")))
            throw new IllegalArgumentException("INITIALIZATION_SEED_CONFIG_INVALID: declare exact seedPacks");
        List<SeedSelection> selected;
        try { selected = mapper.convertValue(request.config().get("seedPacks"),
            mapper.getTypeFactory().constructCollectionType(List.class,SeedSelection.class)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("INITIALIZATION_SEED_SELECTION_INVALID",e); }
        if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("INITIALIZATION_SEED_SELECTION_REQUIRED");
        var refs = selected.stream().map(s -> {
            if (s.name() == null || s.name().isBlank() || s.version() == null)
                throw new IllegalArgumentException("INITIALIZATION_EXACT_SEED_VERSION_REQUIRED");
            if (org.semver4j.Semver.parse(s.version()) == null)
                throw new IllegalArgumentException("INITIALIZATION_EXACT_SEED_VERSION_REQUIRED");
            return SeedPackRef.exact(s.name(),s.version());
        }).toList();
        var plan = seeds.planSeeds(seedContext(request),refs);
        seeds.validatePlan(seedContext(request),plan);
        return Map.of("resolvedSeedPlan",encode(plan));
    }
    @Override public BootstrapStepResult execute(BootstrapStepRequest request) {
        Map<String,Object> detail;
        if (request.kind() == BootstrapStepKind.MIGRATIONS) {
            var plan = decode(request.config().get("resolvedMigrationPlan"),MigrationPlan.class);
            if (!scope(request,"realmRef").equals(plan.realm())) throw new IllegalStateException("INITIALIZATION_MIGRATION_SCOPE_DRIFT");
            migrations.validatePlan(plan);
            if (request.mode() != BootstrapPackApplyMode.VALIDATE_ONLY) migrations.runPlan(plan,null);
            detail = Map.of("migrationPlan",plan);
        } else {
            var plan = decode(request.config().get("resolvedSeedPlan"),SeedPlan.class);
            var context = seedContext(request);
            seeds.validatePlan(context,plan);
            if (request.mode() != BootstrapPackApplyMode.VALIDATE_ONLY) seeds.applyPlan(context,plan);
            detail = Map.of("seedPlan",plan);
        }
        return new BootstrapStepResult(BootstrapStepStatus.COMPLETED,
            request.mode() == BootstrapPackApplyMode.VALIDATE_ONLY ? BootstrapStepOutcome.SKIPPED : BootstrapStepOutcome.UPDATED,
            detail);
    }
    @Override public BootstrapStepResult reconcile(BootstrapStepRequest request) {
        Map<String,Object> detail;
        if (request.kind()==BootstrapStepKind.MIGRATIONS) {
            var plan=decode(request.config().get("resolvedMigrationPlan"),MigrationPlan.class);
            if (!scope(request,"realmRef").equals(plan.realm()) || !migrations.isPlanApplied(plan))
                throw new IllegalStateException("BOOTSTRAP_RECOVERY_EFFECT_UNCERTAIN: migration receipts are incomplete");
            detail=Map.of("migrationPlan",plan);
        } else {
            var plan=decode(request.config().get("resolvedSeedPlan"),SeedPlan.class);
            if (!seeds.isPlanApplied(seedContext(request),plan))
                throw new IllegalStateException("BOOTSTRAP_RECOVERY_EFFECT_UNCERTAIN: seed receipts are incomplete; partial writes require reviewed repair");
            detail=Map.of("seedPlan",plan);
        }
        return new BootstrapStepResult(BootstrapStepStatus.COMPLETED,BootstrapStepOutcome.ALREADY_PRESENT,detail);
    }

    private SeedContext seedContext(BootstrapStepRequest r) {
        return SeedContext.builder(scope(r,"realmRef")).tenantId(scope(r,"tenantRef"))
            .orgRefName(scope(r,"orgRefName")).accountId(scope(r,"accountId")).ownerId(r.actorRef()).build();
    }
    private static String scope(BootstrapStepRequest r,String key) {
        Object v = r.scope().get(key);
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException("INITIALIZATION_SCOPE_REQUIRED: " + key);
        return s;
    }
    private String encode(Object plan) {
        try { return mapper.writeValueAsString(plan); }
        catch (IOException e) { throw new IllegalStateException("INITIALIZATION_PLAN_ENCODING_FAILED",e); }
    }
    private <T> T decode(Object value,Class<T> type) {
        if (!(value instanceof String json)) throw new IllegalStateException("INITIALIZATION_PLAN_REQUIRED");
        try { return mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json); }
        catch (IOException e) { throw new IllegalStateException("INITIALIZATION_PLAN_INVALID",e); }
    }
    public record SeedSelection(String name,String version) {}
}
