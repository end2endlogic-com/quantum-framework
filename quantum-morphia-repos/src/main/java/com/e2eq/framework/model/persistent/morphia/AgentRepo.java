package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.agent.Agent;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Agent (LLM + context) per realm.
 */
@ApplicationScoped
public class AgentRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    /**
     * Saves an agent in the given realm.
     *
     * @param realm realm id
     * @param agent agent to save
     * @return saved agent
     */
    public Agent save(String realm, Agent agent) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm is required");
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(agent);
    }

    /**
     * Finds an agent by refName within the realm.
     *
     * @param realm   realm id
     * @param refName agent ref name (e.g. OBLIGATIONS_SUGGEST)
     * @return optional agent
     */
    public Optional<Agent> findByRefName(String realm, String refName) {
        if (realm == null || refName == null || refName.isBlank()) {
            return Optional.empty();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        Agent a = ds.find(Agent.class)
            .filter(Filters.eq("refName", refName))
            .first();
        return Optional.ofNullable(a);
    }

    /**
     * Finds an agent by id within the realm.
     *
     * @param realm realm id
     * @param id    agent id (ObjectId hex)
     * @return optional agent
     */
    public Optional<Agent> findById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            Agent a = ds.find(Agent.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .first();
            return Optional.ofNullable(a);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Lists all agents in the realm.
     *
     * @param realm realm id
     * @return list of agents (ordered by refName)
     */
    public List<Agent> list(String realm) {
        if (realm == null || realm.isBlank()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(Agent.class)
            .iterator()
            .toList()
            .stream()
            .sorted((a, b) -> (a.getRefName() != null ? a.getRefName() : "")
                .compareTo(b.getRefName() != null ? b.getRefName() : ""))
            .toList();
    }

    /**
     * Deletes an agent by id in the realm.
     *
     * @param realm realm id
     * @param id    agent id (ObjectId hex)
     * @return true if deleted
     */
    public boolean deleteById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return false;
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            var result = ds.find(Agent.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .delete();
            return result.getDeletedCount() > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
