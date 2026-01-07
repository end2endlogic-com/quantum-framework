package com.e2eq.ontology.mongo;

import com.e2eq.framework.model.persistent.base.DataDomain;
import com.e2eq.framework.model.persistent.morphia.MorphiaDataStoreWrapper;
import com.e2eq.framework.model.security.CredentialUserIdPassword;
import com.e2eq.framework.model.security.UserProfile;
import com.e2eq.ontology.core.DataDomainInfo;
import com.e2eq.ontology.core.Reasoner;
import com.e2eq.ontology.spi.OntologyEdgeProvider;
import dev.morphia.Datastore;
import dev.morphia.query.Query;
import dev.morphia.query.filters.Filter;
import dev.morphia.query.filters.Filters;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Edge provider that creates edges linking Credential entities to matching UserProfile entities
 * based on userId, email, or subject, scoped by DataDomain to prevent cross-org/account leakage.
 *
 * <p>This provider ensures that edges are only created when both the Credential and UserProfile
 * are in the same DataDomain (orgRefName, accountNum, tenantId). This prevents accidental
 * cross-organization or cross-account edge creation even when userIds/emails match.
 */
@ApplicationScoped
public class UserIdMatchingEdgeProvider implements OntologyEdgeProvider {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    @Override
    public boolean supports(Class<?> entityType) {
        return CredentialUserIdPassword.class.isAssignableFrom(entityType);
    }

    @Override
    public List<Reasoner.Edge> edges(String realmId, DataDomainInfo dataDomainInfo, Object entity) {
        if (!(entity instanceof CredentialUserIdPassword credential)) {
            return List.of();
        }

        String credentialRefName = credential.getRefName();
        if (credentialRefName == null || credentialRefName.isBlank()) {
            return List.of();
        }

        // Convert DataDomainInfo to DataDomain for query filtering
        DataDomain dataDomain = DataDomainConverter.fromInfo(dataDomainInfo);
        if (dataDomain == null ||
            dataDomain.getOrgRefName() == null ||
            dataDomain.getAccountNum() == null ||
            dataDomain.getTenantId() == null) {
            Log.warnf("UserIdMatchingEdgeProvider: Missing DataDomain fields for credential %s, skipping edge creation", credentialRefName);
            return List.of();
        }

        // Query UserProfile with DataDomain filters to prevent cross-org/account matches
        Optional<UserProfile> matchingProfile = findMatchingUserProfile(realmId, credential, dataDomain);

        if (matchingProfile.isEmpty()) {
            return List.of();
        }

        UserProfile profile = matchingProfile.get();
        String profileRefName = profile.getRefName();
        if (profileRefName == null || profileRefName.isBlank()) {
            return List.of();
        }

        // Verify both entities are in the same DataDomain (defense in depth)
        DataDomain profileDomain = profile.getDataDomain();
        if (profileDomain == null ||
            !dataDomain.getOrgRefName().equals(profileDomain.getOrgRefName()) ||
            !dataDomain.getAccountNum().equals(profileDomain.getAccountNum()) ||
            !dataDomain.getTenantId().equals(profileDomain.getTenantId())) {
            Log.warnf("UserIdMatchingEdgeProvider: DataDomain mismatch detected for credential %s and profile %s, skipping edge creation",
                     credentialRefName, profileRefName);
            return List.of();
        }

        // Create edge: Credential -> hasUserId -> UserProfile
        // Note: The actual userId string value could also be linked, but the primary relationship
        // is Credential -> UserProfile via the matching identifier
        List<Reasoner.Edge> edges = new ArrayList<>();
        edges.add(new Reasoner.Edge(
            credentialRefName,
            "Credential",
            "hasUserId",
            profileRefName,
            "UserProfile",
            false,
            Optional.empty()
        ));

        return edges;
    }

    /**
     * Finds a matching UserProfile for the given Credential within the same DataDomain.
     * Matches by userId, email, or subject (credential's entityRefName).
     *
     * @param realmId the realm/database identifier
     * @param credential the credential to match
     * @param dataDomain the DataDomain scope (orgRefName, accountNum, tenantId must match)
     * @return Optional UserProfile if found and in same DataDomain, empty otherwise
     */
    private Optional<UserProfile> findMatchingUserProfile(String realmId, CredentialUserIdPassword credential, DataDomain dataDomain) {
        Datastore datastore = morphiaDataStoreWrapper.getDataStore(realmId);
        if (datastore == null) {
            Log.warnf("UserIdMatchingEdgeProvider: Datastore not found for realm %s", realmId);
            return Optional.empty();
        }

        String credentialUserId = credential.getUserId();
        String credentialSubject = credential.getSubject();
        if ((credentialUserId == null || credentialUserId.isBlank()) &&
            (credentialSubject == null || credentialSubject.isBlank())) {
            return Optional.empty();
        }

        // Build query with DataDomain filters to prevent cross-org/account matches
        Query<UserProfile> query = datastore.find(UserProfile.class);

        // Add DataDomain filters: MUST match orgRefName, accountNum, tenantId
        List<Filter> dataDomainFilters = new ArrayList<>();
        dataDomainFilters.add(Filters.eq("dataDomain.orgRefName", dataDomain.getOrgRefName()));
        dataDomainFilters.add(Filters.eq("dataDomain.accountNum", dataDomain.getAccountNum()));
        dataDomainFilters.add(Filters.eq("dataDomain.tenantId", dataDomain.getTenantId()));
        if (dataDomain.getDataSegment() >= 0) {
            dataDomainFilters.add(Filters.eq("dataDomain.dataSegment", dataDomain.getDataSegment()));
        }

        // Match by userId, email, or credential reference
        List<Filter> matchFilters = new ArrayList<>();
        if (credentialUserId != null && !credentialUserId.isBlank()) {
            matchFilters.add(Filters.eq("userId", credentialUserId));
            matchFilters.add(Filters.eq("email", credentialUserId)); // email often matches userId
        }
        if (credentialSubject != null && !credentialSubject.isBlank()) {
            matchFilters.add(Filters.eq("credentialUserIdPasswordRef.entityRefName", credentialSubject));
        }

        if (matchFilters.isEmpty()) {
            return Optional.empty();
        }

        // Combine: DataDomain filters AND (userId OR email OR credentialRef match)
        Filter combinedFilter = Filters.and(
            Filters.and(dataDomainFilters.toArray(new Filter[0])),
            Filters.or(matchFilters.toArray(new Filter[0]))
        );

        query.filter(combinedFilter);
        UserProfile profile = query.first();

        if (profile != null) {
            // Final verification: ensure DataDomain matches
            DataDomain profileDomain = profile.getDataDomain();
            if (profileDomain != null &&
                dataDomain.getOrgRefName().equals(profileDomain.getOrgRefName()) &&
                dataDomain.getAccountNum().equals(profileDomain.getAccountNum()) &&
                dataDomain.getTenantId().equals(profileDomain.getTenantId())) {
                return Optional.of(profile);
            } else {
                Log.warnf("UserIdMatchingEdgeProvider: Found profile %s but DataDomain mismatch, ignoring", profile.getRefName());
            }
        }

        return Optional.empty();
    }
}


