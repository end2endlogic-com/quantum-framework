package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.test.TestOrder;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestOrderRepo extends MorphiaRepo<TestOrder> {
}
