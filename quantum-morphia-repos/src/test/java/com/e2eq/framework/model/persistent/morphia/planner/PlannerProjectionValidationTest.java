package com.e2eq.framework.model.persistent.morphia.planner;

import com.e2eq.framework.model.persistent.morphia.metadata.QueryMetadataException;
import com.e2eq.framework.model.security.UserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PlannerProjectionValidationTest {

    @Test
    public void rootProjection_unknownPath_throws() {
        String q = "expand(credentialUserIdPasswordRef) && fields:[+email,+bogusField]";
        QueryPlanner planner = new QueryPlanner();
        // plan() triggers projection validation; unknown path should throw
        assertThrows(QueryMetadataException.class, () -> planner.plan(q, UserProfile.class));
    }

    @Test
    public void rootProjection_only_id_is_allowed_even_if_not_in_model() {
        String q = "expand(credentialUserIdPasswordRef) && fields:[+_id]";
        QueryPlanner planner = new QueryPlanner();
        PlannedQuery planned = planner.plan(q, UserProfile.class);
        assertEquals(PlannerResult.Mode.AGGREGATION, planned.getMode());
        assertFalse(planned.getAggregation().isEmpty());
    }
}
