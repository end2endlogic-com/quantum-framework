package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.agent.AgentConversation;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * Repository for AgentConversation per realm.
 */
@ApplicationScoped
public class AgentConversationRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    /**
     * Saves a conversation in the given realm.
     */
    public AgentConversation save(String realm, AgentConversation conversation) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm is required");
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(conversation);
    }

    /**
     * Finds a conversation by id within the realm.
     */
    public Optional<AgentConversation> findById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            AgentConversation c = ds.find(AgentConversation.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .first();
            return Optional.ofNullable(c);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Lists conversations for an agent and optional principal in the realm.
     *
     * @param realm       realm id
     * @param agentRef    agent ref name (required)
     * @param principalId optional; when set, filter by principal
     * @return list ordered by most recent first (by id)
     */
    public List<AgentConversation> list(String realm, String agentRef, String principalId) {
        if (realm == null || realm.isBlank() || agentRef == null || agentRef.isBlank()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        var q = ds.find(AgentConversation.class).filter(Filters.eq("agentRef", agentRef));
        if (principalId != null && !principalId.isBlank()) {
            q.filter(Filters.eq("principalId", principalId));
        }
        return q.iterator().toList().stream()
            .sorted((a, b) -> (b.getId() != null ? b.getId().toString() : "")
                .compareTo(a.getId() != null ? a.getId().toString() : ""))
            .toList();
    }

    /**
     * Deletes a conversation by id in the realm.
     */
    public boolean deleteById(String realm, String id) {
        if (realm == null || id == null || id.isBlank()) {
            return false;
        }
        try {
            Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
            var result = ds.find(AgentConversation.class)
                .filter(Filters.eq("_id", new org.bson.types.ObjectId(id)))
                .delete();
            return result.getDeletedCount() > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
