package com.e2eq.framework.security.model.persistent.models.security;

import lombok.Data;

import java.util.Map;

public @Data class DataDomainPolicy {
    Map<String, DataDomainPolicyEntry> policyEntries;
}
