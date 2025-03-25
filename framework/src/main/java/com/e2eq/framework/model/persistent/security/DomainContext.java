package com.e2eq.framework.model.persistent.security;

import com.e2eq.framework.model.persistent.base.DataDomain;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
    @Pattern(regexp = "^[a-zA-Z0-9]+\\.[a-zA-Z0-9]{1,3}$", message = "tenantId must follow the pattern 'string.min1.period.string.min1.max3'")
    protected String tenantId;
    @Pattern(regexp = "^[a-zA-Z0-9]+\\-[a-zA-Z0-9]{1,3}$", message = "defaultRealm must follow the pattern 'string.min1.dash.string.min1.max3'")
    @NotNull( message = "defaultRealm must be non null") @NonNull protected String defaultRealm;

    @Pattern(regexp = "^[a-zA-Z0-9]+\\.[a-zA-Z0-9]{1,3}$", message = "orgRefName must follow the pattern 'string.min1.period.string.min1.max3'")
    @NotNull(message = "orgRefName must be non null") @NonNull protected String orgRefName;

    @Pattern(regexp = "^\\d{10}$", message = "accountId must be exactly 10 digits")
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
