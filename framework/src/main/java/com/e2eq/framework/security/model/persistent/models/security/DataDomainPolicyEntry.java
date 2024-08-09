package com.e2eq.framework.security.model.persistent.models.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;
import java.util.List;

@Entity("DataDomainPolicy")
@RegisterForReflection
public @Data class DataDomainPolicyEntry {

    protected String functionalDomainString;
    protected String functionalActionString;
    protected List<DataDomain> dataDomains;
    protected String filter;

}
