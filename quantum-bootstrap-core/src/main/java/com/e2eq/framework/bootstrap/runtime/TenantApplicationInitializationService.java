package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.api.system.SystemDirectory;
import com.e2eq.framework.bootstrap.model.*;
import com.e2eq.framework.model.security.RealmTenancyMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Initializes data in a centrally registered realm; never writes the identity catalog. */
@ApplicationScoped
public class TenantApplicationInitializationService {
    @Inject BootstrapPackService bootstrap;
    @Inject SystemDirectory directory;
    @ConfigProperty(name="quantum.tenant-initialization.application-id") Optional<String> applicationId;
    @ConfigProperty(name="quantum.tenant-initialization.environment-ref") Optional<String> environmentRef;
    @ConfigProperty(name="quantum.provisioning.runtime-artifact-sha256") Optional<String> runtimeSha;

    @ConfigProperty(name="quantum.mode", defaultValue="embedded") String mode;
    @ConfigProperty(name="quantum.system.directory.mode") Optional<String> directoryMode;

    public BootstrapPackRun initialize(InitializeTenantApplicationRequest request, String serviceSubject) {
        validateRequest(request, serviceSubject);
        String effectiveDirectory = directoryMode.filter(v -> !v.isBlank()).orElse(mode);
        if (!"remote".equals(effectiveDirectory))
            throw failure(503, "INITIALIZATION_CENTRAL_DIRECTORY_REQUIRED", "Initialization requires the remote central realm catalog");
        String app = configured(applicationId, "application-id");
        String environment = configured(environmentRef, "environment-ref");
        if (!app.equals(request.applicationId()) || !environment.equals(request.environmentRef()))
            throw failure(422, "INITIALIZATION_TARGET_MISMATCH", "Application/environment does not match this runtime");
        var realm = directory.findRealmByRefName(request.realmRef()).orElseThrow(() ->
            failure(422, "INITIALIZATION_REALM_NOT_REGISTERED", "Provision the realm through the central management plane first"));
        var domain = realm.getDomainContext();
        if (realm.getTenancyMode() != RealmTenancyMode.SINGLE_TENANT || domain == null
            || !Objects.equals(domain.getTenantId(), request.tenantRef())
            || !Objects.equals(domain.getOrgRefName(), request.orgRefName())
            || !Objects.equals(domain.getAccountId(), request.accountId()))
            throw failure(422, "INITIALIZATION_REALM_SCOPE_MISMATCH", "Request must match the registered single-tenant realm scope");
        var apply = new ApplyBootstrapPackRequest(request.packRef(), BootstrapPackApplyMode.APPLY_MISSING,
            app, environment, request.realmRef(), request.tenantRef(), null, serviceSubject,
            Map.of("orgRefName", request.orgRefName(), "accountId", request.accountId(),
                "expectedPackVersion", request.packVersion(), "expectedPackSha256", request.packSha256(),
                "expectedRuntimeArtifactSha256", request.runtimeArtifactSha256()));
        // A matching durable receipt is authoritative even if the current catalog/runtime changed.
        // BootstrapPackService checks the complete request fingerprint before returning it.
        var existing = bootstrap.findRun(request.executionRef());
        if (existing.isEmpty()) {
            if (!configured(runtimeSha, "runtime-artifact-sha256").equals(request.runtimeArtifactSha256()))
                throw failure(409, "INITIALIZATION_RUNTIME_DRIFT", "The requested runtime artifact is not deployed");
            var pack = bootstrap.listPacks().stream().filter(p -> request.packRef().equals(p.packRef()))
                .findFirst().orElseThrow(() -> failure(422, "INITIALIZATION_PACK_UNKNOWN", "Pack is not declared by this runtime"));
            if (pack.steps().isEmpty() || pack.steps().stream().anyMatch(step ->
                    step.kind() != BootstrapStepKind.SEED_PACK && step.kind() != BootstrapStepKind.MIGRATIONS))
                throw failure(422, "INITIALIZATION_PACK_KIND_UNSUPPORTED", "Initialization packs must declare migration/seed steps");
            if (!app.equals(pack.productRef()) || !request.packVersion().equals(pack.packVersion())
                || !request.packSha256().equals(BootstrapRunIdentity.fingerprint(pack)))
                throw failure(409, "INITIALIZATION_PACK_DRIFT", "Pack product/version/content does not match the admitted binding");
        }
        try { return bootstrap.apply(apply, request.executionRef()); }
        catch (IllegalStateException e) {
            if ("BOOTSTRAP_REQUEST_CONFLICT".equals(e.getMessage()))
                throw failure(409, "INITIALIZATION_REQUEST_CONFLICT", "Execution key is already bound to another request");
            if ("BOOTSTRAP_RUNTIME_DRIFT".equals(e.getMessage()) || "BOOTSTRAP_PACK_DRIFT".equals(e.getMessage()))
                throw failure(409, "INITIALIZATION_BINDING_DRIFT", "Runtime or pack changed before execution was admitted");
            throw e;
        }
    }

