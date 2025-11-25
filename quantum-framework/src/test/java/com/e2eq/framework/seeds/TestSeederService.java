package com.e2eq.framework.seeds;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.CredentialRepo;
import com.e2eq.framework.model.persistent.morphia.FunctionalDomainRepo;
import com.e2eq.framework.model.persistent.morphia.PolicyRepo;
import com.e2eq.framework.model.persistent.morphia.UserProfileRepo;
import com.e2eq.framework.model.security.*;
import com.e2eq.framework.model.securityrules.FilterJoinOp;
import com.e2eq.framework.securityrules.RuleContext;
import com.e2eq.framework.model.securityrules.RuleEffect;
import com.e2eq.framework.model.securityrules.SecurityURI;
import com.e2eq.framework.model.securityrules.SecurityURIBody;
import com.e2eq.framework.model.securityrules.SecurityURIHeader;
import com.e2eq.framework.util.EncryptionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.lang3.StringUtils;

import java.util.*;

/**
 * Test-scoped CDI service that performs idempotent upserts for the JSONL seed
 * used by ArchetypeSeeder. Lives under src/test and is only available in tests.
 */
@ApplicationScoped
public class TestSeederService {

    @Inject FunctionalDomainRepo functionalDomainRepo;
    @Inject PolicyRepo policyRepo;
    @Inject UserProfileRepo userProfileRepo;
    @Inject CredentialRepo credentialRepo;
    @Inject RuleContext ruleContext;
    @Inject com.e2eq.framework.util.EnvConfigUtils envConfigUtils;

    /**
     * Import parsed JSON nodes. Returns number of write operations performed (best-effort count).
     */
    public int importNodes(String resourcePath, List<JsonNode> nodes, String realm, DataDomain defaultDomain) {
        if (nodes == null || nodes.isEmpty()) return 0;

        int writes = 0;

        // Ensure the system credential exists so repository security context resolution can succeed
        try { writes += ensureSystemCredential(); } catch (Throwable t) { Log.debug("ensureSystemCredential failed", t); }

        // Establish a temporary principal context for repository operations
        String seedUser = "system@end2endlogic.com"; // align with tests using @TestSecurity(user="system@end2endlogic.com")
        com.e2eq.framework.model.securityrules.PrincipalContext pc = new com.e2eq.framework.model.securityrules.PrincipalContext.Builder()
                .withDefaultRealm(envConfigUtils.getSystemRealm())
                .withUserId(seedUser)
                .withRoles(new String[]{"admin"})
                .withDataDomain(new DataDomain(
                        envConfigUtils.getSystemOrgRefName(),
                        envConfigUtils.getSystemAccountNumber(),
                        envConfigUtils.getSystemTenantId(),
                        0,
                        seedUser))
                .withScope("api")
                .build();

        com.e2eq.framework.model.securityrules.SecurityContext.setPrincipalContext(pc);
        // Also set a generic resource context so repo interceptors have context
        com.e2eq.framework.model.securityrules.ResourceContext rc = new com.e2eq.framework.model.securityrules.ResourceContext.Builder()
                .withRealm(envConfigUtils.getSystemRealm())
                .withArea("security")
                .withFunctionalDomain("auth")
                .withAction("seed")
                .withOwnerId(pc.getUserId())
                .build();
        com.e2eq.framework.model.securityrules.SecurityContext.setResourceContext(rc);

        // Partition by type to ensure dependencies order
        List<JsonNode> fds = new ArrayList<>();
        List<JsonNode> users = new ArrayList<>();
        List<JsonNode> creds = new ArrayList<>();
        List<JsonNode> profiles = new ArrayList<>();
        List<JsonNode> roleAssigns = new ArrayList<>();
        List<JsonNode> policies = new ArrayList<>();
        Set<String> allUserIds = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (JsonNode n : nodes) {
            String type = safe(n.path("type").asText());
            switch (type) {
                case "functionalDomain" -> fds.add(n);
                case "user" -> { users.add(n); allUserIds.add(safe(n.path("userId").asText())); }
                case "credential" -> { creds.add(n); allUserIds.add(safe(n.path("userId").asText())); }
                case "userProfile" -> { profiles.add(n); allUserIds.add(safe(n.path("userId").asText())); }
                case "roleAssignment" -> { roleAssigns.add(n); allUserIds.add(safe(n.path("userId").asText())); }
                case "policy" -> policies.add(n);
                default -> Log.warnf("[SEED] Unknown record type '%s' in %s", type, resourcePath);
            }
        }

        // functional domains first
        for (JsonNode fd : fds) {
            writes += upsertFunctionalDomain(realm, fd);
        }

        // credentials next (creates auth records and allows profile linkage)
        for (JsonNode c : creds) {
            writes += upsertCredential(realm, c, defaultDomain);
        }

        // users and profiles
        for (JsonNode u : users) {
            writes += upsertUserProfileShell(realm, u);
        }
        for (JsonNode up : profiles) {
            writes += upsertUserProfile(realm, up);
        }

        // roles onto credential
        for (JsonNode ra : roleAssigns) {
            writes += ensureRole(realm, ra);
        }

        // policies and rules
        for (JsonNode p : policies) {
            writes += upsertPolicy(realm, p);
        }

        // Ensure credentials exist in both the import realm and the system realm, independent of seed-provided realm
        for (String uid : allUserIds) {
            try { writes += ensureCredentialInRealm(uid, realm, defaultDomain); } catch (Throwable ignored) {}
            try { writes += ensureCredentialInRealm(uid, envConfigUtils.getSystemRealm(), defaultDomain); } catch (Throwable ignored) {}
        }

        // Finally, refresh rule context so tests can use the new policies
        try { ruleContext.reloadFromRepo(realm); } catch (Throwable t) { Log.debug("reloadFromRepo failed", t); }

        // Clear contexts set for seeding
        com.e2eq.framework.model.securityrules.SecurityContext.clearResourceContext();
        com.e2eq.framework.model.securityrules.SecurityContext.clearPrincipalContext();

        return writes;
    }

