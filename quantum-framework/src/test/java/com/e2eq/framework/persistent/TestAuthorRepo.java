package com.e2eq.framework.persistent;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.test.AuthorModel;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestAuthorRepo extends MorphiaRepo<AuthorModel> {
}
