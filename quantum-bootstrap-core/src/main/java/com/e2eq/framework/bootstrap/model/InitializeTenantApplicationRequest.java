package com.e2eq.framework.bootstrap.model;

/** Exact application-runtime binding; identity/catalog creation is deliberately absent. */
@org.eclipse.microprofile.openapi.annotations.media.Schema(requiredProperties={"executionRef","applicationId","environmentRef","realmRef","tenantRef","orgRefName","accountId","packRef","packVersion","packSha256","runtimeArtifactSha256"})
public record InitializeTenantApplicationRequest(
    String executionRef, String applicationId, String environmentRef,
    String realmRef, String tenantRef, String orgRefName, String accountId,
    String packRef, String packVersion, String packSha256, String runtimeArtifactSha256
) {}