    private int ensureCredentialInRealm(String userId, String realm, DataDomain fallback) {
        if (userId == null || userId.isBlank() || realm == null || realm.isBlank()) return 0;
        try {
            Optional<CredentialUserIdPassword> ex = credentialRepo.findByUserId(userId, realm, true);
            if (ex.isPresent()) return 0;
            CredentialUserIdPassword cred = new CredentialUserIdPassword();
            cred.setUserId(userId);
            cred.setSubject(UUID.randomUUID().toString());
            cred.setPasswordHash(EncryptionUtils.hashPassword("Password1!"));
            cred.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
            DataDomain dd = pickDataDomainForRealm(realm, fallback, userId);
            cred.setDomainContext(new DomainContext(dd, realm));
            cred.setDataDomain(dd);
            cred.setLastUpdate(new Date());
            credentialRepo.save(realm, cred);
            return 1;
        } catch (Throwable t) {
            Log.debugf(t, "[SEED] ensureCredentialInRealm failed for %s in %s", userId, realm);
            return 0;
        }
    }

    private int ensureSystemCredential() {
        String sysRealm = envConfigUtils.getSystemRealm();
        String[] users = new String[]{ envConfigUtils.getSystemUserId(), "system@end2endlogic.com" };
        int writes = 0;
        for (String sysUser : users) {
            try {
                Optional<CredentialUserIdPassword> ex = credentialRepo.findByUserId(sysUser, sysRealm, true);
                if (ex.isPresent()) continue;
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(sysUser);
                cred.setSubject(java.util.UUID.randomUUID().toString());
                cred.setPasswordHash(EncryptionUtils.hashPassword("Password1!"));
                cred.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
                DomainContext dc = new DomainContext(new DataDomain(envConfigUtils.getSystemOrgRefName(), envConfigUtils.getSystemAccountNumber(), envConfigUtils.getSystemTenantId(), 0, sysUser), sysRealm);
                cred.setDomainContext(dc);
                cred.setLastUpdate(new java.util.Date());
                DataDomain dd = new DataDomain(envConfigUtils.getSystemOrgRefName(), envConfigUtils.getSystemAccountNumber(), envConfigUtils.getSystemTenantId(), 0, sysUser);
                cred.setDataDomain(dd);
                credentialRepo.save(sysRealm, cred);
                writes++;
            } catch (Throwable t) {
                Log.warnf(t, "[SEED] Failed to ensure system credential for %s", sysUser);
            }
        }
        return writes;
    }

