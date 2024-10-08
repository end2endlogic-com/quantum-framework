package com.e2eq.framework.model.persistent.security;

import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@RegisterForReflection
@EqualsAndHashCode
@Entity
public @Data class DataDomainPolicy {
    Map<String, DataDomainPolicyEntry> policyEntries;
}
