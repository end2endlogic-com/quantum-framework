package com.e2eq.framework.bootstrap.runtime;

import com.e2eq.framework.model.persistent.morphia.MorphiaRepo;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BootstrapPackRunRecordRepo extends MorphiaRepo<BootstrapPackRunRecord> { }
