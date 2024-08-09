package com.e2eq.framework.security.model.persistent.morphia;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import com.e2eq.framework.security.model.persistent.models.security.Policy;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PolicyRepo extends MorphiaRepo<Policy> {
}
