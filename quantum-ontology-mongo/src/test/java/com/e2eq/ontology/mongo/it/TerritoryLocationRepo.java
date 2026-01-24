package com.e2eq.ontology.mongo.it;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Repository for TerritoryLocation entities.
 * Uses standard MorphiaRepo getList which automatically applies
 * permission filters from the security context.
 */
@ApplicationScoped
public class TerritoryLocationRepo extends MorphiaRepo<TerritoryLocation> {
}
