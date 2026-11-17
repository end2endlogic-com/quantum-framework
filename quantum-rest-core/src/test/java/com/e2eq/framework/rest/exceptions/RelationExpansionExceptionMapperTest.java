package com.e2eq.framework.rest.exceptions;

import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansionException;
import com.e2eq.framework.model.persistent.morphia.planner.RelationExpansionException.Code;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelationExpansionExceptionMapperTest {

    @Test
    void aRefusedExpandAnswersWithItsTypedStatusAndBody() {
        Response r = new RelationExpansionExceptionMapper().toResponse(
                new RelationExpansionException(Code.INTERNAL_RELATION, "orderRows", "internal"));
        assertEquals(400, r.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getEntity();
        assertEquals("expand_internal_relation", body.get("code"));
        assertEquals("orderRows", body.get("relation"));

        assertEquals(422, new RelationExpansionExceptionMapper().toResponse(
                new RelationExpansionException(Code.TOO_MANY_TARGETS, "r", "cap")).getStatus());
        assertEquals(503, new RelationExpansionExceptionMapper().toResponse(
                new RelationExpansionException(Code.ONTOLOGY_UNAVAILABLE, null, "refused")).getStatus());
    }
}
