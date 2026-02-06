package com.e2eq.framework.model.persistent.morphia;

import com.e2eq.framework.model.persistent.agent.AgentConversationTurn;
import dev.morphia.Datastore;
import dev.morphia.query.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Repository for AgentConversationTurn per realm.
 */
@ApplicationScoped
public class AgentConversationTurnRepo {

    @Inject
    MorphiaDataStoreWrapper morphiaDataStoreWrapper;

    /**
     * Saves a turn in the given realm.
     */
    public AgentConversationTurn save(String realm, AgentConversationTurn turn) {
        if (realm == null || realm.isBlank()) {
            throw new IllegalArgumentException("realm is required");
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.save(turn);
    }

    /**
     * Lists turns for a conversation in order (turnIndex ascending).
     */
    public List<AgentConversationTurn> listByConversationId(String realm, String conversationId) {
        if (realm == null || realm.isBlank() || conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        return ds.find(AgentConversationTurn.class)
            .filter(Filters.eq("conversationId", conversationId))
            .iterator()
            .toList()
            .stream()
            .sorted((a, b) -> Integer.compare(a.getTurnIndex(), b.getTurnIndex()))
            .toList();
    }

    /**
     * Deletes all turns for a conversation (e.g. when deleting the conversation).
     */
    public long deleteByConversationId(String realm, String conversationId) {
        if (realm == null || realm.isBlank() || conversationId == null || conversationId.isBlank()) {
            return 0;
        }
        Datastore ds = morphiaDataStoreWrapper.getDataStore(realm);
        var result = ds.find(AgentConversationTurn.class)
            .filter(Filters.eq("conversationId", conversationId))
            .delete();
        return result.getDeletedCount();
    }
}
