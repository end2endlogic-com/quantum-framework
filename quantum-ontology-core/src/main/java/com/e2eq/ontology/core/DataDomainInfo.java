package com.e2eq.ontology.core;

import java.util.Objects;

/**
 * Lightweight data domain information for ontology edge scoping.
 * Contains only the fields required for proper isolation of ontology edges:
 * - orgRefName: Organization identifier
 * - accountNum: Account number
 * - tenantId: Tenant identifier
 * - dataSegment: Data segment for additional partitioning
 * 
 * This class exists to avoid cyclic dependencies between quantum-ontology-core and quantum-models.
 * The quantum-ontology-mongo module handles conversion to/from the full DataDomain class.
 */
public record DataDomainInfo(
    String orgRefName,
    String accountNum,
    String tenantId,
    int dataSegment
) {
    /**
     * Creates a DataDomainInfo with default dataSegment of 0.
     */
    public DataDomainInfo(String orgRefName, String accountNum, String tenantId) {
        this(orgRefName, accountNum, tenantId, 0);
    }
    
    /**
     * Validates that all required fields are present.
     * @throws IllegalArgumentException if any required field is missing
     */
    public void validate() {
        if (orgRefName == null || orgRefName.isBlank()) {
            throw new IllegalArgumentException("orgRefName must be provided");
        }
        if (accountNum == null || accountNum.isBlank()) {
            throw new IllegalArgumentException("accountNum must be provided");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must be provided");
        }
        if (dataSegment < 0) {
            throw new IllegalArgumentException("dataSegment cannot be negative");
        }
    }
    
    /**
     * Checks if this DataDomainInfo has all required fields set (non-null, non-blank).
     * @return true if all required fields are set
     */
    public boolean isComplete() {
        return orgRefName != null && !orgRefName.isBlank()
            && accountNum != null && !accountNum.isBlank()
            && tenantId != null && !tenantId.isBlank()
            && dataSegment >= 0;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DataDomainInfo that = (DataDomainInfo) o;
        return dataSegment == that.dataSegment 
            && Objects.equals(orgRefName, that.orgRefName) 
            && Objects.equals(accountNum, that.accountNum) 
            && Objects.equals(tenantId, that.tenantId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(orgRefName, accountNum, tenantId, dataSegment);
    }
    
    @Override
    public String toString() {
        return "DataDomainInfo{" +
            "orgRefName='" + orgRefName + '\'' +
            ", accountNum='" + accountNum + '\'' +
            ", tenantId='" + tenantId + '\'' +
            ", dataSegment=" + dataSegment +
            '}';
    }
}

