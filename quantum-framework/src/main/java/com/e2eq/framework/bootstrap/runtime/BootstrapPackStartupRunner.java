package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.ApplyBootstrapPackRequest;
import com.e2eq.framework.bootstrap.model.BootstrapPackApplyMode;
import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.model.BootstrapPackRunStatus;
import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.ResourceContext;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import com.e2eq.framework.util.EnvConfigUtils;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class BootstrapPackStartupRunner {

    @Inject
    BootstrapPackService bootstrapPackService;

    @Inject
    EnvConfigUtils envConfigUtils;

    @ConfigProperty(name = "quantum.bootstrap-pack.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "quantum.bootstrap-pack.apply.on-startup", defaultValue = "false")
    boolean applyOnStartup;

    @ConfigProperty(name = "quantum.bootstrap-pack.apply.realms")
    Optional<String> startupRealmsCsv;

    @ConfigProperty(name = "quantum.bootstrap-pack.apply.filter")
    Optional<String> startupPackFilterCsv;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String quarkusProfile;

    public void onStart() {
        if (!enabled || !applyOnStartup) {
            Log.info("BootstrapPackStartupRunner disabled by configuration (quantum.bootstrap-pack.enabled / quantum.bootstrap-pack.apply.on-startup)");
            return;
        }

        List<String> realms = resolveStartupRealms();
        List<BootstrapPackDefinition> packs = resolveStartupPacks();
        if (realms.isEmpty() || packs.isEmpty()) {
            Log.info("BootstrapPackStartupRunner: no startup bootstrap packs selected");
            return;
        }

        Log.infof("BootstrapPackStartupRunner: will apply bootstrap packs %s to realms %s", packs.stream().map(BootstrapPackDefinition::packRef).toList(), realms);
        for (String realm : realms) {
            for (BootstrapPackDefinition pack : packs) {
                try {
                    BootstrapPackRun run = SecurityCallScope.runWithContexts(
                            startupPrincipalContext(realm),
                            startupResourceContext(realm),
                            () -> bootstrapPackService.apply(new ApplyBootstrapPackRequest(
                                    pack.packRef(),
                                    BootstrapPackApplyMode.APPLY_MISSING,
                                    pack.productRef(),
                                    quarkusProfile,
                                    realm,
                                    null,
                                    null,
                                    "framework/bootstrap-startup"
                            ))
                    );
                    if (run != null && run.status() == BootstrapPackRunStatus.FAILED) {
                        Log.warnf("BootstrapPackStartupRunner: bootstrap pack %s finished with status FAILED for realm %s", pack.packRef(), realm);
                    }
                } catch (Exception ex) {
                    Log.warnf("BootstrapPackStartupRunner: failed applying pack %s for realm %s: %s", pack.packRef(), realm, ex.getMessage());
                }
            }
        }
    }

    List<String> resolveStartupRealms() {
        LinkedHashSet<String> realms = new LinkedHashSet<>();
        if (startupRealmsCsv.isPresent()) {
            String csv = startupRealmsCsv.get().trim();
            if (!csv.isEmpty() && !"none".equalsIgnoreCase(csv)) {
                for (String token : csv.split(",")) {
                    String realm = normalize(token);
                    if (realm != null) {
                        realms.add(realm);
                    }
                }
            }
        }
        if (realms.isEmpty()) {
            addIfPresent(realms, envConfigUtils.getSystemRealm());
            addIfPresent(realms, envConfigUtils.getDefaultRealm());
            addIfPresent(realms, envConfigUtils.getTestRealm());
        }
        return new ArrayList<>(realms);
    }

    List<BootstrapPackDefinition> resolveStartupPacks() {
        List<BootstrapPackDefinition> packs = bootstrapPackService.listPacks();
        Set<String> filter = resolvePackFilter();
        if (filter.isEmpty()) {
            return packs;
        }
        return packs.stream()
                .filter(pack -> filter.contains(pack.packRef()))
                .toList();
    }

    private Set<String> resolvePackFilter() {
        LinkedHashSet<String> filter = new LinkedHashSet<>();
        if (startupPackFilterCsv.isEmpty()) {
            return filter;
        }
        String csv = startupPackFilterCsv.get().trim();
        if (csv.isEmpty() || "none".equalsIgnoreCase(csv)) {
            return filter;
        }
        for (String token : csv.split(",")) {
            String normalized = normalize(token);
            if (normalized != null) {
                filter.add(normalized);
            }
        }
        return filter;
    }

    private static void addIfPresent(Set<String> target, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            target.add(normalized);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    PrincipalContext startupPrincipalContext(String realm) {
        String effectiveRealm = normalize(realm);
        DataDomain dataDomain;
        if (effectiveRealm != null && effectiveRealm.equals(envConfigUtils.getSystemRealm())) {
            dataDomain = new DataDomain(
                    envConfigUtils.getSystemOrgRefName(),
                    envConfigUtils.getSystemAccountNumber(),
                    envConfigUtils.getSystemTenantId(),
                    envConfigUtils.getDefaultDataSegment(),
                    envConfigUtils.getSystemUserId()
            );
        } else if (effectiveRealm != null && effectiveRealm.equals(envConfigUtils.getTestRealm())) {
            dataDomain = new DataDomain(
                    envConfigUtils.getTestOrgRefName(),
                    envConfigUtils.getTestAccountNumber(),
                    envConfigUtils.getTestTenantId(),
                    envConfigUtils.getDefaultDataSegment(),
                    envConfigUtils.getSystemUserId()
            );
        } else {
            dataDomain = new DataDomain(
                    envConfigUtils.getDefaultOrgRefName(),
                    envConfigUtils.getDefaultAccountNumber(),
                    envConfigUtils.getDefaultTenantId(),
                    envConfigUtils.getDefaultDataSegment(),
                    envConfigUtils.getSystemUserId()
            );
        }
        return SecurityCallScope.service(
                effectiveRealm != null ? effectiveRealm : envConfigUtils.getSystemRealm(),
                dataDomain,
                envConfigUtils.getSystemUserId(),
                "SYSTEM"
        );
    }

    ResourceContext startupResourceContext(String realm) {
        return SecurityCallScope.resource(
                startupPrincipalContext(realm),
                realm,
                "BOOTSTRAP",
                "BOOTSTRAP_PACK",
                "APPLY"
        );
    }
}
