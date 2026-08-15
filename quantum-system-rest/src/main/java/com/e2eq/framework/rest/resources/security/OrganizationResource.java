package com.e2eq.framework.rest.resources.security;

import com.e2eq.framework.annotations.FunctionalAction;
import com.e2eq.framework.annotations.FunctionalMapping;
import com.e2eq.framework.model.persistent.base.ProjectionField;
import com.e2eq.framework.model.persistent.base.SortField;
import com.e2eq.framework.model.persistent.morphia.OrganizationRepo;
import com.e2eq.framework.model.security.Organization;
import com.e2eq.framework.rest.core.BaseResource;
import com.e2eq.framework.rest.models.Collection;
import com.e2eq.framework.rest.query.FilterUtils;
import com.e2eq.framework.model.securityrules.SecurityCallScope;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/security/accounts/organizations")
@RolesAllowed({"admin", "system"})
@Tag(name = "security", description = "Operations related to security")
public class OrganizationResource extends BaseResource<Organization, OrganizationRepo> {
    protected OrganizationResource(OrganizationRepo repo) {
        super(repo);
    }

    /**
     * Privileged control-plane directory read.
     *
     * <p>Organizations are global identity-directory records used by System Manager
     * across data domains. The regular generic list endpoint is intentionally
     * governed by row/data-domain filters. This endpoint is explicitly
     * system-role-only and annotation-marked as a data-scope-bypassing
     * control-plane operation before it opens the internal ignore-rules scope.</p>
     */
    @Path("privileged/list")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"system"})
    @FunctionalMapping(area = "SECURITY", domain = "ORGANIZATION")
    @FunctionalAction(value = "PRIVILEGED_LIST", bypassDataScoping = true)
    public Collection<Organization> privilegedList(@Context HttpHeaders headers,
                                                   @DefaultValue("0") @QueryParam("skip") int skip,
                                                   @DefaultValue("50") @QueryParam("limit") int limit,
                                                   @QueryParam("filter") String filter,
                                                   @QueryParam("sort") String sort,
                                                   @QueryParam("projection") String projection) {
        List<SortField> sortFields = sort == null ? null : convertToSortField(sort);
        List<ProjectionField> projectionFields = projection == null
                ? null
                : FilterUtils.convertProjectionFields(projection);
        String realmId = headers.getHeaderString("X-Realm");
        try (SecurityCallScope.Scope ignored = SecurityCallScope.openIgnoringRules()) {
            List<Organization> rows = realmId == null
                    ? repo.getListByQuery(skip, limit, filter, sortFields, projectionFields)
                    : repo.getListByQuery(realmId, skip, limit, filter, sortFields, projectionFields);
            long count = realmId == null ? repo.getCount(filter) : repo.getCount(realmId, filter);
            Collection<Organization> collection = sortFields == null
                    ? new Collection<>(rows, skip, limit, filter, count)
                    : new Collection<>(rows, skip, limit, filter, count, sortFields);
            collection.setRealm(realmId == null ? repo.getDatabaseName() : realmId);
            return collection;
        }
    }
}