    private int upsertFunctionalDomain(String realm, JsonNode node) {
        String area = safe(node.path("area").asText());
        String domain = safe(node.path("domain").asText());
        if (area.isEmpty() || domain.isEmpty()) return 0;
        try {
            Optional<FunctionalDomain> existing = functionalDomainRepo.findByRefName(realm, domain);
            FunctionalDomain fd = existing.orElseGet(FunctionalDomain::new);
            fd.setRefName(domain);
            fd.setArea(area);
            // Merge actions
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (fd.getFunctionalActions() != null) {
                for (FunctionalAction fa : fd.getFunctionalActions()) if (fa != null && fa.getRefName() != null) names.add(fa.getRefName());
            }
            if (node.has("actions") && node.get("actions").isArray()) {
                for (JsonNode a : node.get("actions")) names.add(safe(a.asText()));
            }
            List<FunctionalAction> out = new ArrayList<>(names.size());
            for (String n : names) { if (n == null || n.isBlank()) continue; FunctionalAction fa = new FunctionalAction(); fa.setRefName(n); fa.setDisplayName(n); out.add(fa); }
            fd.setFunctionalActions(out);
            functionalDomainRepo.save(realm, fd);
            return 1;
        } catch (Throwable t) {
            Log.warnf(t, "[SEED] functionalDomain upsert failed for %s/%s", area, domain);
            return 0;
        }
    }

    private int upsertCredential(String realm, JsonNode node, DataDomain fallback) {
        String userId = safe(node.path("userId").asText());
        String password = safe(node.path("password").asText());
        String credRealm = safe(node.path("realm").asText());
        if (userId.isEmpty() || password.isEmpty()) return 0;
        String useRealm = !credRealm.isEmpty() ? credRealm : realm;
        int writes = 0;
        try {
            Optional<CredentialUserIdPassword> existing = credentialRepo.findByUserId(userId, useRealm, true);
            if (existing.isPresent()) {
                // update hash if different (best-effort), keep roles
                CredentialUserIdPassword cred = existing.get();
                String hash = EncryptionUtils.hashPassword(password);
                if (!StringUtils.equals(cred.getPasswordHash(), hash)) {
                    cred.setPasswordHash(hash);
                    credentialRepo.save(cred);
                    writes++;
                }
            } else {
                // create minimal credential directly
                CredentialUserIdPassword cred = new CredentialUserIdPassword();
                cred.setUserId(userId);
                cred.setSubject(UUID.randomUUID().toString());
                cred.setPasswordHash(EncryptionUtils.hashPassword(password));
                cred.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
                DataDomain ddForUse = pickDataDomainForRealm(useRealm, fallback, userId);
                DomainContext dc = new DomainContext(ddForUse, useRealm);
                cred.setDomainContext(dc);
                cred.setLastUpdate(new Date());
                // ensure DataDomain on credential
                DataDomain dd = ddForUse;
                if (dd.getOwnerId() == null || dd.getOwnerId().isBlank()) dd.setOwnerId(userId);
                cred.setDataDomain(dd);
                credentialRepo.save(cred);
                writes++;
            }
            // Ensure a credential also exists in the target import realm (if different than useRealm)
            if (!useRealm.equals(realm)) {
                Optional<CredentialUserIdPassword> ex2 = credentialRepo.findByUserId(userId, realm, true);
                if (ex2.isEmpty()) {
                    CredentialUserIdPassword cred2 = new CredentialUserIdPassword();
                    cred2.setUserId(userId);
                    cred2.setSubject(UUID.randomUUID().toString());
                    cred2.setPasswordHash(EncryptionUtils.hashPassword(password));
                    cred2.setHashingAlgorithm(EncryptionUtils.hashAlgorithm());
                    DataDomain ddForRealm = pickDataDomainForRealm(realm, fallback, userId);
                    DomainContext dc2 = new DomainContext(ddForRealm, realm);
                    cred2.setDomainContext(dc2);
                    cred2.setLastUpdate(new Date());
                    DataDomain dd2 = ddForRealm;
                    if (dd2.getOwnerId() == null || dd2.getOwnerId().isBlank()) dd2.setOwnerId(userId);
                    cred2.setDataDomain(dd2);
                    credentialRepo.save(realm, cred2);
                    writes++;
                }
            }
        } catch (Throwable t) {
            Log.warnf(t, "[SEED] credential upsert failed for userId=%s", userId);
            return 0;
        }
        return writes;
    }