    public Optional<BootstrapPackRun> findRun(String key) { return bootstrap.findRun(key); }

    public BootstrapPackRun recover(String key,RecoverTenantInitializationRequest request,String actor) {
        if (request==null || request.reason()==null || request.reason().isBlank())
            throw failure(400,"INITIALIZATION_RECOVERY_REASON_REQUIRED","Record why recovery is being requested");
        var run=bootstrap.findRun(key).orElseThrow(() -> failure(404,"INITIALIZATION_RUN_NOT_FOUND","No initialization run exists"));
        if (!configured(applicationId,"application-id").equals(run.productRef())
            || !configured(environmentRef,"environment-ref").equals(run.scope().get("environmentRef")))
            throw failure(422,"INITIALIZATION_TARGET_MISMATCH","Stored run is outside this runtime");
        var realm=directory.findRealmByRefName((String)run.scope().get("realmRef")).orElseThrow(() -> failure(422,"INITIALIZATION_REALM_NOT_REGISTERED","Central realm is missing"));
        var domain=realm.getDomainContext();
        if (realm.getTenancyMode()!=RealmTenancyMode.SINGLE_TENANT || domain==null
            || !Objects.equals(domain.getTenantId(),run.scope().get("tenantRef"))
            || !Objects.equals(domain.getOrgRefName(),run.scope().get("orgRefName"))
            || !Objects.equals(domain.getAccountId(),run.scope().get("accountId")))
            throw failure(422,"INITIALIZATION_REALM_SCOPE_MISMATCH","Central realm scope changed");
        try { return bootstrap.recover(key,request.expectedRevision(),actor+": "+request.reason()); }
        catch(IllegalStateException e) {
            throw failure(409,e.getMessage().split(":",2)[0],e.getMessage());
        }
    }

    public static String packDigest(BootstrapPackDefinition pack) {
        return BootstrapRunIdentity.fingerprint(pack);
    }
    private static void validateRequest(InitializeTenantApplicationRequest r, String subject) {
        if (r == null || blank(subject) || blank(r.applicationId()) || blank(r.environmentRef())
            || blank(r.realmRef()) || blank(r.tenantRef()) || blank(r.orgRefName()) || blank(r.accountId())
            || blank(r.packRef()) || blank(r.packVersion()) || r.executionRef() == null
            || !r.executionRef().matches("[A-Za-z0-9_-]{16,128}")
            || !digest(r.packSha256()) || !digest(r.runtimeArtifactSha256()))
            throw failure(400, "INITIALIZATION_REQUEST_INVALID", "Complete scope, execution key and SHA256 bindings are required");
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static boolean digest(String s) { return s != null && s.matches("[0-9a-f]{64}"); }
    private static String configured(Optional<String> value, String field) {
        return value.filter(v -> !v.isBlank()).orElseThrow(() ->
            failure(503, "INITIALIZATION_RUNTIME_UNCONFIGURED", "Missing tenant initialization binding: " + field));
    }
    private static TenantInitializationFailure failure(int status, String code, String detail) {
        return new TenantInitializationFailure(status, code, detail);
    }
}
