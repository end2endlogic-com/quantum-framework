package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackRunStatus;
import com.e2eq.framework.bootstrap.spi.BootstrapPackRunRepository;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PendingBootstrapPacksStartupLogger {

    @Inject
    BootstrapPackStartupRunner startupRunner;

    @Inject
    BootstrapPackService bootstrapPackService;

    @Inject
    BootstrapPackRunRepository runRepository;

    @ConfigProperty(name = "quantum.bootstrap-pack.enabled", defaultValue = "true")
    boolean enabled;

    public void onStart() {
        if (!enabled) {
            return;
        }
        List<BootstrapPackDefinition> startupPacks = startupRunner.resolveStartupPacks();
        for (String realm : startupRunner.resolveStartupRealms()) {
            long pending = startupPacks.stream()
                    .filter(pack -> hasNoSuccessfulRun(pack.packRef(), realm))
                    .count();
            if (pending == 0) {
                Log.infof("BootstrapPacks: No pending startup bootstrap packs for realm %s.", realm);
            } else {
                Log.warnf("BootstrapPacks: %d pending startup bootstrap pack(s) for realm %s.", pending, realm);
            }
        }
    }

    private boolean hasNoSuccessfulRun(String packRef, String realm) {
        return runRepository.list().stream()
                .noneMatch(run -> run.status() == BootstrapPackRunStatus.COMPLETED
                        && packRef.equals(run.packRef())
                        && realm.equals(stringValue(run.scope().get("realmRef"))));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