    private DataDomain pickDataDomainForRealm(String realm, DataDomain fallback, String ownerId) {
        // Use the system-scoped DataDomain only for the system realm; otherwise prefer the provided
        // fallback (which should be the target tenant for import). This ensures seeded data lands in
        // the targeted realm/tenant as requested.
        if (realm != null && realm.equalsIgnoreCase(envConfigUtils.getSystemRealm())) {
            return new DataDomain(
                    envConfigUtils.getSystemOrgRefName(),
                    envConfigUtils.getSystemAccountNumber(),
                    envConfigUtils.getSystemTenantId(),
                    0,
                    ownerId
            );
        }
        if (fallback != null) {
            DataDomain dd = new DataDomain(
                    fallback.getOrgRefName(),
                    fallback.getAccountNum(),
                    fallback.getTenantId(),
                    fallback.getDataSegment(),
                    (fallback.getOwnerId() == null || fallback.getOwnerId().isBlank()) ? ownerId : fallback.getOwnerId()
            );
            return dd;
        }
        // Default test tenant fallback
        return new DataDomain("end2endlogic","0000000001","tenant-seed-1",0, ownerId);
    }

    private int upsertUserProfileShell(String realm, JsonNode node) {
        String userId = safe(node.path("userId").asText());
        if (userId.isEmpty()) return 0;
        try {
            Optional<UserProfile> existing = userProfileRepo.getByUserId(realm, userId);
            if (existing.isPresent()) return 0;
            UserProfile up = new UserProfile();
            // Set refName to the realm so repository rule filters that key off refName can match
            up.setRefName(realm);
            up.setUserId(userId);
            up.setEmail(userId);
            // Use supplied dataDomain from seed for the target tenant
            DataDomain dd = readDataDomain(node.path("dataDomain"), userId);
            up.setDataDomain(dd);
            // link credential if present (try multiple realms, create if missing)
            Optional<CredentialUserIdPassword> oc = credentialRepo.findByUserId(userId, realm, true);
            if (oc.isEmpty()) oc = credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true);
            if (oc.isEmpty()) oc = credentialRepo.findByUserId(userId, "b2bi", true);
            if (oc.isPresent()) {
                String credRealm = oc.get().getDomainContext() != null ? oc.get().getDomainContext().getDefaultRealm() : realm;
                up.setCredentialUserIdPasswordRef(oc.get().createEntityReference(credRealm));
            } else {
                // create a minimal credential in the current realm for linkage
                upsertCredential(realm, buildCredNode(userId, "Password1!", realm), up.getDataDomain());
                credentialRepo.findByUserId(userId, realm, true).ifPresent(cred -> up.setCredentialUserIdPasswordRef(cred.createEntityReference(realm)));
            }
            userProfileRepo.save(realm, up);
            return 1;
        } catch (Throwable t) {
            Log.warnf(t, "[SEED] user shell upsert failed for %s", userId);
            return 0;
        }
    }

    private int upsertUserProfile(String realm, JsonNode node) {
        String userId = safe(node.path("userId").asText());
        if (userId.isEmpty()) return 0;
        try {
            Optional<UserProfile> existing = userProfileRepo.getByUserId(realm, userId);
            UserProfile up = existing.orElseGet(UserProfile::new);
            // Ensure refName aligns with realm for repository rule filters
            up.setRefName(realm);
            up.setUserId(userId);
            if (up.getEmail() == null) up.setEmail(userId);
            // Use supplied dataDomain from seed for the target tenant
            DataDomain dd = readDataDomain(node.path("dataDomain"), userId);
            up.setDataDomain(dd);
            // link credential (same fallback logic)
            Optional<CredentialUserIdPassword> oc = credentialRepo.findByUserId(userId, realm, true);
            if (oc.isEmpty()) oc = credentialRepo.findByUserId(userId, envConfigUtils.getSystemRealm(), true);
            if (oc.isEmpty()) oc = credentialRepo.findByUserId(userId, "b2bi", true);
            if (oc.isPresent()) {
                String credRealm = oc.get().getDomainContext() != null ? oc.get().getDomainContext().getDefaultRealm() : realm;
                up.setCredentialUserIdPasswordRef(oc.get().createEntityReference(credRealm));
            } else {
                upsertCredential(realm, buildCredNode(userId, "Password1!", realm), up.getDataDomain());
                credentialRepo.findByUserId(userId, realm, true).ifPresent(cred -> up.setCredentialUserIdPasswordRef(cred.createEntityReference(realm)));
            }
            userProfileRepo.save(realm, up);
            return 1;
        } catch (Throwable t) {
            Log.warnf(t, "[SEED] userProfile upsert failed for %s", userId);
            return 0;
        }
    }

    private int ensureRole(String realm, JsonNode node) {
        String userId = safe(node.path("userId").asText());
        String role = safe(node.path("role").asText());
        if (userId.isEmpty() || role.isEmpty()) return 0;
        try {
            Optional<CredentialUserIdPassword> existing = credentialRepo.findByUserId(userId, realm, true);
            if (existing.isEmpty()) return 0;
            CredentialUserIdPassword cred = existing.get();
            Set<String> roles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            if (cred.getRoles() != null) roles.addAll(Arrays.asList(cred.getRoles()));
            if (!roles.add(role)) return 0; // already present
            cred.setRoles(roles.toArray(new String[0]));
            credentialRepo.save(realm, cred);
            return 1;
        } catch (Throwable t) {
            Log.warnf(t, "[SEED] roleAssignment failed for %s:%s", userId, role);
            return 0;
        }
    }

    private int upsertPolicy(String realm, JsonNode node) {
        String refName = safe(node.path("refName").asText());
        String principalId = safe(node.path("principalId").asText());
        if (refName.isEmpty() || principalId.isEmpty()) return 0;
        try {
            policyRepo.findByRefName(realm, refName).ifPresent(existing -> {
                try { policyRepo.delete(realm, existing); } catch (Exception ignored) {}
            });

            Policy p = new Policy();
            p.setRefName(refName);
            p.setPrincipalId(principalId);
            // DataDomain on policy (optional); else use default
            DataDomain dd = readDataDomain(node.path("dataDomain"), null);
            if (dd == null) dd = new DataDomain("*","*","*",0,"*");
            p.setDataDomain(dd);

            if (node.has("rules") && node.get("rules").isArray()) {
                for (JsonNode r : node.get("rules")) {
                    Rule rule = toRule(r);
                    p.getRules().add(rule);
                }
            }
            policyRepo.save(realm, p);
            return 1;
        } catch (Throwable t) {
            Log.warnf(t, "[SEED] policy upsert failed for %s", refName);
            return 0;
        }
    }

    private Rule toRule(JsonNode r) {
        String name = safe(r.path("name").asText());
        String effect = safe(r.path("effect").asText());
        int priority = r.path("priority").isInt() ? r.path("priority").asInt() : 100;
        boolean finalRule = r.path("finalRule").asBoolean(false);
        // URI
        JsonNode suri = r.path("securityURI");
        SecurityURIHeader hdr = new SecurityURIHeader.Builder()
                .withIdentity(safe(suri.path("header").path("identity").asText()))
                .withArea(safe(suri.path("header").path("area").asText()))
                .withFunctionalDomain(safe(suri.path("header").path("functionalDomain").asText()))
                .withAction(safe(suri.path("header").path("action").asText()))
                .build();
        SecurityURIBody bdy = new SecurityURIBody.Builder()
                .withRealm(safe(suri.path("body").path("realm").asText()))
                .withOrgRefName(safe(suri.path("body").path("orgRefName").asText()))
                .withAccountNumber(safe(suri.path("body").path("accountNumber").asText()))
                .withTenantId(safe(suri.path("body").path("tenantId").asText()))
                .withOwnerId(safe(suri.path("body").path("ownerId").asText()))
                .withDataSegment(safe(suri.path("body").path("dataSegment").asText()))
                .withResourceId(safe(suri.path("body").path("resourceId").asText()))
                .build();

        Rule.Builder rb = new Rule.Builder()
                .withName(name)
                .withSecurityURI(new SecurityURI(hdr, bdy))
                .withEffect(StringUtils.isBlank(effect) ? RuleEffect.DENY : RuleEffect.valueOf(effect))
                .withPriority(priority)
                .withFinalRule(finalRule);

        if (r.hasNonNull("andFilterString")) rb.withAndFilterString(r.get("andFilterString").asText());
        if (r.hasNonNull("orFilterString")) rb.withOrFilterString(r.get("orFilterString").asText());
        if (r.hasNonNull("joinOp")) try { rb.withJoinOp(FilterJoinOp.valueOf(r.get("joinOp").asText())); } catch (Exception ignored) {}
        if (r.hasNonNull("preconditionScript")) rb.withPreconditionScript(r.get("preconditionScript").asText());
        if (r.hasNonNull("postconditionScript")) rb.withPostconditionScript(r.get("postconditionScript").asText());

        return rb.build();
    }

    private JsonNode buildCredNode(String userId, String password, String realm) {
        com.fasterxml.jackson.databind.node.ObjectNode n = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        n.put("type", "credential");
        n.put("userId", userId);
        n.put("password", password);
        n.put("realm", realm);
        return n;
    }

    private DataDomain readDataDomain(JsonNode dd, String ownerFallback) {
        if (dd == null || dd.isMissingNode() || dd.isNull()) return null;
        String org = safe(dd.path("orgRefName").asText());
        String acct = safe(dd.path("accountNumber").asText());
        String tenant = safe(dd.path("tenantId").asText());
        int seg = dd.path("dataSegment").isInt() ? dd.path("dataSegment").asInt() : 0;
        String owner = safe(dd.path("ownerId").asText());
        if (owner.isEmpty() && ownerFallback != null) owner = ownerFallback;
        if (org.isEmpty() && acct.isEmpty() && tenant.isEmpty() && owner.isEmpty()) return null;
        return new DataDomain(org.isEmpty()?"*":org, acct.isEmpty()?"*":acct, tenant.isEmpty()?"*":tenant, seg, owner.isEmpty()?"*":owner);
    }

    private static String safe(String s) { return (s == null) ? "" : s.trim(); }
}
