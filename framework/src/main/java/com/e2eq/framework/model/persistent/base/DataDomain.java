package com.e2eq.framework.model.persistent.base;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.morphia.annotations.Entity;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.util.Objects;

@RegisterForReflection
@Entity(useDiscriminator = false)
@EqualsAndHashCode
@ToString
@SuperBuilder
public class DataDomain {

    /**
    The org id this belongs to
    */
    @JsonProperty(required = true)
    @NotNull
    protected String orgRefName;

    /**
    The account that this object belongs to
    */
    @NotNull
    @JsonProperty(required = true)
    protected String accountNum;

    /**
    The tenantId that this object belongs to.  Accounts can have multiple tenants, so this is just another level of
    indirection, allowing for permission on this object with in an account.
    */
    @NotNull
    @NotEmpty
    @JsonProperty(required = true)
    protected String tenantId;

    /**
    The datasegment is again another level of indirection with in account, tenant to provide even more granularity at a permission level.
    */
    @JsonProperty(required = true)
    protected int dataSegment;

    /**
    The userId of the owner of this object, that user typically will be considered to have additional privileges vs. non owners
    */
    @NotNull
    @NotEmpty
    @JsonProperty(required = true)
    protected String ownerId;

    protected String businessTransactionId;

    protected String locationId;

    public DataDomain() {}

    public DataDomain(
            @NotNull String orgRefName,
            @NotNull @NotEmpty String accountNum,
            @NotEmpty @NotNull String tenantId,
            int dataSegment,
            @NotNull String ownerId
    ) {
        this(orgRefName, accountNum, tenantId, null, dataSegment, ownerId);
    }

    public DataDomain(
        @NotNull String orgRefName,
        @NotNull @NotEmpty String accountNum,
        @NotEmpty @NotNull String tenantId,
        String locationId,
        int dataSegment,
        @NotNull String ownerId
    ) {
        Objects.requireNonNull(orgRefName);
        Objects.requireNonNull(accountNum);
        Objects.requireNonNull(tenantId);
        if (dataSegment < 0) {
            throw new IllegalArgumentException(
                "dataSegment cannot be negative"
            );
        }
        Objects.requireNonNull(ownerId);

        this.orgRefName = orgRefName;
        this.accountNum = accountNum;
        this.tenantId = tenantId;
        this.locationId = locationId;
        this.ownerId = ownerId;
        this.dataSegment = dataSegment;
    }

    public String getOrgRefName() {
        return orgRefName;
    }

    public void setOrgRefName(@NotNull String orgRefName) {
        Objects.requireNonNull(orgRefName);
        this.orgRefName = orgRefName;
    }

    public String getAccountNum() {
        return accountNum;
    }

    public void setAccountNum(@NotNull @NotEmpty String accountNum) {
        Objects.requireNonNull(accountNum);
        if (accountNum.isEmpty()) {
            throw new IllegalArgumentException("accountNum cannot be empty");
        }
        this.accountNum = accountNum;
    }

    public String getTenantId() {
        return (tenantId == null) ? orgRefName : tenantId;
    }

    public void setTenantId(@NotNull @NotEmpty String tenantId) {
        Objects.requireNonNull(tenantId);
        if (tenantId.isEmpty()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        this.tenantId = tenantId;
    }

    public int getDataSegment() {
        return dataSegment;
    }

    public void setDataSegment(int dataSegment) {
        if (dataSegment < 0) {
            throw new IllegalArgumentException(
                "dataSegment cannot be negative"
            );
        }
        this.dataSegment = dataSegment;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(@NotNull @NotEmpty String ownerId) {
        Objects.requireNonNull(ownerId);
        if (ownerId.isEmpty()) {
            throw new IllegalArgumentException("ownerId cannot be empty");
        }
        this.ownerId = ownerId;
    }

    public void setBusinessTransactionId(String businessTransId) {
        this.businessTransactionId = businessTransId;
    }

    public String getBusinessTransactionId() {
        return this.businessTransactionId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public String getLocationId() {
        return this.locationId;
    }

    public DataDomain clone() throws CloneNotSupportedException {
        DataDomain rc = new DataDomain();
        rc.setAccountNum(this.accountNum);
        rc.setOrgRefName(this.orgRefName);
        rc.setTenantId(this.tenantId);
        rc.setOwnerId(this.ownerId);
        rc.setDataSegment(this.dataSegment);
        rc.setBusinessTransactionId(this.businessTransactionId);
        rc.setLocationId(this.locationId);
        return rc;
    }

}
