package com.e2eq.framework.model.persistent.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode
@Entity
@ToString
@RegisterForReflection
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DomainContext {
    @NotNull( message =  "tenantId must be non null")
    @NonNull
    protected String tenantId;
    @NotNull( message = "defaultRealm must be non null") @NonNull protected String defaultRealm;
    @NotNull(message = "orgRefName must be non null") @NonNull protected String orgRefName;
    @NotNull(message = "accountId must be non null") @NonNull protected String accountId;
    @Builder.Default protected int dataSegment=0;


    public DomainContext(DataDomain dataDomain, String defaultRealm) {
        this.tenantId = dataDomain.getTenantId();
        this.orgRefName = dataDomain.getOrgRefName();
        this.accountId = dataDomain.getAccountNum();
        this.defaultRealm = defaultRealm;
    }

    public DataDomain toDataDomain(String ownerId) {
        return DataDomain.builder().tenantId(tenantId).orgRefName(orgRefName).accountNum(accountId).ownerId(ownerId).dataSegment(dataSegment).build();
    }
}
