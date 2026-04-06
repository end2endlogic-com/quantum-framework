package com.e2eq.framework.rest.resources;

import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.bootstrap.model.ApplyBootstrapPackRequest;
import com.e2eq.framework.bootstrap.model.BootstrapPackApplyMode;
import com.e2eq.framework.bootstrap.model.BootstrapPackDefinition;
import com.e2eq.framework.bootstrap.model.BootstrapPackRun;
import com.e2eq.framework.bootstrap.model.BootstrapPackRunStatus;
import com.e2eq.framework.model.securityrules.PrincipalContext;
import com.e2eq.framework.model.securityrules.SecurityContext;
import com.e2eq.framework.bootstrap.runtime.BootstrapPackService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Path("/admin/bootstrap-packs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "system"})
@FunctionalMapping(area = "SYSTEM", domain = "BOOTSTRAP_PACK")
@ApplicationScoped
public class BootstrapPackAdminResource {

    @Inject
    BootstrapPackService bootstrapPackService;

    @GET
    public List<BootstrapPackDefinition> listPacks(@QueryParam("filter") String filterCsv) {
        return selectedPacks(filterCsv);
    }

    @GET
    @Path("/pending/{realm}")
    public List<PendingBootstrapPack> listPending(@PathParam("realm") String realm,
                                                  @QueryParam("filter") String filterCsv) {
        List<PendingBootstrapPack> pending = new ArrayList<>();
        for (BootstrapPackDefinition pack : selectedPacks(filterCsv)) {
            if (hasNoSuccessfulRun(pack, realm)) {
                pending.add(new PendingBootstrapPack(
                        pack.packRef(),
                        pack.packVersion(),
                        pack.productRef(),
                        pack.profileRef()
                ));
            }
        }
        pending.sort(Comparator.comparing(PendingBootstrapPack::packRef));
        return pending;
    }

    @GET
    @Path("/history/{realm}")
    public List<BootstrapPackRun> history(@PathParam("realm") String realm,
                                          @QueryParam("filter") String filterCsv) {
        Set<String> filter = parseFilter(filterCsv);
        return bootstrapPackService.listRuns().stream()
                .filter(run -> realm.equals(scopeValue(run, "realmRef")))
                .filter(run -> filter.isEmpty() || filter.contains(run.packRef()))
                .sorted(Comparator.comparing(BootstrapPackRun::startedAt).reversed())
                .toList();
    }

    @POST
    @Path("/validate/{realm}")
    public ApplyResult validateAll(@PathParam("realm") String realm,
                                   @QueryParam("filter") String filterCsv) {
        return applySelected(realm, selectedPacks(filterCsv), BootstrapPackApplyMode.VALIDATE_ONLY);
    }

    @POST
    @Path("/{realm}/{packRef}/validate")
    public BootstrapPackRun validateOne(@PathParam("realm") String realm,
                                        @PathParam("packRef") String packRef) {
        return bootstrapPackService.apply(buildApplyRequest(realm, packRef, BootstrapPackApplyMode.VALIDATE_ONLY));
    }

    @POST
    @Path("/apply/{realm}")
    public ApplyResult applyAll(@PathParam("realm") String realm,
                                @QueryParam("filter") String filterCsv,
                                @QueryParam("mode") String mode) {
        return applySelected(realm, selectedPacks(filterCsv), parseMode(mode));
    }

    @POST
    @Path("/{realm}/{packRef}/apply")
    public BootstrapPackRun applyOne(@PathParam("realm") String realm,
                                     @PathParam("packRef") String packRef,
                                     @QueryParam("mode") String mode) {
        return bootstrapPackService.apply(buildApplyRequest(realm, packRef, parseMode(mode)));
    }

    private ApplyResult applySelected(String realm, List<BootstrapPackDefinition> packs, BootstrapPackApplyMode mode) {
        List<BootstrapPackRun> runs = packs.stream()
                .map(pack -> bootstrapPackService.apply(buildApplyRequest(realm, pack.packRef(), mode)))
                .toList();
        List<String> applied = runs.stream().map(BootstrapPackRun::packRef).distinct().sorted().toList();
        return new ApplyResult(applied, runs);
    }

    private ApplyBootstrapPackRequest buildApplyRequest(String realm, String packRef, BootstrapPackApplyMode mode) {
        return new ApplyBootstrapPackRequest(
                packRef,
                mode,
                null,
                null,
                realm,
                null,
                null,
                actorRef()
        );
    }

    private String actorRef() {
        Optional<PrincipalContext> principal = SecurityContext.getPrincipalContext();
        if (principal.isPresent() && principal.get().getUserId() != null && !principal.get().getUserId().isBlank()) {
            return "bootstrap-admin/" + principal.get().getUserId();
        }
        return "bootstrap-admin/system";
    }

    private List<BootstrapPackDefinition> selectedPacks(String filterCsv) {
        Set<String> filter = parseFilter(filterCsv);
        return bootstrapPackService.listPacks().stream()
                .filter(pack -> filter.isEmpty() || filter.contains(pack.packRef()))
                .sorted(Comparator.comparing(BootstrapPackDefinition::packRef))
                .toList();
    }

    private boolean hasNoSuccessfulRun(BootstrapPackDefinition pack, String realm) {
        return bootstrapPackService.listRuns().stream()
                .noneMatch(run -> run.status() == BootstrapPackRunStatus.COMPLETED
                        && pack.packRef().equals(run.packRef())
                        && pack.packVersion().equals(run.packVersion())
                        && realm.equals(scopeValue(run, "realmRef")));
    }

    private static String scopeValue(BootstrapPackRun run, String key) {
        Object value = run.scope().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Set<String> parseFilter(String filterCsv) {
        LinkedHashSet<String> filter = new LinkedHashSet<>();
        if (filterCsv == null || filterCsv.isBlank()) {
            return filter;
        }
        for (String token : filterCsv.split(",")) {
            String normalized = token == null ? null : token.trim();
            if (normalized != null && !normalized.isEmpty()) {
                filter.add(normalized);
            }
        }
        return filter;
    }

    private static BootstrapPackApplyMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return BootstrapPackApplyMode.APPLY_MISSING;
        }
        try {
            return BootstrapPackApplyMode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unsupported bootstrap pack mode: " + mode);
        }
    }

    public record PendingBootstrapPack(
            String packRef,
            String packVersion,
            String productRef,
            String profileRef
    ) {}

    public record ApplyResult(
            List<String> applied,
            List<BootstrapPackRun> runs
    ) {}
}
