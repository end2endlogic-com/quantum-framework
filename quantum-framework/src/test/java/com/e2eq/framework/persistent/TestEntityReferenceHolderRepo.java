package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.test.EntityReferenceHolderModel;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestEntityReferenceHolderRepo extends MorphiaRepo<EntityReferenceHolderModel> {
}
