package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.imports.ImportProfile;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ImportProfile entities.
 * Provides additional query methods for profile management.
 */
@ApplicationScoped
public class ImportProfileRepo extends MorphiaRepo<ImportProfile> {

    /**
     * Find a profile by its target collection.
     *
     * @param targetCollection the target collection class name
     * @param realmId the realm ID
     * @return the matching profile, if found
     */
    public Optional<ImportProfile> findByTargetCollection(String targetCollection, String realmId) {
        String query = "targetCollection='" + escapeQueryValue(targetCollection) + "'";
        List<ImportProfile> results = getListByQuery(realmId, 0, 1, query, null, null);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all profiles for a target collection.
     *
     * @param targetCollection the target collection class name
     * @param realmId the realm ID
     * @return list of matching profiles
     */
    public List<ImportProfile> findAllByTargetCollection(String targetCollection, String realmId) {
        String query = "targetCollection='" + escapeQueryValue(targetCollection) + "'";
        return getListByQuery(realmId, 0, 100, query, null, null);
    }

    private String escapeQueryValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "\\'");
    }
}
